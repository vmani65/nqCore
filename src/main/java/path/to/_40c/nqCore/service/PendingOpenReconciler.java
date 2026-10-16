package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.FAILED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LOT_SIZE;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_OPEN;
import static path.to._40c.nqCore.util.Constants.UNKNOWN;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.LegScope;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.CloseOrderState;

/**
 * Open-side twin of PendingCloseReconciler (trade-73 class): an ENTRY order that finished
 * its confirm budget in a non-terminal broker state leaves the leg PENDING_OPEN with the
 * orderId retained. Under the old handling the leg was terminally marked FAILED — a late
 * fill then became an untracked broker position, and with a 1-leg book (LONG_MONTHLY)
 * that is the ENTIRE position.
 *
 * Two entry points with different urgency, mirroring the close reconciler:
 * - the 30s scheduled tick reconciles passively: a working entry order is left to fill
 *   (it is a resting entry near the signal price); only a terminal outcome is acted on.
 * - resolveBeforeSignal() reconciles aggressively before any signal-driven close/open:
 *   a still-working entry order is cancelled and settled NOW, so the next signal never
 *   stacks orders on top of an unconfirmed entry working the same strike. When the cancel
 *   cannot be confirmed (2026-10-07: Kite rejected it and the order stayed OPEN) the leg
 *   STAYS pending — the open guard then blocks this book's entries until it settles.
 *
 * Outcomes per pending leg: filled in full → leg LIVE at intended qty; terminal with a
 * partial fill → leg LIVE trimmed to the filled qty (real position at reduced size);
 * terminal with no fills → leg FAILED (never traded — fill prices stay null so
 * neverTraded semantics hold). Position settles LIVE / PARTIAL (orphan machinery
 * flattens on the next signal) / FAILED, and post-open calc runs once real legs exist.
 */
@Service
@Slf4j
public class PendingOpenReconciler {

    /** Passive ticks tolerated with an UNKNOWN order state before the leg is treated as never opened. */
    private static final int UNKNOWN_TICKS_BEFORE_FAILED = 10;

    private final PositionRepository positionRepository;
    private final PositionUtil positionUtil;
    private final PostTradeService postTradeService;
    private final Map<Long, Integer> unknownTicks = new ConcurrentHashMap<>();

    public PendingOpenReconciler(PositionRepository positionRepository, PositionUtil positionUtil,
                                 PostTradeService postTradeService) {
        this.positionRepository = positionRepository;
        this.positionUtil = positionUtil;
        this.postTradeService = postTradeService;
    }

    /** Passive pass: settle pendings whose entry orders have since gone terminal; leave working orders alone. */
    @Scheduled(fixedDelay = 30_000)
    public void reconcileTick() {
        reconcileAll(false);
    }

    /**
     * Aggressive pass for signal handling: every pending entry order is settled now —
     * cancelled if still working — so the next signal starts from a known broker state.
     */
    public void resolveBeforeSignal() {
        reconcileAll(true);
    }

    private synchronized void reconcileAll(boolean settle) {
        List<Position> pendings;
        try {
            pendings = positionRepository.findByLegStatus(PENDING_OPEN);
        } catch (Exception e) {
            log.error("PENDING_OPEN lookup failed — will retry next pass", e);
            return;
        }
        for (Position p : pendings) {
            try {
                reconcilePosition(p, settle);
            } catch (Exception e) {
                log.error("Reconcile failed for PENDING_OPEN trade id={} — will retry next pass", p.getId(), e);
            }
        }
    }

    private void reconcilePosition(Position p, boolean settle) {
        for (WeeklyLeg w : p.getLegs()) {
            if (PENDING_OPEN.equals(w.getStatus())) {
                resolveLeg(p, w, settle);
            }
        }
        finalizePosition(p);
    }

    private void resolveLeg(Position p, WeeklyLeg w, boolean settle) {
        String orderIds = w.getOpenOrderId();
        int qty = w.getQuantity() != null ? w.getQuantity() : 0;
        if (orderIds == null || orderIds.isBlank() || qty <= 0) {
            log.error("PENDING_OPEN leg id={} ({}) has no open orderId/qty — treating as never opened", w.getId(), w.getInstrument());
            markNeverOpened(w);
            return;
        }
        CloseOrderState s = positionUtil.readCloseOrderState(orderIds);
        if (s.tradedQty() >= qty) {
            markLegLive(p, w, s, qty);
            return;
        }
        if (PositionUtil.isTerminalStatus(s.lastStatus())) {
            settleTerminal(p, w, s, qty);
            return;
        }
        if (!settle) {
            trackStillWorking(p, w, s, qty);
            return;
        }
        log.warn("PENDING_OPEN trade id={} leg {} — entry order {} still {} at signal time; cancelling before the next trade",
                p.getId(), w.getInstrument(), orderIds, s.lastStatus());
        CloseOrderState settled = positionUtil.cancelAndSettleCloseOrders(orderIds);
        if (settled.tradedQty() >= qty) {
            markLegLive(p, w, settled, qty);
        } else if (PositionUtil.isTerminalStatus(settled.lastStatus())) {
            settleTerminal(p, w, settled, qty);
        } else {
            log.error("PENDING_OPEN trade id={} leg {} — entry order {} still {} ({}/{} filled) after every cancel attempt; "
                    + "leg STAYS PENDING_OPEN (settling it as failed would let a late fill become an untracked broker "
                    + "position — the 2026-10-07 shape); entries for this book stay blocked until it settles",
                    p.getId(), w.getInstrument(), orderIds, settled.lastStatus(), settled.tradedQty(), qty);
        }
    }

