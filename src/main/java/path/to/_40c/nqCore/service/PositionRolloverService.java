package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.LegScope;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Rolls a book's LIVE legs onto its next contract: close the book's legs in hand, re-open
 * the same structure at the current ATM on the target contract, and re-strike the points
 * accounting exactly like a recenter — bank the closed segment's points into the book's
 * OWN banked chain and reset that chain's baseline to the roll spot. entrySpot/exitSpot
 * are left untouched (immutable original-entry / final-exit reference for reporting).
 *
 * Book isolation is leg-structural: the position row is shared per signal, so every
 * selection here filters legs by their BOOK column — the weekly expiry-day roll can never
 * touch a monthly leg and the monthly roll can never touch the weekly synthetic, even in
 * monthly-expiry week when both books hold the identical contract.
 *
 * Both books roll the same way: the caller syncs the book's symbol row first (weekly:
 * date-gated promotion on rollover day; monthly: DTE rule), then this service moves the
 * position onto whatever the CURRENT slot says. The no-churn guard makes a trigger fire
 * safe on any day — a position already on the current contract is a logged no-op, so
 * only a genuine contract change ever closes and re-buys.
 *
 * - rollOverWeekly: 14:47 trigger via SignalService.handleWeeklyRollOver.
 * - rollOverMonthly: nqTicker-triggered (cadence logic lives there) via
 *   SignalService.handleMonthlyRollOver. Closed legs are stamped with the book's
 *   futures-equivalent expected factor (qty/2) so capture stays on the same scale as
 *   the rest of LONG_MONTHLY accounting.
 */
@Service
@Slf4j
public class PositionRolloverService {
    private final PositionRepository positionRepository;
    private final PositionUtil positionUtil;
    private final ComputeUtil computeUtil;
    private final PostTradeService postTradeService;
    private final PositionCloseService closingService;

    public PositionRolloverService(PositionRepository positionRepository, PositionUtil positionUtil, ComputeUtil computeUtil,
                                PostTradeService postTradeService, PositionCloseService closingService) {
        this.positionRepository = positionRepository;
        this.positionUtil = positionUtil;
        this.computeUtil = computeUtil;
        this.postTradeService = postTradeService;
        this.closingService = closingService;
    }

    /**
     * Weekly roll of the SYNTH_WEEKLY book — sell whatever is in hand, buy the same
     * structure at the current ATM on the current weekly contract (per the symbol row,
     * promoted by WeeklySymbolService.syncTradedContract before this is called).
     */
    public void rollOverWeekly(String signalPrice) {
        rollOverBook(SYNTH_WEEKLY, signalPrice, 1.0, computeUtil.weeklyContractPrefix(),
                trade -> computeUtil.buildWeeklyInstrument(signalPrice, trade));
    }

    /**
     * Monthly roll of the LONG_MONTHLY book — sell whatever is in hand, buy the same
     * structure at the current ATM on the latest monthly contract (per the symbol row,
     * synced by MonthlySymbolService before this is called). expectedFactor 0.5 keeps
     * the closed segment's per-leg expected PnL on the futures-equivalent scale.
     */
    public void rollOverMonthly(String signalPrice) {
        rollOverBook(LONG_MONTHLY, signalPrice, 0.5, computeUtil.monthlyContractPrefix(),
                trade -> computeUtil.buildMonthlyInstrument(signalPrice, trade));
    }

