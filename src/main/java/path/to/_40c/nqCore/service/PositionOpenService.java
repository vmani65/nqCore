package path.to._40c.nqCore.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

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

import static path.to._40c.nqCore.util.Constants.*;

@Service
@Slf4j
public class PositionOpenService {
    private final PositionRepository positionRepository;
    private final PositionUtil positionUtil;
    private final ComputeUtil computeUtil;

    public PositionOpenService(PositionRepository positionRepository, PositionUtil positionUtil, ComputeUtil computeUtil) {
        this.positionRepository = positionRepository;
        this.positionUtil = positionUtil;
        this.computeUtil = computeUtil;
    }

    /**
     * Instruments + quotes (incl. depth + LTP) pre-fetched by the flip path's async task
     * while close orders execute. One getQuote round-trip covers both LTP-for-intent and
     * bid/ask for the LIMIT walk in placeGraduatedLimit (D₁).
     */
    public record OpenPrep(List<LegOrder> pojos, Map<String, Quote> quotes) {}

    /**
     * Called by the weekly flip path's CompletableFuture concurrently with the weekly close.
     * Builds instruments and fetches quotes for the open leg so both are ready
     * the moment the close completes (~100ms work vs ~500ms close execution).
     */
    public OpenPrep prepareWeeklyOpen(String signalPrice, String type, Position trade) {
        stampForOpen(trade, signalPrice, type);
        List<LegOrder> pojos = computeUtil.buildWeeklyInstrument(signalPrice, trade);
        String[] symbols = pojos.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotes = positionUtil.getQuote(symbols);
        return new OpenPrep(pojos, quotes);
    }

    /**
     * SYNTH_WEEKLY open — weekly templates + weekly symbol, quotes fetched inline. The
     * weekly legs land on the signal's shared row (fan-out passes the same Position
     * instance to both books).
     */
    public Position openWeeklyTrade(String signalPrice, String type, Position trade) {
    	stampForOpen(trade, signalPrice, type);
    	List<LegOrder> legOrder = computeUtil.buildWeeklyInstrument(signalPrice, trade);
    	String[] ltpIns = legOrder.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
	    log.debug("OpenTrade ltpIns is: {}", (Object) ltpIns);
    	Map<String, Quote> quotes = positionUtil.getQuote(ltpIns);
    	return placeAndSave(trade, legOrder, quotes);
    }

    /**
     * LONG_MONTHLY open — single bought leg on the monthly contract, appended to the
     * signal's shared row. No async prep variant: one leg's build+quote is cheap and the
     * monthly flip closes inline. Throws IllegalStateException when the monthly book is
     * unconfigured (fan-out isolates the failure to this book).
     */
    public Position openMonthlyTrade(String signalPrice, String type, Position trade) {
        stampForOpen(trade, signalPrice, type);
        List<LegOrder> legOrder = computeUtil.buildMonthlyInstrument(signalPrice, trade);
        String[] ltpIns = legOrder.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotes = positionUtil.getQuote(ltpIns);
        return placeAndSave(trade, legOrder, quotes);
    }

    /**
     * Optimised weekly flip path — skips buildWeeklyInstrument and getQuote since both
     * were pre-computed by prepareWeeklyOpen while the close orders were executing on
     * Zerodha, against the same shared trade instance.
     */
    public Position openTrade(String signalPrice, String type, Position trade, OpenPrep prep) {
        stampForOpen(trade, signalPrice, type);
        return placeAndSave(trade, prep.pojos(), prep.quotes());
    }

    /**
     * Monthly analogue of prepareWeeklyOpen, used by the interleaved flip
     * (PATIENT_EXECUTION_PLAN.md §4): builds the target monthly leg and fetches its quote
     * BEFORE any close order is placed, so an unconfigured book or dead quote feed aborts
     * the flip while the held position is still intact. Stamps spots/direction on the
     * shared trade exactly like the weekly prep.
     */
    public OpenPrep prepareMonthlyOpen(String signalPrice, String type, Position trade) {
        stampForOpen(trade, signalPrice, type);
        List<LegOrder> pojos = computeUtil.buildMonthlyInstrument(signalPrice, trade);
        String[] symbols = pojos.stream().map(LegOrder::getExchangeSymbol).toArray(String[]::new);
        Map<String, Quote> quotes = positionUtil.getQuote(symbols);
        return new OpenPrep(pojos, quotes);
    }

    /**
     * Persists a position whose entry orders were executed by the caller (the interleaved
     * monthly flip places its own slice orders and hands ONE aggregate ExecResult per leg).
     * Runs the exact same result-recording, materialization and status rules as placeAndSave —
     * trim-to-filled on partials, PENDING_OPEN on possibly-live orders, PARTIAL/FAILED position
     * statuses — so a flip-opened position is indistinguishable from a normally-opened one.
     * Legs without a result entry (keyed by exchange symbol) are treated as never-placed FAILED.
     */
    public Position savePreparedOpen(Position trade, OpenPrep prep, Map<String, ExecResult> resultsByExchangeSymbol) {
        prep.pojos().forEach(w -> {
            ExecResult er = resultsByExchangeSymbol.get(w.getExchangeSymbol());
            if (er != null) {
                recordOpenResult(w, prep.quotes().get(w.getExchangeSymbol()), er);
            } else {
                log.error("savePreparedOpen: no ExecResult for {} — leg will be FAILED", w.getExchangeSymbol());
            }
        });
        return materializeAndSave(trade, prep.pojos(), prep.quotes());
    }