    /** Same UNKNOWN-tick budget as the close reconciler: a pending can't sit silently forever. */
    private void trackStillWorking(Position p, WeeklyLeg w, CloseOrderState s, int qty) {
        if (!UNKNOWN.equals(s.lastStatus())) {
            unknownTicks.remove(w.getId());
            log.info("PENDING_OPEN trade id={} leg {} — entry order {} still {} ({}/{} filled), waiting",
                    p.getId(), w.getInstrument(), w.getOpenOrderId(), s.lastStatus(), s.tradedQty(), qty);
            return;
        }
        int ticks = unknownTicks.merge(w.getId(), 1, Integer::sum);
        if (ticks >= UNKNOWN_TICKS_BEFORE_FAILED) {
            log.error("PENDING_OPEN trade id={} leg {} — entry order {} UNKNOWN for {} consecutive checks; treating as never opened",
                    p.getId(), w.getInstrument(), w.getOpenOrderId(), ticks);
            settleTerminal(p, w, s, qty);
        } else {
            log.warn("PENDING_OPEN trade id={} leg {} — entry order {} state UNKNOWN (check {}/{})",
                    p.getId(), w.getInstrument(), w.getOpenOrderId(), ticks, UNKNOWN_TICKS_BEFORE_FAILED);
        }
    }

    private void markLegLive(Position p, WeeklyLeg w, CloseOrderState s, int qty) {
        unknownTicks.remove(w.getId());
        w.setStatus(LIVE);
        log.info("PENDING_OPEN resolved: trade id={} leg {} entry order {} filled {} @ {} — leg LIVE",
                p.getId(), w.getInstrument(), w.getOpenOrderId(), s.tradedQty(), s.vwap());
    }

    /**
     * The entry order settled terminal short of full. A partial fill is a real,
     * correctly-directed position — the leg goes LIVE trimmed to the filled quantity so
     * every later close/flatten trades exactly what is held. No fills → the leg FAILED
     * with fill prices untouched (null), preserving neverTraded semantics for P&L.
     */
    private void settleTerminal(Position p, WeeklyLeg w, CloseOrderState s, int qty) {
        unknownTicks.remove(w.getId());
        if (s.tradedQty() > 0 && s.tradedQty() < qty) {
            log.error("PENDING_OPEN trade id={} leg {} — entry order {} settled PARTIAL {}/{} @ {}; leg LIVE at reduced size",
                    p.getId(), w.getInstrument(), w.getOpenOrderId(), s.tradedQty(), qty, s.vwap());
            w.setQuantity(s.tradedQty());
            w.setLots(s.tradedQty() / LOT_SIZE);
            w.setStatus(LIVE);
        } else {
            log.error("PENDING_OPEN trade id={} leg {} — entry order {} settled {} with no fills; leg FAILED (never opened)",
                    p.getId(), w.getInstrument(), w.getOpenOrderId(), s.lastStatus());
            markNeverOpened(w);
        }
    }

    private void markNeverOpened(WeeklyLeg w) {
        unknownTicks.remove(w.getId());
        w.setStatus(FAILED);
    }

    /**
     * Settles the position from its legs' now-known states: legs still pending → stay
     * for the next pass. Otherwise the roll-up over ALL legs decides — per-book orphan
     * shape → PARTIAL (the orphan machinery flattens on the next signal), any LIVE →
     * LIVE, nothing traded → FAILED. Post-open calc (fill prices, margin, charges, peak
     * margin) runs once the position holds real legs — the step the old terminal-FAILED
     * path skipped.
     */
    private void finalizePosition(Position p) {
        boolean anyPending = p.getLegs().stream().anyMatch(w -> PENDING_OPEN.equals(w.getStatus()));
        if (anyPending) {
            positionRepository.save(p);
            return;
        }
        String rolledUp = LegScope.rollUpStatus(p);
        p.setStatus(rolledUp);
        if (PARTIAL.equals(rolledUp)) {
            log.warn("PENDING_OPEN trade id={} settled PARTIAL — orphan legs will be flattened on the next signal", p.getId());
        } else if (FAILED.equals(rolledUp)) {
            log.error("PENDING_OPEN trade id={} settled FAILED — no entry fills", p.getId());
        }
        Position saved = positionRepository.save(p);
        if (!FAILED.equals(saved.getStatus())) {
            log.info("PENDING_OPEN trade id={} reconciled — position {}, running post-open calc", saved.getId(), saved.getStatus());
            postTradeService.afterOpen(saved);
        }
    }
}
