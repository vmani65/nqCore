package path.to._40c.nqCore.service;

import static com.zerodhatech.kiteconnect.utils.Constants.ORDER_COMPLETE;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.FAILED;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.LOT_SIZE;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.SELL;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.service.PositionOpenService.OpenPrep;
import path.to._40c.nqCore.util.ExecMode;
import path.to._40c.nqCore.util.LegScope;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Interleaved LONG_MONTHLY flip (PATIENT_EXECUTION_PLAN.md §4): instead of close-everything-
 * then-open, the flip runs in lot-sized slices — patiently close one slice of the held thin
 * deep-ITM option, and only after its fill is CONFIRMED, aggressively open the same qty of the
 * new liquid ATM option. Price drift during the slow flip largely cancels between the unclosed
 * old position and the already-opened new one, which is what makes minutes-scale patience
 * affordable on a flip at all.
 *
 * The invariant: opened qty never exceeds confirmed-closed qty, and lags it by at most one
 * slice. A stalled close slice stops the loop symmetrically (its resting order + PENDING_CLOSE
 * hand the tail to the reconciler; nothing further opens). A failed OPEN slice stops further
 * opens but lets the remaining close slices finish — the flip's primary obligation is exiting
 * the old position, and that degenerate shape equals the legacy close-then-failed-open flow,
 * handled by the existing PENDING_OPEN/PARTIAL machinery.
 *
 * Persistence goes through the same appliers as the normal paths (applyCloseResult /
 * finalizeClose / savePreparedOpen) with ONE aggregate ExecResult per side, comma-joined slice
 * ids included — so LEG_FILL slice telemetry and the reconcilers see the whole picture for free.
 */
@Service
@Slf4j
public class MonthlyFlipService {

    /** Floor on any single slice's patient budget, so late slices are never starved to nothing. */
    private static final long MIN_SLICE_WAIT_MS = 60_000L;

    private final PositionUtil positionUtil;
    private final PositionOpenService openingService;
    private final PositionCloseService closingService;
    private final PendingCloseReconciler pendingCloseReconciler;
    private final PendingOpenReconciler pendingOpenReconciler;

    @Value("${order.execution.monthly-flip-interleave.enabled:false}")
    private boolean interleaveEnabled;
    @Value("${order.execution.monthly-flip-slice-lots:1}")
    private int sliceLots;
    @Value("${order.execution.monthly-flip-window-ms:600000}")
    private long flipWindowMs;

    public MonthlyFlipService(PositionUtil positionUtil, PositionOpenService openingService,
            PositionCloseService closingService, PendingCloseReconciler pendingCloseReconciler,
            PendingOpenReconciler pendingOpenReconciler) {
        this.positionUtil = positionUtil;
        this.openingService = openingService;
        this.closingService = closingService;
        this.pendingCloseReconciler = pendingCloseReconciler;
        this.pendingOpenReconciler = pendingOpenReconciler;
    }

    /** The two positions a flip touches; either may be null (nothing held / nothing opened). */
    public record FlipOutcome(Position closed, Position opened) {}

    /**
     * True when the interleaved flip should replace the legacy monthly close→open: its own flag
     * is on AND patient execution is live right now (enabled, valid config, before the IST
     * cutoff) — an interleave without patience is just a slow legacy flip with extra orders.
     */
    public boolean interleaveAvailable() {
        return interleaveEnabled && sliceLots > 0 && positionUtil.patientModeAvailable();
    }

