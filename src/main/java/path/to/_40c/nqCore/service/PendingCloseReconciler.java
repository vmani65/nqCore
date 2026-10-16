package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LOT_SIZE;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_CLOSE;
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
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.LegScope;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.CloseOrderState;

/**
 * Finalizes PENDING_CLOSE positions — closes whose order was still working at the broker when
 * the confirm budget ran out (2026-08-04 trade 73: a protection-converted MARKET exit sat OPEN
 * for 56s, filled fully at the broker, but the position had already been terminally marked
 * FAILED with zero P&L and was never looked at again).
 *
 * Two entry points with different urgency:
 * - the 30s scheduled tick reconciles passively: a close order still working is left to fill
 *   (it is a resting close at a favourable price); only a terminal outcome is acted on.
 * - resolveBeforeSignal() reconciles aggressively and is called by the closing service before
 *   any signal-driven close/open: a still-working close order is cancelled and settled NOW, so
 *   a new entry can never trade against an unconfirmed close working the same strike (the
 *   would-have-been 1300-short on 2026-08-04 had the 09:15 buy-back not lucked into filling
 *   before the 09:33 re-entry).
 *
 * Outcomes per pending leg: order filled → leg CLOSED; order terminal short of full → the
 * remainder is still held at the broker, so the leg reverts to LIVE with its quantity trimmed
 * to that remainder and the position drops to PARTIAL, which the existing orphan machinery
 * flattens on the next signal. When every leg has settled CLOSED the position closes for real
 * and post-close calc (P&L + capital chain) finally runs.
 */
@Service
@Slf4j
public class PendingCloseReconciler {

    /**
     * Passive ticks tolerated with an UNKNOWN order state (empty history + empty tradebook —
     * e.g. a pending carried across midnight whose order id Kite no longer serves) before the
     * leg is treated as never-closed and handed to the orphan machinery. 10 ticks ≈ 5 minutes.
     */
    private static final int UNKNOWN_TICKS_BEFORE_ORPHAN = 10;

    private final PositionRepository positionRepository;
    private final PositionUtil positionUtil;
    private final PostTradeService postTradeService;
    private final ComputeUtil computeUtil;
    private final Map<Long, Integer> unknownTicks = new ConcurrentHashMap<>();

    public PendingCloseReconciler(PositionRepository positionRepository, PositionUtil positionUtil,
                                  PostTradeService postTradeService, ComputeUtil computeUtil) {
        this.positionRepository = positionRepository;
        this.positionUtil = positionUtil;
        this.postTradeService = postTradeService;
        this.computeUtil = computeUtil;
    }

    /** Passive pass: finalize pendings whose orders have since settled; leave working orders alone. */
    @Scheduled(fixedDelay = 30_000)
    public void reconcileTick() {
        reconcileAll(false);
    }

    /**
     * Aggressive pass for signal handling: every pending close order is settled now — cancelled
     * if still working — so signal-driven closes and entries start from a known broker state.
     */
    public void resolveBeforeSignal() {
        reconcileAll(true);
    }

    private synchronized void reconcileAll(boolean flatten) {
        List<Position> pendings;
        try {
            pendings = positionRepository.findByLegStatus(PENDING_CLOSE);
        } catch (Exception e) {
            log.error("PENDING_CLOSE lookup failed — will retry next pass", e);
            return;
        }
        for (Position p : pendings) {
            try {
                reconcilePosition(p, flatten);
            } catch (Exception e) {
                log.error("Reconcile failed for PENDING_CLOSE trade id={} — will retry next pass", p.getId(), e);
            }
        }
    }

    private void reconcilePosition(Position p, boolean flatten) {
        boolean revivedAny = false;
        for (WeeklyLeg w : p.getLegs()) {
            if (PENDING_CLOSE.equals(w.getStatus())) {
                revivedAny |= resolveLeg(p, w, flatten);
            }
        }
        finalizePosition(p, revivedAny);
    }

    /** Resolves one pending leg; returns true when the leg was revived to LIVE as an orphan. */
    private boolean resolveLeg(Position p, WeeklyLeg w, boolean flatten) {
        String orderIds = w.getCloseOrderId();
        int qty = w.getQuantity() != null ? w.getQuantity() : 0;
        if (orderIds == null || orderIds.isBlank() || qty <= 0) {
            log.error("PENDING_CLOSE leg id={} ({}) has no close orderId/qty — treating as never closed", w.getId(), w.getInstrument());
            markOrphan(p, w, new CloseOrderState(0, 0.0, UNKNOWN), qty);
            return true;
        }
        CloseOrderState s = positionUtil.readCloseOrderState(orderIds);
        if (s.tradedQty() >= qty) {
            markLegClosed(p, w, s);
            return false;
        }
        if (PositionUtil.isTerminalStatus(s.lastStatus())) {
            markOrphan(p, w, s, qty);
            return true;
        }
        if (!flatten) {
            trackStillWorking(p, w, s, qty);
            return false;
        }
        log.warn("PENDING_CLOSE trade id={} leg {} — close order {} still {} at signal time; cancelling before re-entry",
                p.getId(), w.getInstrument(), orderIds, s.lastStatus());
        CloseOrderState settled = positionUtil.cancelAndSettleCloseOrders(orderIds);
        if (settled.tradedQty() >= qty) {
            markLegClosed(p, w, settled);
            return false;
        }
        if (PositionUtil.isTerminalStatus(settled.lastStatus())) {
            markOrphan(p, w, settled, qty);
            return true;
        }
        log.error("PENDING_CLOSE trade id={} leg {} — close order {} still {} ({}/{} filled) after every cancel attempt; "
                + "leg STAYS PENDING_CLOSE (reviving it as LIVE would flatten against an exit that may still fill — "
                + "a double-sell past flat); entries for this book stay blocked until it settles",
                p.getId(), w.getInstrument(), orderIds, settled.lastStatus(), settled.tradedQty(), qty);
        return false;
    }