    /**
     * Stamps the signal-level open state on the shared row: entry spot, both books' baseline
     * chains (they start equal and diverge only through recenters/rolls), and direction.
     * Idempotent — the second book's open re-stamps the same values from the same signal.
     */
    private void stampForOpen(Position trade, String signalPrice, String type) {
        trade.setEntrySpot(Double.valueOf(signalPrice));
        trade.setBaselineSpot(Double.valueOf(signalPrice));
        trade.setMonthlyBaselineSpot(Double.valueOf(signalPrice));
        trade.setDirection(CE.equals(type) ? LONG : SHORT);
    }

    private Position placeAndSave(Position trade, List<LegOrder> legOrder, Map<String, Quote> quotes) {
    	if (quotes.isEmpty()) {
    	    log.error("Quote map is empty — aborting trade open for this book's instruments "
    	            + "(auth missing, Kite error, or unknown/expired instruments)");
    	    return saveNeverPlaced(trade, legOrder);
    	}
    	String book = legOrder.isEmpty() ? null : legOrder.get(0).getBook();
    	List<WeeklyLeg> unsettled = book != null ? positionUtil.findUnsettledLegs(book) : List.of();
    	if (unsettled != null && !unsettled.isEmpty()) {
    	    log.error("OPEN BLOCKED for {} — {} leg(s) of this book still have an order unconfirmed at the broker [{}]. "
    	            + "A fresh entry on top of an order that may yet fill is the 2026-10-07 double-buy; the reconciler "
    	            + "must settle them first (nothing placed, this signal's {} legs recorded FAILED)",
    	            book, unsettled.size(), describeUnsettled(unsettled), book);
    	    return saveNeverPlaced(trade, legOrder);
    	}
    	List<CompletableFuture<Void>> futs = legOrder.stream()
    	    .map(w -> CompletableFuture.runAsync(() -> {
    	        log.debug("LegOrder to place order is: {}", w);
    	        int totalQty = w.getLots() * LOT_SIZE;
    	        try {
    	            ExecResult er = positionUtil.placeAggressiveOrder(quotes.get(w.getExchangeSymbol()), w.getInstrument(), w.getSide(), totalQty, ENTRY);
    	            recordOpenResult(w, quotes.get(w.getExchangeSymbol()), er);
    	        } catch (Exception e) {
    	            log.error("Exception placing order for {} ({} qty): {}", w.getInstrument(), totalQty, e.getMessage(), e);
    	        }
    	    }, PositionUtil.LEG_EXEC))
    	    .toList();
    	CompletableFuture.allOf(futs.toArray(new CompletableFuture[0])).join();
    	return materializeAndSave(trade, legOrder, quotes);
    }

    /**
     * Records this book's legs for the signal as FAILED without placing anything — the shape
     * shared by an unreadable quote map and an open blocked by unsettled broker orders. Fill
     * prices and order ids stay null, so neverTraded semantics hold downstream.
     */
    private Position saveNeverPlaced(Position trade, List<LegOrder> legOrder) {
        trade.setLegs(legOrder.stream().map(pojo -> {
            WeeklyLeg b = new WeeklyLeg();
            b.setBook(pojo.getBook());
            b.setInstrument(pojo.getInstrument()); b.setExchangeSymbol(pojo.getExchangeSymbol());
            b.setSide(pojo.getSide()); b.setPosition(pojo.getParentPosition());
            b.setMoneyness(pojo.getMoneyness()); b.setLots(pojo.getLots());
            b.setQuantity(pojo.getLots() * LOT_SIZE); b.setStatus(FAILED);
            return b;
        }).collect(Collectors.toList()));
        trade.setStatus(LegScope.rollUpStatus(trade));
        return positionRepository.save(trade);
    }

    /** "instrument STATUS order=id" per unsettled leg, for the blocked-open / blocked-roll log lines. */
    static String describeUnsettled(List<WeeklyLeg> unsettled) {
        return unsettled.stream()
                .map(l -> l.getInstrument() + " " + l.getStatus() + " order="
                        + (PENDING_OPEN.equals(l.getStatus()) ? l.getOpenOrderId() : l.getCloseOrderId()))
                .collect(Collectors.joining(", "));
    }