    /**
     * Runs the interleaved flip against the signal's shared row (newTrade — created by the
     * fan-out, the weekly legs may already sit on it). Returns null when the held book's
     * shape can't be interleaved (more than one LONG_MONTHLY leg — caller falls back to the
     * legacy flip), and FlipOutcome(null,null) when aborted pre-execution with the held
     * position fully intact (dead quotes). Only the held row's LONG_MONTHLY legs are
     * considered — its weekly legs belong to the other book and are untouchable here. The
     * target leg is prepared and quoted BEFORE any close order is placed, so an unconfigured
     * monthly book can never strand a half-closed flip.
     */
    public FlipOutcome flip(String signalPrice, String type, Signal signal, Position newTrade) {
        pendingCloseReconciler.resolveBeforeSignal();
        pendingOpenReconciler.resolveBeforeSignal();
        List<WeeklyLeg> unsettled = positionUtil.findUnsettledLegs(LONG_MONTHLY);
        if (unsettled != null && !unsettled.isEmpty()) {
            log.error("interleaved flip declined — {} LONG_MONTHLY leg(s) still unsettled at the broker after reconcile [{}]; "
                    + "legacy path closes the held legs and the open guard blocks the re-entry until they settle",
                    unsettled.size(), PositionOpenService.describeUnsettled(unsettled));
            return null;
        }
        Position held = positionUtil.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY);
        List<WeeklyLeg> heldMonthlyLegs = held != null ? LegScope.of(held, LONG_MONTHLY) : List.of();
        if (held == null || heldMonthlyLegs.isEmpty()) {
            log.info("interleaved flip: no LIVE LONG_MONTHLY legs — plain open path");
            Position orphanClosed = closingService.closeMonthlyOrphanIfAny(signalPrice, signal);
            Position opened = openingService.openMonthlyTrade(signalPrice, type, newTrade);
            return new FlipOutcome(orphanClosed, opened);
        }
        if (heldMonthlyLegs.size() > 1) {
            log.warn("interleaved flip: trade id={} unexpectedly has {} LONG_MONTHLY legs — legacy flip path",
                    held.getId(), heldMonthlyLegs.size());
            return null;
        }
        WeeklyLeg heldLeg = heldMonthlyLegs.get(0);

        OpenPrep prep = openingService.prepareMonthlyOpen(signalPrice, type, newTrade);
        LegOrder target = prep.pojos().isEmpty() ? null : prep.pojos().get(0);
        Quote targetQuote = target != null ? prep.quotes().get(target.getExchangeSymbol()) : null;
        Quote heldQuote = positionUtil.getQuote(new String[]{heldLeg.getExchangeSymbol()})
                .get(heldLeg.getExchangeSymbol());
        if (target == null || targetQuote == null || heldQuote == null) {
            log.error("interleaved flip ABORTED before any order (target={}, targetQuote={}, heldQuote={}) — "
                    + "held position intact", target != null ? target.getInstrument() : "none",
                    targetQuote != null, heldQuote != null);
            return new FlipOutcome(null, null);
        }

        held.setExitSpot(Math.round(Double.parseDouble(signalPrice) * 100.0) / 100.0);
        String closeTxn = BUY.equals(heldLeg.getSide()) ? SELL : BUY;
        if (BUY.equals(closeTxn)) heldLeg.setBuyIntendedPrice(heldQuote.lastPrice);
        else                      heldLeg.setSellIntendedPrice(heldQuote.lastPrice);