    /**
     * 1. Close the Live Position first, through the SAME per-leg bookkeeping as a signal close
     *    (applyCloseResult: CLOSED / PENDING_CLOSE / FAILED with closeOrderIds recorded).
     * 2. Open the new legs only if every close confirmed CLOSED. An abort — close not confirmed,
     *    or the open-side quote fetch failing after the closes executed — SAVES the row first:
     *    the leg states and order ids survive, PENDING_CLOSE legs belong to the reconciler, and
     *    a retried trigger can never re-sell a leg the broker already closed.
     * 3. Open the new legs through the normal open bookkeeping (recordOpenResult): a working
     *    order rests as PENDING_OPEN for the reconciler, a partial fill is trimmed to the filled
     *    quantity (row promoted PARTIAL so the orphan sweep sees it), a no-fill is FAILED.
     *
     * Re-strike accounting is identical to a recenter: bank the closed segment's points into
     * bankedPoints and reset baselineSpot to the rollover spot. Each closed leg is stamped
     * with expectedPnl = qty × expectedFactor × the segment's spot points, the denominator
     * calcPnL later uses for that leg's pnlCapturePct (its share of the segment move).
     *
     * No-churn guard: a roll happens ONLY when the held legs sit on a different contract
     * than the current slot (targetPrefix). Same contract — even with the ATM drifted —
     * is a no-op: rolling means moving to the new contract, never re-striking on the
     * current one (the weekly recenter is a separate flow; LONG_MONTHLY never recenters
     * by policy). This is what makes the daily trigger fire safe on non-roll days.
     */
    private void rollOverBook(String book, String signalPrice, double expectedFactor, String targetPrefix,
                              Function<Position, List<LegOrder>> legBuilder) {
        Position tradeToRollOver = positionUtil.findLiveTradesWithLiveOrderBooks(book);
        if (tradeToRollOver == null) {
            log.info("No Live {} trades to rollover.", book);
            return;
        }
        List<WeeklyLeg> bookLegs = LegScope.of(tradeToRollOver, book);
        if (targetPrefix != null && bookLegs.stream()
                .allMatch(leg -> leg.getInstrument().startsWith(targetPrefix))) {
            log.info("Rollover {} skipped — legs already on the current contract {}; nothing to roll",
                    book, targetPrefix);
            return;
        }
        List<WeeklyLeg> unsettled = positionUtil.findUnsettledLegs(book);
        if (unsettled != null && !unsettled.isEmpty()) {
            log.error("Rollover {} BLOCKED — {} leg(s) of this book still have an order unconfirmed at the broker [{}]; "
                    + "rolling now could stack the re-open on an order that may yet fill. The daily trigger retries "
                    + "once the reconciler settles them", book, unsettled.size(), PositionOpenService.describeUnsettled(unsettled));
            return;
        }
        log.info("Rolling over {} legs of position id={}: {}", book, tradeToRollOver.getId(),
                bookLegs.stream().map(WeeklyLeg::getInstrument).toList());
        List<LegOrder> legOrder = legBuilder.apply(tradeToRollOver);

        Instant closeStart = Instant.now();
        String[] liveIns = bookLegs.stream().map(WeeklyLeg::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotesOfToCloseTrade = positionUtil.getQuote(liveIns);
        if (quotesOfToCloseTrade.isEmpty()) {
            log.error("Quote map is empty for close leg — aborting rollover (auth missing or Kite error)");
            return;
        }
        List<CompletableFuture<Void>> closeFuts = bookLegs.stream()
            .map(toClose -> CompletableFuture.runAsync(() -> {
                log.debug("WeeklyLeg to rollover is: {}", toClose);
                String oppositeTransaction = BUY.equals(toClose.getSide()) ? SELL : BUY;
                Quote q = quotesOfToCloseTrade.get(toClose.getExchangeSymbol());
                if (q != null) {
                    if (BUY.equals(oppositeTransaction))
                        toClose.setBuyIntendedPrice(q.lastPrice);
                    else
                        toClose.setSellIntendedPrice(q.lastPrice);
                }
                try {
                    ExecResult er = positionUtil.placeAggressiveOrder(q, toClose.getInstrument(), oppositeTransaction, toClose.getQuantity(), EXIT);
                    closingService.applyCloseResult(tradeToRollOver, toClose, q, oppositeTransaction, er);
                } catch (Exception e) {
                    log.error("Exception closing order during rollover for {}: {}", toClose.getInstrument(), e.getMessage(), e);
                    toClose.setStatus(FAILED);
                }
            }, PositionUtil.LEG_EXEC))
            .toList();
        CompletableFuture.allOf(closeFuts.toArray(new CompletableFuture[0])).join();

        long closeMs = Duration.between(closeStart, Instant.now()).toMillis();
        boolean allClosed = bookLegs.stream().allMatch(l -> CLOSED.equals(l.getStatus()));
        if (!allClosed) {
            tradeToRollOver.setStatus(LegScope.rollUpStatus(tradeToRollOver));
            Position saved = positionRepository.save(tradeToRollOver);
            log.error("Rollover {} aborted — not all held legs closed; leg states SAVED (row status={}): "
                    + "PENDING_CLOSE legs belong to the reconciler, no re-open attempted, banking untouched", book, saved.getStatus());
            log.info("[PERFORMANCE] rollover {} | close={}ms | open=0ms | total={}ms (aborted)", book, closeMs, closeMs);
            postTradeService.afterClose(saved);
            return;
        }
        positionUtil.setTradeExecPricesForRollOver(bookLegs, true, false);
        Instant openStart = Instant.now();
        List<WeeklyLeg> childOrderBook = new ArrayList<WeeklyLeg>();
        double rolloverPrice = signalPrice != null ? Double.valueOf(signalPrice) : 0.0;
        double base = baselineFor(tradeToRollOver, book, rolloverPrice);
        double segment = SHORT.equals(tradeToRollOver.getDirection()) ? base - rolloverPrice : rolloverPrice - base;
        bankSegment(tradeToRollOver, book, segment, rolloverPrice);
        bookLegs.forEach(leg -> leg.setExpectedPnl(ComputeUtil.rnd(leg.getQuantity() * expectedFactor * segment)));
        log.info("rollover {} re-strike | segment={}pts newBaseline={} expectedFactor={}",
            book, Math.round(segment * 100.0) / 100.0, rolloverPrice, expectedFactor);
        String[] ltpIns = legOrder.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        log.debug("OpenTrade ltpIns is: {}", Arrays.toString(ltpIns));
        Map<String, Quote> quotesOfToOpenTrade = positionUtil.getQuote(ltpIns);
        if (quotesOfToOpenTrade.isEmpty()) {
            tradeToRollOver.setStatus(LegScope.rollUpStatus(tradeToRollOver));
            Position saved = positionRepository.save(tradeToRollOver);
            log.error("Quote map is empty for open leg — rollover {} open aborted AFTER the closes executed; "
                    + "closed leg states and banked points SAVED (row status={}), position must be re-opened manually", book, saved.getStatus());
            long abortOpenMs = Duration.between(openStart, Instant.now()).toMillis();
            log.info("[PERFORMANCE] rollover {} | close={}ms | open={}ms | total={}ms (aborted at open quote)", book, closeMs, abortOpenMs, closeMs + abortOpenMs);
            postTradeService.afterClose(saved);
            return;
        }
        List<CompletableFuture<Void>> openFuts = legOrder.stream()
            .map(w -> CompletableFuture.runAsync(() -> {
                log.debug("LegOrder to place order is: {}", w);
                int totalQty = w.getLots() * LOT_SIZE;
                try {
                    ExecResult er = positionUtil.placeAggressiveOrder(quotesOfToOpenTrade.get(w.getExchangeSymbol()), w.getInstrument(), w.getSide(), totalQty, ENTRY);
                    PositionOpenService.recordOpenResult(w, quotesOfToOpenTrade.get(w.getExchangeSymbol()), er);
                    PositionUtil.alertIfMonthlySpreadExcessive(book, w.getInstrument(), "ROLL-ENTRY", w.getOpenSpreadPaid());
                } catch (Exception e) {
                    log.error("Exception opening order during rollover for {}: {}", w.getInstrument(), e.getMessage(), e);
                }
            }, PositionUtil.LEG_EXEC))
            .toList();
        CompletableFuture.allOf(openFuts.toArray(new CompletableFuture[0])).join();
        long openMs = Duration.between(openStart, Instant.now()).toMillis();
        log.info("[PERFORMANCE] rollover {} | close={}ms | open={}ms | total={}ms (excl. fill retrieval)", book, closeMs, openMs, closeMs + openMs);

        legOrder.forEach(pojo -> {
            WeeklyLeg b = new WeeklyLeg();
            b.setBook(pojo.getBook());
            b.setInstrument(pojo.getInstrument());
            b.setExchangeSymbol(pojo.getExchangeSymbol());
            b.setSide(pojo.getSide());
            b.setOpenOrderId(pojo.getOpenOrderId());
            b.setOpenSpreadPaid(pojo.getOpenSpreadPaid());
            b.setPosition(pojo.getParentPosition());
            b.setMoneyness(pojo.getMoneyness());
            int filledQty = pojo.getOpenFilledQty();
            boolean mayStillFill = Boolean.TRUE.equals(pojo.getOpenOrderMayBeLive())
                    && !Boolean.TRUE.equals(pojo.getOpenFullyFilled());
            if (mayStillFill) {
                b.setLots(pojo.getLots());
                b.setQuantity(pojo.getLots() * LOT_SIZE);
                b.setStatus(PENDING_OPEN);
                log.error("[ENTRY] {} roll-open order {} still working at broker (filled={}/{}) — leg PENDING_OPEN, "
                        + "reconciler will settle it from the tradebook",
                    b.getInstrument(), b.getOpenOrderId(), filledQty, b.getQuantity());
            } else if (filledQty > 0) {
                b.setLots(filledQty / LOT_SIZE);
                b.setQuantity(filledQty);
                b.setStatus(LIVE);
            } else {
                b.setLots(pojo.getLots());
                b.setQuantity(pojo.getLots() * LOT_SIZE);
                b.setStatus(FAILED);
            }
            Quote q = quotesOfToOpenTrade.get(pojo.getExchangeSymbol());
            if (q != null) {
                if (BUY.equals(pojo.getSide()))
                    b.setBuyIntendedPrice(q.lastPrice);
                else
                    b.setSellIntendedPrice(q.lastPrice);
            }
            childOrderBook.add(b);
        });
        tradeToRollOver.setLegs(childOrderBook);
        positionUtil.setTradeExecPricesForRollOver(childOrderBook, false, true);
        String rolledUp = LegScope.rollUpStatus(tradeToRollOver);
        boolean allFullyFilled = legOrder.stream().allMatch(p -> Boolean.TRUE.equals(p.getOpenFullyFilled()));
        boolean anyPendingOpen = childOrderBook.stream().anyMatch(l -> PENDING_OPEN.equals(l.getStatus()));
        boolean anyFilled = legOrder.stream().anyMatch(p -> p.getOpenFilledQty() > 0);
        if (anyFilled && !allFullyFilled && !anyPendingOpen && LIVE.equals(rolledUp)) {
            rolledUp = PARTIAL;
        }
        tradeToRollOver.setStatus(rolledUp);
        var liveTrade = positionRepository.save(tradeToRollOver);
        log.info("Live {} Position after rollOver completed is: {}", book, liveTrade);
        if (LegScope.isTerminal(liveTrade)) {
            log.error("Rollover {} closed the held legs but opened nothing — position id={} is terminal; "
                    + "running post-close accounting instead of post-open", book, liveTrade.getId());
            postTradeService.afterClose(liveTrade);
            return;
        }
        postTradeService.afterOpen(liveTrade);
    }

    /** The book's current baseline: the weekly chain for SYNTH_WEEKLY, the monthly chain for LONG_MONTHLY. */
    private static double baselineFor(Position trade, String book, double fallback) {
        Double baseline = LONG_MONTHLY.equals(book)
                ? (trade.getMonthlyBaselineSpot() != null ? trade.getMonthlyBaselineSpot() : trade.getBaselineSpot())
                : trade.getBaselineSpot();
        return baseline != null ? baseline : fallback;
    }

    /**
     * Banks the closed segment's points into the book's OWN chain and re-bases that chain's
     * baseline to the roll spot. The other book's chain is untouched — its legs did not move.
     */
    private static void bankSegment(Position trade, String book, double segment, double rolloverPrice) {
        if (LONG_MONTHLY.equals(book)) {
            double banked = trade.getMonthlyBankedPoints() != null ? trade.getMonthlyBankedPoints() : 0.0;
            trade.setMonthlyBankedPoints(Math.round((banked + segment) * 100.0) / 100.0);
            trade.setMonthlyBaselineSpot(rolloverPrice);
        } else {
            double banked = trade.getBankedPoints() != null ? trade.getBankedPoints() : 0.0;
            trade.setBankedPoints(Math.round((banked + segment) * 100.0) / 100.0);
            trade.setBaselineSpot(rolloverPrice);
        }
    }
}