    /**
     * Records one leg's execution outcome onto its LegOrder pojo: order ids, filled qty,
     * possibly-still-live flag, spread paid vs the order-time mid, and the fully-filled marker.
     * Extracted from placeAndSave's placement lambda so the interleaved flip and the rollover
     * open (PositionRolloverService) feed their results through the identical bookkeeping.
     */
    static void recordOpenResult(LegOrder w, Quote q, ExecResult er) {
        if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isEmpty()) {
            w.setOpenOrderId(er.aggregateOrderIds());
        }
        w.setOpenFilledQty(er.totalFilled());
        w.setOpenOrderMayBeLive(PositionUtil.closeOrderMayBeLive(er));
        w.setOpenSpreadPaid(PositionUtil.effectiveSpreadPaid(q, w.getSide(), er.weightedAvgFillPrice()));
        if (!er.fullyFilled()) {
            log.error("[ENTRY] {} ({} qty) NOT fully filled: filled={}/{} term={}",
                w.getInstrument(), w.getLots() * LOT_SIZE, er.totalFilled(), er.totalRequested(), er.terminalStatus());
            w.setOpenFullyFilled(false);
        } else {
            w.setOpenFullyFilled(true);
        }
    }

    /**
     * Materializes WeeklyLeg rows from the recorded LegOrder outcomes and resolves per-leg
     * statuses (LIVE trim-to-filled / PENDING_OPEN / FAILED), appends them to the signal's
     * shared row, resolves the row status as the roll-up over ALL legs (the other book's
     * legs included), then saves. A trim-to-filled partial open is stamped PARTIAL
     * explicitly — its legs are all LIVE so the roll-up alone cannot see that this book's
     * open half-failed and the orphan machinery must flatten it before the next entry.
     * Shared by the normal open paths and savePreparedOpen.
     */
    private Position materializeAndSave(Position trade, List<LegOrder> legOrder, Map<String, Quote> quotes) {
    	List<WeeklyLeg> childOrderBook = new ArrayList<>();
    	legOrder.forEach(pojo -> {
    		WeeklyLeg b = new WeeklyLeg();
    		b.setBook(pojo.getBook());
    		b.setInstrument(pojo.getInstrument());
    		b.setExchangeSymbol(pojo.getExchangeSymbol());
    		b.setSide(pojo.getSide());
    		b.setPosition(pojo.getParentPosition());
    		b.setMoneyness(pojo.getMoneyness());
    		b.setOpenOrderId(pojo.getOpenOrderId());
    		b.setOpenSpreadPaid(pojo.getOpenSpreadPaid());
    		PositionUtil.alertIfMonthlySpreadExcessive(pojo.getBook(), pojo.getInstrument(), ENTRY, pojo.getOpenSpreadPaid());
    		int filledQty = pojo.getOpenFilledQty();
    		boolean mayStillFill = Boolean.TRUE.equals(pojo.getOpenOrderMayBeLive())
    				&& !Boolean.TRUE.equals(pojo.getOpenFullyFilled());
    		if (mayStillFill) {
    			b.setLots(pojo.getLots());
    			b.setQuantity(pojo.getLots() * LOT_SIZE);
    			b.setStatus(PENDING_OPEN);
    			log.error("[ENTRY] {} open order {} still working at broker (filled={}/{}) — leg PENDING_OPEN, "
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
    		Quote q = quotes.get(pojo.getExchangeSymbol());
    		if (q != null) {
    			if (BUY.equals(pojo.getSide()))
					b.setBuyIntendedPrice(q.lastPrice);
    			else
					b.setSellIntendedPrice(q.lastPrice);
    		}
    		childOrderBook.add(b);
    	});
    	trade.setLegs(childOrderBook);
    	boolean allFullyFilled = legOrder.stream().allMatch(p -> Boolean.TRUE.equals(p.getOpenFullyFilled()));
    	boolean anyPendingOpen = childOrderBook.stream().anyMatch(l -> PENDING_OPEN.equals(l.getStatus()));
    	boolean anyFilled = legOrder.stream().anyMatch(p -> p.getOpenFilledQty() > 0);
    	if (!allFullyFilled) {
    		if (anyPendingOpen) {
    			log.error("Book open not confirmed - an entry order is still working at the broker; "
    					+ "leg(s) PENDING_OPEN, reconciler will settle from the tradebook");
    		} else if (anyFilled) {
    			log.error("ORPHAN: book opened PARTIALLY - filled legs will be closed on next signal. Legs: {}",
    				childOrderBook.stream()
    					.map(l -> l.getInstrument() + " " + l.getSide() + " " + l.getStatus() + " qty=" + l.getQuantity())
    					.collect(Collectors.joining(", ")));
    		} else {
    			log.error("Book opening operation failed - no legs filled");
    		}
    	}
    	String rolledUp = LegScope.rollUpStatus(trade);
    	if (anyFilled && !allFullyFilled && !anyPendingOpen && LIVE.equals(rolledUp)) {
    		rolledUp = PARTIAL;
    	}
    	trade.setStatus(rolledUp);
    	var liveTrade = positionRepository.save(trade);
    	log.info("Live Position Being Opened: {}", trade);
    	return liveTrade;
    }
}