    /**
     * Passive-mode handling of a non-terminal order state. A known working state (OPEN etc.) is
     * left to fill. UNKNOWN — no history and no fills — is tolerated for a bounded number of
     * ticks and then treated as never-closed, so a pending can't sit silently forever when Kite
     * has stopped serving the order id.
     */
    private void trackStillWorking(Position p, WeeklyLeg w, CloseOrderState s, int qty) {
        if (!UNKNOWN.equals(s.lastStatus())) {
            unknownTicks.remove(w.getId());
            log.info("PENDING_CLOSE trade id={} leg {} — close order {} still {} ({}/{} filled), waiting",
                    p.getId(), w.getInstrument(), w.getCloseOrderId(), s.lastStatus(), s.tradedQty(), qty);
            return;
        }
        int ticks = unknownTicks.merge(w.getId(), 1, Integer::sum);
        if (ticks >= UNKNOWN_TICKS_BEFORE_ORPHAN) {
            log.error("PENDING_CLOSE trade id={} leg {} — close order {} UNKNOWN for {} consecutive checks; treating as never closed",
                    p.getId(), w.getInstrument(), w.getCloseOrderId(), ticks);
            markOrphan(p, w, s, qty);
        } else {
            log.warn("PENDING_CLOSE trade id={} leg {} — close order {} state UNKNOWN (check {}/{})",
                    p.getId(), w.getInstrument(), w.getCloseOrderId(), ticks, UNKNOWN_TICKS_BEFORE_ORPHAN);
        }
    }

    private void markLegClosed(Position p, WeeklyLeg w, CloseOrderState s) {
        unknownTicks.remove(w.getId());
        w.setStatus(CLOSED);
        log.info("PENDING_CLOSE resolved: trade id={} leg {} close order {} filled {} @ {} — leg CLOSED",
                p.getId(), w.getInstrument(), w.getCloseOrderId(), s.tradedQty(), s.vwap());
    }

    /**
     * The close order settled short of full: the unfilled remainder is still a real position at
     * the broker. The leg reverts to LIVE sized to that remainder so the orphan-flatten path
     * closes exactly what is held — closing the original quantity after a partial close would
     * flip the book past flat. The already-closed portion's economics are logged for manual
     * P&L reconciliation (per-leg fills for a partially-closed leg aren't modeled).
     */
    private void markOrphan(Position p, WeeklyLeg w, CloseOrderState s, int qty) {
        unknownTicks.remove(w.getId());
        if (s.tradedQty() > 0 && s.tradedQty() < qty) {
            int remaining = qty - s.tradedQty();
            log.error("PENDING_CLOSE trade id={} leg {} — close order {} settled PARTIAL {}/{} @ {}; leg reverts to LIVE "
                            + "with qty trimmed to {} for orphan flatten; closed portion needs manual P&L reconciliation",
                    p.getId(), w.getInstrument(), w.getCloseOrderId(), s.tradedQty(), qty, s.vwap(), remaining);
            w.setQuantity(remaining);
            w.setLots(remaining / LOT_SIZE);
        } else {
            log.error("PENDING_CLOSE trade id={} leg {} — close order {} settled {} with no fills; leg reverts to LIVE for orphan flatten",
                    p.getId(), w.getInstrument(), w.getCloseOrderId(), s.lastStatus());
        }
        w.setStatus(LIVE);
    }

    /**
     * Settles the position from its legs' now-known states. Legs still pending → stay for
     * the next pass. A leg revived to LIVE as an orphan → the row is stamped PARTIAL
     * explicitly (the roll-up alone cannot tell a revived orphan from the other book's
     * healthy live legs on the shared row), and the orphan machinery flattens on the next
     * signal. Otherwise the roll-up decides: terminal → post-close calc (fill prices, P&L,
     * the signal's single capital step) finally runs — the step the old terminal-FAILED
     * path skipped, leaving trade 73 at zero P&L; still LIVE on the other book's legs →
     * enrichment now, accounting deferred until that book closes.
     */
    private void finalizePosition(Position p, boolean revivedAny) {
        boolean anyPending = p.getLegs().stream().anyMatch(w -> PENDING_CLOSE.equals(w.getStatus()));
        if (anyPending) {
            positionRepository.save(p);
            return;
        }
        if (revivedAny) {
            p.setStatus(PARTIAL);
            positionRepository.save(p);
            log.warn("PENDING_CLOSE trade id={} downgraded to PARTIAL — orphan legs will be flattened on the next signal", p.getId());
            return;
        }
        p.setStatus(LegScope.rollUpStatus(p));
        if (LegScope.isTerminal(p) && p.getClosedAt() == null) {
            p.setClosedAt(computeUtil.getDtTimeNow());
        }
        Position saved = positionRepository.save(p);
        log.info("PENDING_CLOSE trade id={} reconciled — position {}, running post-close pass", p.getId(), saved.getStatus());
        postTradeService.afterClose(saved);
    }
}