        int heldQty  = heldLeg.getQuantity();
        int sliceQty = sliceLots * LOT_SIZE;
        long flipStart = System.currentTimeMillis();
        List<ExecResult> closeResults = new ArrayList<>();
        List<ExecResult> openResults  = new ArrayList<>();
        int closedQty = 0;
        int openAttempted = 0;
        boolean openHealthy = true;
        int slice = 0;
        while (closedQty < heldQty) {
            int thisQty = Math.min(sliceQty, heldQty - closedQty);
            int slicesLeft = (heldQty - closedQty + sliceQty - 1) / sliceQty;
            long budgetLeft = flipWindowMs - (System.currentTimeMillis() - flipStart);
            long perSlice = Math.max(MIN_SLICE_WAIT_MS, budgetLeft / slicesLeft);
            ExecResult closeEr = positionUtil.placeAggressiveOrder(heldQuote, heldLeg.getInstrument(),
                    closeTxn, thisQty, "EXIT[" + slice + "]", ExecMode.PATIENT, perSlice);
            closeResults.add(closeEr);
            closedQty += closeEr.totalFilled();
            if (!closeEr.fullyFilled()) {
                log.warn("interleaved flip: close slice {} stalled ({}/{} filled, term={}) — stopping "
                        + "symmetrically; the resting order + PENDING_CLOSE machinery own the tail",
                        slice, closeEr.totalFilled(), thisQty, closeEr.terminalStatus());
                break;
            }
            if (openHealthy) {
                ExecResult openEr = positionUtil.placeAggressiveOrder(targetQuote, target.getInstrument(),
                        target.getSide(), thisQty, "ENTRY[" + slice + "]", ExecMode.AGGRESSIVE);
                openResults.add(openEr);
                openAttempted += thisQty;
                if (!openEr.fullyFilled()) {
                    openHealthy = false;
                    log.error("interleaved flip: open slice {} not fully filled ({}/{} term={}) — no further "
                            + "opens; remaining close slices continue (legacy-equivalent exit)",
                            slice, openEr.totalFilled(), thisQty, openEr.terminalStatus());
                }
            }
            slice++;
        }

        ExecResult closeAgg = aggregate(closeResults, heldQty);
        closingService.applyCloseResult(held, heldLeg, heldQuote, closeTxn, closeAgg);
        Position closedSaved = closingService.finalizeClose(held, signal, false);

        Position openedSaved = null;
        if (!openResults.isEmpty()) {
            ExecResult openAgg = aggregate(openResults, openAttempted);
            openedSaved = openingService.savePreparedOpen(newTrade, prep,
                    Map.of(target.getExchangeSymbol(), openAgg));
        } else {
            log.warn("interleaved flip: nothing opened (first close slice stalled) — no new position row");
        }
        log.info("[PERFORMANCE] flip LONG_MONTHLY interleaved | slices={} | closed={}/{} | opened={}/{} | total={}ms",
                closeResults.size(), closedQty, heldQty,
                openResults.stream().mapToInt(ExecResult::totalFilled).sum(), openAttempted,
                System.currentTimeMillis() - flipStart);
        return new FlipOutcome(closedSaved, openedSaved);
    }

    /**
     * Folds per-slice ExecResults into one: comma-joined ids (LEG_FILL and the reconcilers split
     * on exactly this), qty-weighted average, and a least-settled status — any possibly-live
     * slice makes the aggregate non-terminal so the PENDING machinery owns the whole close,
     * mirroring readCloseOrderState's rule for multi-slice orders.
     */
    private static ExecResult aggregate(List<ExecResult> ers, int totalRequested) {
        int filled = 0;
        double weighted = 0.0;
        StringBuilder ids = new StringBuilder();
        String liveStatus = null;
        String lastTerminal = null;
        for (ExecResult er : ers) {
            filled += er.totalFilled();
            weighted += er.totalFilled() * er.weightedAvgFillPrice();
            if (er.aggregateOrderIds() != null && !er.aggregateOrderIds().isBlank()) {
                if (ids.length() > 0) ids.append(", ");
                ids.append(er.aggregateOrderIds());
            }
            if (PositionUtil.closeOrderMayBeLive(er)) liveStatus = er.terminalStatus();
            else lastTerminal = er.terminalStatus();
        }
        double avg = filled > 0 ? Math.round(weighted / filled * 100.0) / 100.0 : 0.0;
        boolean full = totalRequested > 0 && filled >= totalRequested;
        String status;
        if (full) status = ORDER_COMPLETE;
        else if (liveStatus != null) status = liveStatus;
        else if (filled > 0) status = PARTIAL;
        else status = lastTerminal != null ? lastTerminal : FAILED;
        return new ExecResult(ids.toString(), filled, totalRequested, avg, full, status);
    }
}
