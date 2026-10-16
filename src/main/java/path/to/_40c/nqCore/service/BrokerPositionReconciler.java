package path.to._40c.nqCore.service;

import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.NFO;
import static path.to._40c.nqCore.util.Constants.PENDING_CLOSE;
import static path.to._40c.nqCore.util.Constants.PENDING_OPEN;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.gateway.KiteAuthChangedEvent;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.repo.PositionRepository;

/**
 * Independent check that the broker's NET NIFTY positions equal what the app believes it holds.
 * Every execution-path fix closes a gap that is already known; this is the catch-all for the
 * one that is not. It detects, logs and reports — it never trades.
 *
 * Motivation: on 2026-10-07 a LIMIT whose cancel Kite had rejected filled minutes after the
 * MARKET top-up, leaving 65 NIFTY26OCT22650PE at the broker that no row in the app tracked. The
 * only evidence was the broker's own position page, which nothing in the app ever consulted, so
 * the orphan sat for seven sessions. This check would have flagged it on the next 5-minute tick.
 *
 * Expected book: the signed quantity of every LIVE leg (BUY +qty, SELL −qty) per instrument.
 * Actual book: Kite /portfolio/positions "net", NFO NIFTY index instruments only — the app's
 * whole universe (NIFTYNXT is a different index and excluded). Instruments carrying a
 * PENDING_OPEN / PENDING_CLOSE leg are not counted — their broker state is legitimately unknown
 * until the reconciler settles them.
 *
 * Noise control: an order mid-flight (placed at the broker, not yet saved) is a transient
 * mismatch, so a difference is WARN on first sighting and ERROR only once it survives
 * CONFIRMED_AFTER_CHECKS consecutive checks. Runs every 5 minutes through the session, on each
 * saved Kite auth (the 08:49 cycle gives a pre-open verdict), and on demand via
 * GET /api/broker-positions/reconcile.
 */
@Service
@Slf4j
public class BrokerPositionReconciler {

    /** Consecutive checks a mismatch must survive before it escalates from WARN to ERROR. */
    static final int CONFIRMED_AFTER_CHECKS = 2;

    private final KiteGateway kiteGateway;
    private final PositionRepository positionRepository;
    private final Map<String, Integer> consecutive = new ConcurrentHashMap<>();
    private volatile List<Mismatch> lastMismatches = List.of();

    @Value("${broker.reconcile.enabled:true}")
    private boolean enabled;

    public BrokerPositionReconciler(KiteGateway kiteGateway, PositionRepository positionRepository) {
        this.kiteGateway = kiteGateway;
        this.positionRepository = positionRepository;
    }

    /**
     * One instrument whose broker net quantity differs from the app's LIVE legs. A positive delta
     * is quantity held at the broker that the app does not track (the 2026-10-07 shape); a
     * negative delta means the app believes it holds more than the broker does.
     */
    public record Mismatch(String instrument, int brokerQty, int appQty, int consecutiveChecks) {
        public int delta() {
            return brokerQty - appQty;
        }
    }

    /** Session cadence: every 5 minutes 09:00–15:55 IST on weekdays (a holiday is a harmless no-op). */
    @Scheduled(cron = "${broker.reconcile.cron:0 */5 9-15 * * MON-FRI}", zone = ZONE_ID)
    public void reconcileTick() {
        if (enabled) reconcile();
    }

    /** A fresh token is the first moment of the day the broker book is readable — check it before any signal can trade. */
    @EventListener(KiteAuthChangedEvent.class)
    public void onAuthChanged(KiteAuthChangedEvent event) {
        if (enabled) reconcile();
    }

    /** The mismatches found by the most recent check (empty when in sync or never run). */
    public List<Mismatch> lastMismatches() {
        return lastMismatches;
    }

    /**
     * Compares the broker's net NIFTY positions with the app's LIVE legs right now and returns
     * the mismatches. An unreadable broker book (no auth row for today, Kite error) is never
     * read as "flat": the previous report is kept and returned.
     */
    public synchronized List<Mismatch> reconcile() {
        List<com.zerodhatech.models.Position> broker = kiteGateway.getNetPositions();
        if (broker == null) {
            log.warn("Broker position check skipped — net positions unreadable (no auth row for today, or Kite error)");
            return lastMismatches;
        }
        Map<String, Integer> brokerQty = new TreeMap<>();
        for (com.zerodhatech.models.Position p : broker) {
            if (p == null || p.tradingSymbol == null || !isNiftyInstrument(p.tradingSymbol)) continue;
            if (p.exchange != null && !NFO.equals(p.exchange)) continue;
            brokerQty.merge(p.tradingSymbol, p.netQuantity, Integer::sum);
        }
        Map<String, Integer> appQty = new TreeMap<>();
        for (Position p : positionRepository.findByLegStatus(LIVE)) {
            for (WeeklyLeg l : p.getLegs()) {
                if (!LIVE.equals(l.getStatus()) || l.getQuantity() == null || l.getInstrument() == null) continue;
                int signed = BUY.equals(l.getSide()) ? l.getQuantity() : -l.getQuantity();
                appQty.merge(l.getInstrument(), signed, Integer::sum);
            }
        }
        Set<String> unsettled = new TreeSet<>();
        for (String pending : List.of(PENDING_OPEN, PENDING_CLOSE)) {
            for (Position p : positionRepository.findByLegStatus(pending)) {
                for (WeeklyLeg l : p.getLegs()) {
                    if (pending.equals(l.getStatus()) && l.getInstrument() != null) unsettled.add(l.getInstrument());
                }
            }
        }

        Set<String> instruments = new TreeSet<>(brokerQty.keySet());
        instruments.addAll(appQty.keySet());
        List<Mismatch> found = new ArrayList<>();
        for (String ins : instruments) {
            int b = brokerQty.getOrDefault(ins, 0);
            int a = appQty.getOrDefault(ins, 0);
            if (b == a) {
                if (consecutive.remove(ins) != null) {
                    log.info("BROKER POSITION MISMATCH cleared: {} broker={} app={}", ins, b, a);
                }
                continue;
            }
            if (unsettled.contains(ins)) {
                consecutive.remove(ins);
                log.info("Broker position check: {} broker={} app={} differs while a PENDING order is unsettled — not counted",
                        ins, b, a);
                continue;
            }
            int seen = consecutive.merge(ins, 1, Integer::sum);
            found.add(new Mismatch(ins, b, a, seen));
            String shape = b - a > 0 ? "UNTRACKED at the broker" : "app believes it holds more than the broker does";
            if (seen >= CONFIRMED_AFTER_CHECKS) {
                log.error("BROKER POSITION MISMATCH: {} broker={} app={} delta={} ({}) — persisted {} consecutive checks; "
                        + "manual review required", ins, b, a, b - a, shape, seen);
            } else {
                log.warn("Broker position check: {} broker={} app={} delta={} ({}) — first sighting, re-checking next pass",
                        ins, b, a, b - a, shape);
            }
        }
        consecutive.keySet().retainAll(instruments);
        if (found.isEmpty()) {
            log.debug("Broker position check: {} broker instrument(s) match the app's LIVE legs", brokerQty.size());
        }
        lastMismatches = List.copyOf(found);
        return lastMismatches;
    }

    /** NIFTY index instruments only — the app's entire universe; NIFTYNXT is a different index. */
    static boolean isNiftyInstrument(String symbol) {
        return symbol.startsWith("NIFTY") && !symbol.startsWith("NIFTYNXT");
    }
}
