package path.to._40c.nqCore.util;

import static com.zerodhatech.kiteconnect.utils.Constants.ORDER_CANCELLED;
import static com.zerodhatech.kiteconnect.utils.Constants.ORDER_COMPLETE;
import static com.zerodhatech.kiteconnect.utils.Constants.ORDER_REJECTED;

import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.DATE_FORMAT;
import static path.to._40c.nqCore.util.Constants.ENTRY;
import static path.to._40c.nqCore.util.Constants.EXIT;
import static path.to._40c.nqCore.util.Constants.FAILED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LIVE_ORDER_BOOKS;
import static path.to._40c.nqCore.util.Constants.MAX_SIZE_PER_ORDER;
import static path.to._40c.nqCore.util.Constants.NFO;
import static path.to._40c.nqCore.util.Constants.NIFTY;
import static path.to._40c.nqCore.util.Constants.NIFTY_OPT_TICK;
import static path.to._40c.nqCore.util.Constants.OVERFILL;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_CLOSE;
import static path.to._40c.nqCore.util.Constants.PENDING_OPEN;
import static path.to._40c.nqCore.util.Constants.PLACE_FAILED;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;
import static path.to._40c.nqCore.util.Constants.UNKNOWN;
import static path.to._40c.nqCore.util.Constants.ZONE_ID;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.hibernate.Session;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.ContractNote;
import com.zerodhatech.models.ContractNoteParams;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import com.zerodhatech.models.Quote;

import jakarta.persistence.EntityManager;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.entity.LegFill;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.gateway.KiteOrderStream;
import path.to._40c.nqCore.repo.PositionRepository;

@Service
@Slf4j
public class PositionUtil {
    /**
     * Shared virtual-thread executor for parallel leg operations across services.
     * Used by PositionOpenService, PositionCloseService, PositionRolloverService,
     * ProfitRecenterService — replaces FJP common pool for blocking I/O fan-out.
     * Static lifetime; virtual-thread executors hold negligible resources.
     */
    public static final java.util.concurrent.ExecutorService LEG_EXEC =
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** Split regex for the comma-separated values this class round-trips (aggregate order ids, patient config). */
    private static final String CSV_SPLIT = "\\s*,\\s*";

    private final KiteGateway kiteGateway;
    private final KiteOrderStream orderStream;
    private final PositionRepository positionRepository;
    private final EntityManager entityManager;

    /**
     * When true, placeAggressiveOrder uses graduated LIMIT walk (mid → walk → MARKET fallback)
     * instead of pure MARKET. Default false; production sets true via application.properties.
     */
    @org.springframework.beans.factory.annotation.Value("${order.execution.use-limit-walk:false}")
    private boolean useLimitWalk;

    /**
     * PATIENT execution config (PATIENT_EXECUTION_PLAN.md §3 / §5). Raw strings are parsed and
     * validated once in initPatientConfig; invalid config logs an error and disables patient mode
     * rather than failing startup. All values default to the plan's §5 table; patient mode itself
     * defaults OFF.
     */
    @org.springframework.beans.factory.annotation.Value("${order.execution.patient.enabled:false}")
    private boolean patientEnabled;
    @org.springframework.beans.factory.annotation.Value("${order.execution.patient.step-delays-ms:20000,60000,120000,240000,420000,600000}")
    private String patientStepDelaysRaw;
    @org.springframework.beans.factory.annotation.Value("${order.execution.patient.concession-fractions:0.0,0.2,0.4,0.6,0.8,1.0}")
    private String patientConcessionFractionsRaw;
    @org.springframework.beans.factory.annotation.Value("${order.execution.patient.max-concession-pts:2.0}")
    private double patientMaxConcessionPts;
    @org.springframework.beans.factory.annotation.Value("${order.execution.patient.max-concession-pct:0.5}")
    private double patientMaxConcessionPct;
    @org.springframework.beans.factory.annotation.Value("${order.execution.patient.sane-spread-pct:2.0}")
    private double patientSaneSpreadPct;
    @org.springframework.beans.factory.annotation.Value("${order.execution.patient.cutoff:15:10}")
    private String patientCutoffRaw;
    @org.springframework.beans.factory.annotation.Value("${order.execution.patient.deadline-action:REST}")
    private String patientDeadlineAction;

    private long[]    patientStepDelaysMs;
    private double[]  patientConcessionFractions;
    private LocalTime patientCutoff;
    private boolean   patientConfigValid;

    public PositionUtil(KiteGateway kiteGateway, KiteOrderStream orderStream,
                        PositionRepository positionRepository, EntityManager entityManager) {
        this.kiteGateway = kiteGateway;
        this.orderStream = orderStream;
        this.positionRepository = positionRepository;
        this.entityManager = entityManager;
    }

    /**
     * Parses and validates the patient-mode config strings. Delays must be positive and strictly
     * increasing, fractions the same length within [0,1] and non-decreasing, cutoff a valid HH:mm.
     * Any violation disables patient mode (logged loudly) instead of throwing — a bad tuning value
     * must never take order placement down with it. Package-visible so tests can invoke it after
     * setting the raw fields by reflection (no Spring context in the JUnit suite).
     */
    @jakarta.annotation.PostConstruct
    void initPatientConfig() {
        try {
            long[] delays = Arrays.stream(patientStepDelaysRaw.split(CSV_SPLIT))
                    .mapToLong(Long::parseLong).toArray();
            double[] fracs = Arrays.stream(patientConcessionFractionsRaw.split(CSV_SPLIT))
                    .mapToDouble(Double::parseDouble).toArray();
            LocalTime cutoff = LocalTime.parse(patientCutoffRaw);
            boolean ok = delays.length > 0 && delays.length == fracs.length;
            for (int i = 0; ok && i < delays.length; i++) {
                if (delays[i] <= 0 || (i > 0 && delays[i] <= delays[i - 1])) ok = false;
                if (fracs[i] < 0.0 || fracs[i] > 1.0 || (i > 0 && fracs[i] < fracs[i - 1])) ok = false;
            }
            if (!ok) {
                throw new IllegalArgumentException(
                        "step-delays-ms and concession-fractions must be same-length, delays strictly increasing, fractions in [0,1] non-decreasing");
            }
            patientStepDelaysMs = delays;
            patientConcessionFractions = fracs;
            patientCutoff = cutoff;
            patientConfigValid = true;
        } catch (Exception e) {
            patientConfigValid = false;
            log.error("PATIENT execution config invalid — patient mode DISABLED: {}", e.getMessage());
        }
    }

    /**
     * True when a PATIENT request will actually be honored: flag on, config valid, and the IST
     * cutoff not yet passed. PositionCloseService consults this for routing; placeAggressiveOrder
     * re-checks it so a stale caller decision can never start a patient loop after cutoff.
     */
    public boolean patientModeAvailable() {
        return patientEnabled && patientConfigValid && !pastPatientCutoff();
    }

    /** True once IST wall-clock reaches the patient cutoff (default 15:10) — see plan §3.6. */
    private boolean pastPatientCutoff() {
        return patientCutoff == null || !LocalTime.now(ZoneId.of(ZONE_ID)).isBefore(patientCutoff);
    }

    /** Capture fill prices for legs that don't already have them. Idempotent. */
    public void setTradeExecutedPrices(Position t) {
        if (t != null) {
            t.getLegs().forEach(w -> {
                if (isPriceAlreadyCaptured(w)) return;
                boolean isLive = LIVE.equals(w.getStatus());
                String orderId = isLive ? w.getOpenOrderId() : w.getCloseOrderId();
                if (orderId == null || orderId.isBlank()) return;
                MDC.put(MDC_LEG_KEY, (isLive ? ENTRY : EXIT) + ":" + w.getInstrument() + " | ");
                try {
                    log.debug("Fetching executed prices for trade id={} orderId={}", t.getId(), orderId);
                    List<com.zerodhatech.models.Trade> trades = fetchWithRetry(orderId, "executed prices");
                    if (trades != null && !trades.isEmpty()) {
                        double avgPrice = weightedAvgFillPrice(trades);
                        log.debug("Average Price (weighted across {} fills): {}", trades.size(), avgPrice);
                        if (BUY.equals(w.getSide())) {
                            if (LIVE.equals(w.getStatus()))   w.setBuyFillPrice(avgPrice);
                            if (CLOSED.equals(w.getStatus())) w.setSellFillPrice(avgPrice);
                        }
                        if (SELL.equals(w.getSide())) {
                            if (LIVE.equals(w.getStatus()))   w.setSellFillPrice(avgPrice);
                            if (CLOSED.equals(w.getStatus())) w.setBuyFillPrice(avgPrice);
                        }
                    }
                } finally {
                    MDC.remove(MDC_LEG_KEY);
                }
            });
        }
    }

    /** Σ(qty × price) / Σ(qty) across all fills. Handles multi-slice auto-orders correctly. */
    public static double weightedAvgFillPrice(List<com.zerodhatech.models.Trade> trades) {
        if (trades == null || trades.isEmpty()) return 0.0;
        double totalQty = 0.0;
        double totalValue = 0.0;
        for (com.zerodhatech.models.Trade tr : trades) {
            if (tr == null || tr.averagePrice == null || tr.quantity == null) continue;
            try {
                double q = Double.parseDouble(tr.quantity);
                double p = Double.parseDouble(tr.averagePrice);
                totalQty   += q;
                totalValue += q * p;
            } catch (NumberFormatException e) {
                log.warn("weightedAvgFillPrice: malformed trade record (qty={} price={}) — skipping",
                        tr.quantity, tr.averagePrice);
            }
        }
        return totalQty > 0.0 ? Math.round((totalValue / totalQty) * 100.0) / 100.0 : 0.0;
    }

    /** True if the price side relevant to this leg's status is already populated. */
    private boolean isPriceAlreadyCaptured(WeeklyLeg w) {
        boolean isBuy  = BUY.equals(w.getSide());
        boolean isLive = LIVE.equals(w.getStatus());
        Double target = isLive
                ? (isBuy ? w.getBuyFillPrice() : w.getSellFillPrice())
                : (isBuy ? w.getSellFillPrice()   : w.getBuyFillPrice());
        return target != null;
    }

    /**
     * Accumulates (+=) fill prices onto the given legs after a rollover close/open. Takes an
     * explicit leg list — never the whole position — because on a shared row the += would
     * double-count prices onto the other book's already-priced legs.
     */
    public void setTradeExecPricesForRollOver(List<WeeklyLeg> legs, boolean rollOverClose, boolean rollOverOpen) {
        if (legs != null) {
            legs.stream()
                .filter(w -> rollOverClose ? CLOSED.equals(w.getStatus()) : LIVE.equals(w.getStatus()))
                .forEach(w -> {
                    String orderId = rollOverOpen ? w.getOpenOrderId() : w.getCloseOrderId();
                    log.debug("Fetching executed prices for rollover leg {} orderId={}", w.getInstrument(), orderId);
                    List<com.zerodhatech.models.Trade> trades = fetchWithRetry(orderId, "rollover executed prices");
                    if (trades != null && !trades.isEmpty()) {
                        double avgPrice = weightedAvgFillPrice(trades);
                        log.debug("Average Price (weighted across {} fills): {}", trades.size(), avgPrice);
                        if (BUY.equals(w.getSide())) {
                            if (rollOverOpen) {
                                w.setBuyFillPrice(Math.round(((w.getBuyFillPrice() != null ? w.getBuyFillPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                            if (rollOverClose) {
                                w.setSellFillPrice(Math.round(((w.getSellFillPrice() != null ? w.getSellFillPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                        }
                        if (SELL.equals(w.getSide())) {
                            if (rollOverOpen) {
                                w.setSellFillPrice(Math.round(((w.getSellFillPrice() != null ? w.getSellFillPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                            if (rollOverClose) {
                                w.setBuyFillPrice(Math.round(((w.getBuyFillPrice() != null ? w.getBuyFillPrice() : 0.0) + avgPrice) * 100.0) / 100.0);
                            }
                        }
                    }
                });
        }
    }

    /**
     * Set margin + open/close brokerage estimate for LIVE legs via two parallel /margins/orders calls
     * (real-txn and opposite-txn baskets). Uniform-direction baskets are avoided — Kite collapses them
     * as straddles and redistributes margin. Runs on ForkJoinPool to avoid postTradeExecutor deadlock.
     */
    public void calcMarginAndBrokerage(Position trade) {
        if (trade == null) return;

        List<MarginCalculationParams> realParams     = new ArrayList<>();
        List<MarginCalculationParams> oppositeParams = new ArrayList<>();
        trade.getLegs().stream()
                .filter(c -> LIVE.equals(c.getStatus()))
                .forEach(c -> {
                    realParams.add(buildParam(c, c.getSide()));
                    oppositeParams.add(buildParam(c, opposite(c.getSide())));
                });
        if (realParams.isEmpty()) return;

        var realFuture     = CompletableFuture.supplyAsync(() -> getMarginCalculation(realParams), LEG_EXEC);
        var oppositeFuture = CompletableFuture.supplyAsync(() -> getMarginCalculation(oppositeParams), LEG_EXEC);
        List<MarginCalculationData> realMargins;
        List<MarginCalculationData> oppositeMargins;
        try {
            realMargins     = realFuture.get();
            oppositeMargins = oppositeFuture.get();
        } catch (Exception e) {
            log.error("Exception during parallel margin calculation", e);
            return;
        }

        realMargins.forEach(m -> trade.getLegs().stream()
                .filter(w -> m.tradingSymbol.equals(w.getInstrument()) && LIVE.equals(w.getStatus()))
                .findFirst()
                .ifPresent(w -> {
                    w.setMarginRequired(ComputeUtil.rnd(m.total));
                    w.setOpenCharges(ComputeUtil.rnd(totalChargesForSliceCount(m.charges, sliceCount(w, true))));
                }));
        oppositeMargins.forEach(m -> trade.getLegs().stream()
                .filter(w -> m.tradingSymbol.equals(w.getInstrument()) && LIVE.equals(w.getStatus()))
                .findFirst()
                .ifPresent(w -> w.setCloseCharges(ComputeUtil.rnd(totalChargesForSliceCount(m.charges, sliceCount(w, false))))));
    }

    private MarginCalculationParams buildParam(WeeklyLeg c, String side) {
        var p = initCalcParam(c.getQuantity());
        p.tradingSymbol   = c.getInstrument();
        p.transactionType = side;
        return p;
    }

    private static String opposite(String txn) {
        return BUY.equals(txn) ? SELL : BUY;
    }

    /** Slice count from stored comma-separated orderIds, or estimated from qty if not yet placed. */
    static int sliceCount(WeeklyLeg w, boolean openSide) {
        String orderId = openSide ? w.getOpenOrderId() : w.getCloseOrderId();
        if (orderId != null && !orderId.isBlank()) {
            return orderId.split(CSV_SPLIT).length;
        }
        Integer qty = w.getQuantity();
        if (qty == null || qty <= MAX_SIZE_PER_ORDER) return 1;
        return (int) Math.ceil((double) qty / MAX_SIZE_PER_ORDER);
    }

    /**
     * Total charges adjusted for auto-slicing: brokerage scales with order count (Zerodha charges
     * per-order), turnover-based charges (STT/exch/SEBI) don't, GST is recomputed.
     */
    static double totalChargesForSliceCount(MarginCalculationData.Charges c, int sliceCount) {
        if (c == null) return 0.0;
        if (sliceCount <= 1) return c.total;
        double brokerage = c.brokerage * sliceCount;
        double exch      = c.exchangeTurnoverCharge;
        double sebi      = c.SEBITurnoverCharge;
        double gst       = (brokerage + exch + sebi) * 0.18;
        return brokerage + c.transactionTax + exch + sebi + c.stampDuty + gst;
    }

    /**
     * Overwrite brokerage estimates with EOD-exact charges from /charges/orders.
     * LIVE leg → openCharges, CLOSED leg → closeCharges. Idempotent.
     */
    public void applyActualCharges(Position trade) {
        if (trade == null) return;
        List<WeeklyLeg>   legs   = new ArrayList<>();
        List<ContractNoteParams> params = new ArrayList<>();
        trade.getLegs().forEach(w -> {
            boolean isLive = LIVE.equals(w.getStatus());
            boolean isBuy  = BUY.equals(w.getSide());
            String  orderId = isLive ? w.getOpenOrderId() : w.getCloseOrderId();
            Double  avgPrice = isLive
                    ? (isBuy ? w.getBuyFillPrice() : w.getSellFillPrice())
                    : (isBuy ? w.getSellFillPrice()   : w.getBuyFillPrice());
            if (orderId == null || orderId.isBlank() || avgPrice == null || avgPrice <= 0.0) return;
            ContractNoteParams p = new ContractNoteParams();
            p.orderID         = orderId;
            p.tradingSymbol   = w.getInstrument();
            p.exchange        = Constants.EXCHANGE_NFO;
            p.transactionType = isLive
                    ? (isBuy ? Constants.TRANSACTION_TYPE_BUY : Constants.TRANSACTION_TYPE_SELL)
                    : (isBuy ? Constants.TRANSACTION_TYPE_SELL : Constants.TRANSACTION_TYPE_BUY);
            p.variety      = Constants.VARIETY_REGULAR;
            p.product      = Constants.PRODUCT_NRML;
            p.orderType    = Constants.ORDER_TYPE_MARKET;
            p.quantity     = w.getQuantity();
            p.averagePrice = avgPrice;
            params.add(p);
            legs.add(w);
        });
        if (params.isEmpty()) return;

        List<ContractNote> notes = kiteGateway.getVirtualContractNote(params);
        if (notes == null || notes.size() != legs.size()) {
            log.warn("applyActualCharges: response size mismatch (expected {} got {}) — skipping",
                    legs.size(), notes == null ? 0 : notes.size());
            return;
        }
        for (int i = 0; i < legs.size(); i++) {
            WeeklyLeg w = legs.get(i);
            ContractNote    n = notes.get(i);
            if (n == null || n.charges == null) continue;
            boolean isLive = LIVE.equals(w.getStatus());
            Double exact = ComputeUtil.rnd(totalChargesForSliceCount(n.charges, sliceCount(w, isLive)));
            if (isLive) w.setOpenCharges(exact);
            else        w.setCloseCharges(exact);
        }
    }

    /**
     * Running max of Σ(LIVE legs.marginRequired) across the trade's lifetime — cumulative
     * over ALL books (the signal's total capital locked at the broker). Also tracks the
     * SYNTH_WEEKLY book's margin per synthetic lot (pair margin / pair lots) as its own
     * running max, because the NRML sizing stats need the weekly regime undiluted by
     * monthly premium.
     */
    public void calcPeakMargin(Position trade) {
        if (trade == null || trade.getLegs() == null) return;
        double currentSegmentMargin = trade.getLegs().stream()
                .filter(w -> LIVE.equals(w.getStatus()) && w.getMarginRequired() != null)
                .mapToDouble(WeeklyLeg::getMarginRequired)
                .sum();
        if (currentSegmentMargin <= 0.0) return;
        double currentPeak = trade.getPeakMargin() != null ? trade.getPeakMargin() : 0.0;
        if (currentSegmentMargin > currentPeak) {
            trade.setPeakMargin(ComputeUtil.rnd(currentSegmentMargin));
        }
        List<WeeklyLeg> weeklyLive = LegScope.of(trade, SYNTH_WEEKLY).stream()
                .filter(w -> LIVE.equals(w.getStatus()) && w.getMarginRequired() != null)
                .toList();
        double weeklyMargin = weeklyLive.stream().mapToDouble(WeeklyLeg::getMarginRequired).sum();
        int weeklyLots = weeklyLive.stream()
                .filter(w -> w.getLots() != null && w.getLots() > 0)
                .mapToInt(WeeklyLeg::getLots).max().orElse(0);
        if (weeklyMargin <= 0.0 || weeklyLots <= 0) return;
        double weeklyPerLot = weeklyMargin / weeklyLots;
        double currentWeeklyPeak = trade.getWeeklyMarginPerLot() != null ? trade.getWeeklyMarginPerLot() : 0.0;
        if (weeklyPerLot > currentWeeklyPeak) {
            trade.setWeeklyMarginPerLot(ComputeUtil.rnd(weeklyPerLot));
        }
    }

    /**
     * Persist per-slice fill data into WEEKLY_ORDER_FILL for slippage analytics.
     *
     * For each leg with a populated openOrderId / closeOrderId, parses the
     * comma-separated slice orderIDs, fetches per-slice fills from Kite, weighted-averages
     * each slice's fills, and INSERTs (open) or UPDATEs (close) one row per slice.
     *
     * Idempotent — re-runs are no-ops because we check existing fill rows before writing.
     * Must run inside a transaction (caller marks @Transactional) because it lazy-loads
     * the fills collection per leg.
     */
    public void captureSliceFills(Position trade) {
        if (trade == null || trade.getLegs() == null) return;
        trade.getLegs().forEach(this::captureSliceFillsForLeg);
    }

    private void captureSliceFillsForLeg(WeeklyLeg w) {
        if (w == null) return;
        String openOrderIds  = w.getOpenOrderId();
        String closeOrderIds = w.getCloseOrderId();
        boolean hasOpen  = openOrderIds  != null && !openOrderIds.isBlank();
        boolean hasClose = closeOrderIds != null && !closeOrderIds.isBlank();
        if (!hasOpen && !hasClose) return;

        boolean legIsBuy = BUY.equals(w.getSide());

        if (hasOpen) {
            boolean openSideIsBuy = legIsBuy;
            boolean alreadyCaptured = w.getFills().stream()
                    .anyMatch(f -> openSideIsBuy ? f.getBuyFillPrice() != null : f.getSellFillPrice() != null);
            if (!alreadyCaptured) captureSliceFillsForSide(w, openSideIsBuy, openOrderIds);
        }
        if (hasClose) {
            boolean closeSideIsBuy = !legIsBuy;
            boolean alreadyCaptured = w.getFills().stream()
                    .anyMatch(f -> closeSideIsBuy ? f.getBuyFillPrice() != null : f.getSellFillPrice() != null);
            if (!alreadyCaptured) captureSliceFillsForSide(w, closeSideIsBuy, closeOrderIds);
        }
    }

    private void captureSliceFillsForSide(WeeklyLeg w, boolean buySide, String orderIdsStr) {
        String[] sliceIds = orderIdsStr.split(CSV_SPLIT);
        if (sliceIds.length == 0) return;

        List<com.zerodhatech.models.Trade> allFills = fetchWithRetry(orderIdsStr,
                (buySide ? "BUY" : "SELL") + " slice fills");
        if (allFills == null || allFills.isEmpty()) {
            log.warn("captureSliceFills: no fills returned for {} side of leg {} after retry (orderIds={})",
                    buySide ? "BUY" : "SELL", w.getInstrument(), orderIdsStr);
            return;
        }

        Map<String, List<com.zerodhatech.models.Trade>> bySliceId = allFills.stream()
                .filter(t -> t != null && t.orderId != null)
                .collect(Collectors.groupingBy(t -> t.orderId));

        List<SliceFillData> slices = new ArrayList<>();
        for (String sliceId : sliceIds) {
            String id = sliceId.trim();
            if (id.isEmpty()) continue;
            List<com.zerodhatech.models.Trade> sliceFills = bySliceId.getOrDefault(id, Collections.emptyList());
            if (sliceFills.isEmpty()) continue;
            SliceFillData computed = computeSliceFill(id, sliceFills);
            if (computed != null) slices.add(computed);
        }
        if (slices.isEmpty()) return;

        slices.sort(Comparator.comparing(SliceFillData::time,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<Integer, LegFill> byIndex = w.getFills().stream()
                .collect(Collectors.toMap(LegFill::getSliceIndex, x -> x, (a, b) -> a));

        for (int i = 0; i < slices.size(); i++) {
            SliceFillData s = slices.get(i);
            LegFill row = byIndex.get(i);
            if (row == null) {
                row = new LegFill();
                row.setWeeklyLeg(w);
                row.setSliceIndex(i);
                w.getFills().add(row);
                byIndex.put(i, row);
            }
            String timeStr = formatFillTime(s.time());
            if (buySide) {
                row.setBuySliceQty(s.qty());
                row.setBuySliceOrderId(s.orderId());
                row.setBuyFillPrice(s.price());
                row.setBuyFillTime(timeStr);
            } else {
                row.setSellSliceQty(s.qty());
                row.setSellSliceOrderId(s.orderId());
                row.setSellFillPrice(s.price());
                row.setSellFillTime(timeStr);
            }
        }
    }

    private static SliceFillData computeSliceFill(String sliceOrderId,
                                                  List<com.zerodhatech.models.Trade> sliceFills) {
        double totalQty = 0.0;
        double totalValue = 0.0;
        java.util.Date earliestTime = null;
        for (com.zerodhatech.models.Trade tr : sliceFills) {
            if (tr == null || tr.averagePrice == null || tr.quantity == null) continue;
            try {
                double q = Double.parseDouble(tr.quantity);
                double p = Double.parseDouble(tr.averagePrice);
                totalQty += q;
                totalValue += q * p;
                if (tr.fillTimestamp != null &&
                        (earliestTime == null || tr.fillTimestamp.before(earliestTime))) {
                    earliestTime = tr.fillTimestamp;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        if (totalQty <= 0.0) return null;
        return new SliceFillData(
                sliceOrderId,
                (int) totalQty,
                Math.round((totalValue / totalQty) * 100.0) / 100.0,
                earliestTime != null ? earliestTime.toInstant() : null);
    }

    private static String formatFillTime(Instant t) {
        if (t == null) return null;
        return LocalDateTime.ofInstant(t, ZoneId.of(ZONE_ID))
                .format(DateTimeFormatter.ofPattern(DATE_FORMAT));
    }

    private record SliceFillData(String orderId, int qty, double price, Instant time) {}

    public MarginCalculationParams initCalcParam(int quantity) {
    	var params = new MarginCalculationParams();
    	params.exchange = Constants.EXCHANGE_NFO;
    	params.variety = Constants.VARIETY_REGULAR;
    	params.product = Constants.PRODUCT_NRML;
    	params.orderType = Constants.ORDER_TYPE_MARKET;
    	params.quantity = quantity;
    	return params;
    }

    public Map<String, Quote> getQuote(String[] ins) {
        return kiteGateway.getQuote(ins);
    }

    public List<MarginCalculationData> getMarginCalculation(List<MarginCalculationParams> params) {
        return kiteGateway.getMarginCalculation(params);
    }

    /**
     * Fetches executed trades for one or more comma-separated order IDs.
     * Splits the ID string (auto-slice orders produce multiple IDs), calls gateway
     * once per single ID, and aggregates results.
     */
    public List<com.zerodhatech.models.Trade> getOrderTrades(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            log.warn("getOrderTrades called with null/blank orderId — skipping");
            return new ArrayList<>();
        }
        log.debug("Fetching trades for orderId: {}", orderId);
        List<com.zerodhatech.models.Trade> allTrades = new ArrayList<>();
        Arrays.stream(orderId.split(CSV_SPLIT))
              .filter(id -> id != null && !id.trim().isEmpty())
              .forEach(id -> {
                  List<com.zerodhatech.models.Trade> orderTrades = kiteGateway.getOrderTrades(id);
                  allTrades.addAll(orderTrades);
                  log.debug("Fetched {} trades for orderId: {}", orderTrades.size(), id);
              });
        int totalQty = allTrades.stream().filter(t -> t != null).mapToInt(t -> parseIntSafe(t.quantity)).sum();
        log.info("fills orderId={}: {} fills, qty={}, wAvg={}", orderId, allTrades.size(), totalQty, weightedAvgFillPrice(allTrades));
        allTrades.forEach(trade -> log.debug("Fill[tradeId={}, orderId={}, symbol={}, type={}, qty={}, price={}, fillTime={}]",
                trade.tradeId, trade.orderId, trade.tradingSymbol, trade.transactionType,
                trade.quantity, trade.averagePrice, trade.fillTimestamp));
        return allTrades;
    }

    public List<BulkOrderResponse> placeAutoSliceOrder(String ins, Double price, String side, int quantity) {
        OrderParams orderParams = buildOrderParams();
        orderParams.transactionType = side;
        orderParams.tradingsymbol = ins;
        orderParams.quantity = quantity;
        orderParams.price = price;
        List<BulkOrderResponse> orders = kiteGateway.placeAutoSliceOrder(orderParams, Constants.VARIETY_REGULAR);
        orders.forEach(o -> {
            if (o.orderId != null) log.info("BulkOrder placed orderId: {}", o.orderId);
            else log.error("BulkOrder error — code: {}, message: {}", o.bulkOrderError.code, o.bulkOrderError.message);
        });
        return orders;
    }

    /**
     * Order pipeline shared by entries and exits — AGGRESSIVE-mode convenience overload.
     * Every pre-existing call site uses this signature, so legacy behavior is untouched.
     */
    public ExecResult placeAggressiveOrder(Quote q, String ins, String txn, int qty, String contextLabel) {
        return placeAggressiveOrder(q, ins, txn, qty, contextLabel, ExecMode.AGGRESSIVE);
    }

    /**
     * Order pipeline shared by entries and exits.
     *
     * Routes by size, mode and config:
     *   qty ≥ MAX_SIZE_PER_ORDER   → Kite broker-side auto-slice (MARKET per slice)
     *   mode=PATIENT + available   → patient fair-anchored LIMIT (minutes-scale, no MARKET
     *                                fallback on wide spreads) — PATIENT_EXECUTION_PLAN.md §3
     *   useLimitWalk=true          → graduated LIMIT (mid → walk → MARKET fallback), saves spread
     *   default                    → pure MARKET protection=1 (legacy behavior)
     *
     * A PATIENT request degrades to the aggressive path when patientModeAvailable() is false
     * (flag off, invalid config, or past the IST cutoff), so callers never need a fallback branch.
     *
     * Logging: every line emitted while a leg executes carries an MDC tag
     * "&lt;context&gt;:&lt;instrument&gt; | " for attribution, and the leg's step-by-step story is
     * flushed as one contiguous multi-line INFO block when the leg finishes (ERROR block
     * if it dies mid-flight), so parallel legs never interleave their summaries.
     */
    public ExecResult placeAggressiveOrder(Quote q, String ins, String txn, int qty, String contextLabel, ExecMode mode) {
        return placeAggressiveOrder(q, ins, txn, qty, contextLabel, mode, 0L);
    }

    /**
     * Full-control overload: maxWaitMs is a hard wall-clock cap on a PATIENT order (0 = the full
     * configured schedule). The interleaved monthly flip uses it to hand each close slice its
     * share of the flip window (PATIENT_EXECUTION_PLAN.md §4.2); the aggressive paths ignore it.
     */
    public ExecResult placeAggressiveOrder(Quote q, String ins, String txn, int qty, String contextLabel, ExecMode mode, long maxWaitMs) {
        MDC.put(MDC_LEG_KEY, contextLabel + ":" + ins + " | ");
        ExecTrace trace = new ExecTrace();
        ExecResult result = null;
        try {
            result = ScopedValue.where(EXEC_TRACE, trace).call(() -> {
                if (qty >= MAX_SIZE_PER_ORDER) {
                    log.warn("qty={} >= MAX_SIZE_PER_ORDER — using auto-slice MARKET path", qty);
                    trace.record("qty %d >= MAX_SIZE_PER_ORDER -> auto-slice MARKET", qty);
                    return autoSliceFallback(ins, txn, qty, contextLabel);
                }
                if (mode == ExecMode.PATIENT) {
                    if (patientModeAvailable()) {
                        return placePatientLimit(q, ins, txn, qty, contextLabel, maxWaitMs);
                    }
                    log.info("PATIENT requested but unavailable (enabled={}, configValid={}, cutoffPassed={}) — aggressive path",
                            patientEnabled, patientConfigValid, pastPatientCutoff());
                    trace.record("PATIENT unavailable -> aggressive path");
                }
                if (useLimitWalk) {
                    return placeGraduatedLimit(q, ins, txn, qty, contextLabel);
                }
                return placeMarketCore(ins, txn, qty, contextLabel);
            });
            return result;
        } finally {
            if (result != null) {
                log.info("done in {}ms | filled {}/{} avg={} term={}{}",
                        trace.elapsedMs(), result.totalFilled(), qty,
                        result.weightedAvgFillPrice(), result.terminalStatus(), trace.render());
            } else {
                log.error("ABORTED by exception after {}ms — steps completed before failure:{}",
                        trace.elapsedMs(), trace.render());
            }
            MDC.remove(MDC_LEG_KEY);
        }
    }

    // ─── Tick walk schedule (used only when useLimitWalk=true) ─────────────────────
    /** Walk deadlines from t0 (ms). At each deadline we check fill, then modify if unfilled. */
    private static final long[]   WALK_DELAYS_MS  = {500L, 1500L, 2500L};
    /** Aggression at each step: fraction of half-spread from mid toward the marketable quote.
     *  0.25 = quarter, 0.50 = at ask/bid, 1.00 = past quote (rounded down to tick anyway). */
    private static final double[] WALK_AGGRESSION = {0.25, 0.50, 1.00};

    /**
     * Graduated-LIMIT execution: places LIMIT at midpoint, walks toward the marketable quote
     * at three timed steps, then falls back to MARKET. Every failure path (no quote, weird
     * spread, placement error, walk exhausted) falls back to MARKET, so a leg can never end
     * up un-hedged.
     *
     * Expected wall-clock: ≤ 2.5s before MARKET fallback. Real-world benefit: 30-50% less
     * slippage vs pure MARKET on typical ATM NIFTY weekly options (mid is reachable on ~half
     * the orders; the rest walk a tick or two before crossing).
     *
     * Quote freshness: a flip pre-fetches the entry quote while the close orders execute, so by
     * walk time it can be seconds stale. The walk refreshes the quote at the start (anchoring the
     * initial LIMIT to the live book) and re-anchors mid/half-spread to the current book at every
     * step, so a trending market is chased rather than repriced inside a now-dead spread — the
     * 2026-06-25 ~6pt run-away, where every step sat in the stale spread and only MARKET filled.
     * A failed/unusable refresh keeps the last-known reference; only when neither the fresh nor the
     * passed-in quote has usable depth does it fall back to MARKET.
     *
     * Walk-exhausted path (over-fill safety): when the walk ends without a full fill, the LIMIT
     * is cancelled and its TRUE settled fill is re-read via confirmTerminalFill BEFORE the MARKET
     * top-up is sized. A Kite cancel is asynchronous and NOT atomic with matching: a LIMIT the
     * walk repriced to cross the spread keeps filling between the cancel request and the exchange
     * acting on it. Sizing the top-up from the pre-cancel snapshot double-counts those in-flight
     * fills — the LIMIT settles more than the snapshot shows AND the MARKET adds the stale
     * remainder on top. That was the 2026-06-19 over-fill: snapshot 195 → MARKET 455, but the
     * LIMIT actually settled 520, so the real position was 520+455=975 against an intended 650.
     * Sizing from the confirmed post-cancel fill (no further fills possible once dead) closes it;
     * a residual combined > qty is logged as OVERFILL for manual review.
     *
     * The one failure path that does NOT fall back to MARKET: a LIMIT whose cancel cannot be
     * confirmed (2026-10-07 — Kite rejected the cancel, the order stayed OPEN, the old top-up
     * doubled the position). That leg returns non-terminal with the LIMIT id and parks
     * PENDING_OPEN/PENDING_CLOSE for the reconciler; never two working orders per leg.
     * <p>Step waits (D₂): with a healthy order stream each step waits on the order's terminal-event
     * future and falls through to the REST confirm as soon as the event arrives (or was already
     * cached); a step deadline without an event just proceeds to the REST peek. The REST
     * peekOrderState after the wait stays the source of truth — the WS event only short-circuits
     * the wait. On a WS error mid-flight the attempt degrades to sleep-poll for its remaining
     * steps (awaitFut=null sentinel), and the awaiter is re-armed after every modify because a
     * modify resets the order's terminal state.
     */
    private ExecResult placeGraduatedLimit(Quote q, String ins, String txn, int qty, String contextLabel) {
        ExecTrace trace = EXEC_TRACE.get();
        boolean isBuy = BUY.equals(txn);
        Quote fresh = refreshQuote(ins);
        double[] bk = midHalfSpread(fresh);
        Quote ref = fresh;
        if (bk == null) { bk = midHalfSpread(q); ref = q; }
        if (bk == null) {
            log.warn("no usable quote/depth — MARKET fallback");
            trace.record("no usable quote/depth -> MARKET fallback");
            return placeMarketCore(ins, txn, qty, contextLabel);
        }
        double mid = bk[0], halfSp = bk[1];
        trace.quote("bid=%s ask=%s ltp=%s mid=%s spread=%s", bk[2], bk[3], ref.lastPrice,
                Math.round(mid * 1000.0) / 1000.0, Math.round((bk[3] - bk[2]) * 100.0) / 100.0);

        OrderParams params = buildAggressiveOrderParams();
        params.orderType       = Constants.ORDER_TYPE_LIMIT;
        params.validity        = Constants.VALIDITY_DAY;
        params.transactionType = txn;
        params.tradingsymbol   = ins;
        params.quantity        = qty;
        params.price           = roundToTick(mid, NIFTY_OPT_TICK);

        OrderResponse resp = kiteGateway.placeOrder(params, Constants.VARIETY_REGULAR);
        if (resp == null || resp.orderId == null) {
            log.error("LIMIT placeOrder null — MARKET fallback");
            trace.record("LIMIT placeOrder returned null -> MARKET fallback");
            return placeMarketCore(ins, txn, qty, contextLabel);
        }
        log.info("LIMIT @ {} placed orderId={}", params.price, resp.orderId);
        trace.record("LIMIT @ %s placed orderId=%s", params.price, resp.orderId);

        final boolean wsHealthy = orderStream != null && orderStream.isHealthy();
        java.util.concurrent.CompletableFuture<com.zerodhatech.models.Order> awaitFut =
                wsHealthy ? orderStream.awaitTerminal(resp.orderId) : null;

        long t0 = System.currentTimeMillis();
        for (int step = 0; step < WALK_DELAYS_MS.length; step++) {
            long deadline = t0 + WALK_DELAYS_MS[step];
            long waitMs = deadline - System.currentTimeMillis();

            if (awaitFut != null && waitMs > 0) {
                try {
                    awaitFut.get(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException te) {
                } catch (Exception e) {
                    log.warn("WS await error: {} — falling back to sleep-poll for remaining steps", e.getMessage());
                    trace.record("WS await error: %s -> sleep-poll for remaining steps", e.getMessage());
                    awaitFut = null;
                }
            } else if (waitMs > 0) {
                sleepMillis(waitMs);
            }

            AttemptResult ar = peekOrderState(resp.orderId);
            if (ar.filledQty() >= qty && isTerminal(ar.status())) {
                trace.record("FILLED %d/%d avg=%s (step %d, mode=%s)",
                        ar.filledQty(), qty, ar.avgFillPrice(), step, awaitFut != null ? "WS" : "REST");
                if (orderStream != null) orderStream.cancel(resp.orderId);
                return new ExecResult(resp.orderId, ar.filledQty(), qty, ar.avgFillPrice(), true, ORDER_COMPLETE);
            }
            double[] cur = midHalfSpread(refreshQuote(ins));
            if (cur != null) { mid = cur[0]; halfSp = cur[1]; }
            double newPx = roundToTick(isBuy
                    ? mid + halfSp * WALK_AGGRESSION[step]
                    : mid - halfSp * WALK_AGGRESSION[step], NIFTY_OPT_TICK);
            boolean mod = kiteGateway.modifyOrder(resp.orderId, newPx, qty, Constants.VARIETY_REGULAR);
            log.debug("walk step={} newPx={} filledSoFar={}/{} modified={}", step, newPx, ar.filledQty(), qty, mod);
            trace.record("step%d -> %s filled %d/%d%s", step, newPx, ar.filledQty(), qty, mod ? "" : " (modify FAILED)");

            if (awaitFut != null) awaitFut = orderStream.awaitTerminal(resp.orderId);
        }

        AttemptResult finalAr = peekOrderState(resp.orderId);
        if (finalAr.filledQty() >= qty && isTerminal(finalAr.status())) {
            trace.record("FILLED %d/%d avg=%s (post-walk check)", finalAr.filledQty(), qty, finalAr.avgFillPrice());
            if (orderStream != null) orderStream.cancel(resp.orderId);
            return new ExecResult(resp.orderId, finalAr.filledQty(), qty, finalAr.avgFillPrice(), true, ORDER_COMPLETE);
        }
        return cancelAndTopUp(resp.orderId, ins, txn, qty, contextLabel, "walk exhausted");
    }

    /**
     * Finishes an unfilled/partial working LIMIT the aggressive way: cancels it, confirms its TRUE
     * settled fill via cancelAndConfirm (the cancel-vs-fill discipline from the 2026-06-19
     * over-fill, with the cancel request itself retried), then sizes a MARKET top-up from the
     * confirmed remainder and returns the combined result. Extracted verbatim from the graduated
     * walk's exhausted path so the patient loop's deadline-action=MARKET shares the identical
     * over-fill guarantees. reason labels log/trace lines.
     *
     * Hard rule (2026-10-07 NIFTY26OCT22650PE double-buy): the MARKET top-up is placed ONLY once
     * the LIMIT is confirmed terminal. That day Kite rejected the cancel with an "internal error"
     * while the order was still OPEN, the old code topped up anyway and dropped the LIMIT's id,
     * the LIMIT filled minutes later, and 65 untracked lots sat at the broker for seven sessions.
     * When every cancel attempt leaves the LIMIT non-terminal, NO second order is placed: the
     * result keeps the LIMIT's order id with its non-terminal status, closeOrderMayBeLive() holds,
     * the leg parks PENDING_OPEN / PENDING_CLOSE and the reconciler settles it from the tradebook —
     * a late fill is then a tracked position and a cancel that lands later a tracked non-fill.
     */
    private ExecResult cancelAndTopUp(String orderId, String ins, String txn, int qty, String contextLabel, String reason) {
        ExecTrace trace = EXEC_TRACE.get();
        if (orderStream != null) orderStream.cancel(orderId);

        AttemptResult confirmed = cancelAndConfirm(orderId);
        int filledByLimit  = confirmed.filledQty();
        double limitAvg    = filledByLimit > 0 ? confirmed.avgFillPrice() : 0.0;
        int remaining = Math.max(0, qty - filledByLimit);

        if (remaining == 0) {
            if (filledByLimit > qty) {
                log.error("OVER-FILL: LIMIT {} settled {}/{} (exceeds requested) — no top-up placed; position "
                        + "oversized by {}, manual review required", orderId, filledByLimit, qty, filledByLimit - qty);
                trace.record("OVER-FILL filled=%d > requested=%d -> no top-up", filledByLimit, qty);
            } else {
                trace.record("FILLED %d/%d avg=%s via LIMIT (%s, confirmed after cancel)", filledByLimit, qty, limitAvg, reason);
            }
            return new ExecResult(orderId, filledByLimit, qty, limitAvg, filledByLimit >= qty,
                    filledByLimit > qty ? OVERFILL : ORDER_COMPLETE);
        }
        if (!isTerminal(confirmed.status())) {
            String st = confirmed.status() != null ? confirmed.status() : UNKNOWN;
            log.error("LIMIT {} still {} (filled {}/{}) after {} cancel attempts — NO MARKET top-up ({}): a second "
                    + "order while this LIMIT may still fill is the 2026-10-07 double-buy; the leg goes to the "
                    + "PENDING reconciler carrying the LIMIT order id", orderId, st, filledByLimit, qty,
                    CANCEL_ATTEMPTS, reason);
            trace.record("%s -> cancel UNCONFIRMED (status=%s filled=%d/%d) -> NO top-up; LIMIT %s handed to the PENDING reconciler",
                    reason, st, filledByLimit, qty, orderId);
            return new ExecResult(orderId, filledByLimit, qty, limitAvg, false, st);
        }
        log.warn("LIMIT {}, cancelled with confirmed {} fill {}/{} — MARKET for remaining qty={}",
                reason, confirmed.status(), filledByLimit, qty, remaining);
        trace.record("%s -> cancelled LIMIT (confirmed %s filled=%d), MARKET for remaining %d",
                reason, confirmed.status(), filledByLimit, remaining);
        ExecResult mkt = placeMarketCore(ins, txn, remaining, contextLabel);

        int combined  = filledByLimit + mkt.totalFilled();
        double avg    = combined > 0
                ? (filledByLimit * limitAvg + mkt.totalFilled() * mkt.weightedAvgFillPrice()) / combined
                : 0.0;
        String ids    = filledByLimit > 0 ? orderId + ", " + mkt.aggregateOrderIds() : mkt.aggregateOrderIds();
        if (combined > qty) {
            log.error("OVER-FILL: {} settled {}/{} (LIMIT {} + MARKET {}) — position oversized by {}, "
                    + "manual review/unwind required", ins, combined, qty, filledByLimit, mkt.totalFilled(), combined - qty);
            trace.record("OVER-FILL combined=%d > requested=%d (limit=%d market=%d)", combined, qty, filledByLimit, mkt.totalFilled());
        }
        boolean full  = combined >= qty;
        return new ExecResult(ids, combined, qty, avg, full,
                combined > qty ? OVERFILL : (full ? ORDER_COMPLETE : (combined > 0 ? PARTIAL : FAILED)));
    }

    // ─── PATIENT execution (PATIENT_EXECUTION_PLAN.md §3) ──────────────────────────
    /** Longest single wait inside the patient loop, so a passing cutoff interrupts promptly. */
    private static final long PATIENT_WAIT_CHUNK_MS = 5000L;

    /**
     * Fair price for a thin book (plan §3.2). Sane spread (≤ saneSpreadPct of mid) → trust the
     * midpoint, exactly like the walk. Wide spread → the mid of a 440/490 book is noise, so use
     * the last traded price CLAMPED into the current bid..ask (a stale LTP must never price
     * outside the live book). Unusable depth degrades to raw LTP; no price at all → null, which
     * tells the caller patient mode cannot run. Deliberately has NO wide-book rejection — a wide
     * book is the reason patient mode exists, never a reason to bail to MARKET.
     */
    private static Double fairAnchor(Quote q, double saneSpreadPct) {
        if (q == null) return null;
        if (q.depth == null || q.depth.buy == null || q.depth.buy.isEmpty()
                || q.depth.sell == null || q.depth.sell.isEmpty()) {
            return q.lastPrice > 0 ? q.lastPrice : null;
        }
        double bid = q.depth.buy.get(0).getPrice();
        double ask = q.depth.sell.get(0).getPrice();
        if (bid <= 0 || ask <= 0 || ask < bid) {
            return q.lastPrice > 0 ? q.lastPrice : null;
        }
        double mid = (bid + ask) / 2.0;
        double spreadPct = (ask - bid) / mid * 100.0;
        if (spreadPct <= saneSpreadPct) return mid;
        if (q.lastPrice <= 0) return mid;
        return Math.min(ask, Math.max(bid, q.lastPrice));
    }

    /**
     * Patient fair-anchored LIMIT (plan §3.3): rest at fair, re-anchor and concede slowly across
     * the configured minutes-scale schedule, capped at max(max-concession-pts, fair ×
     * max-concession-pct) — enforced against BOTH the live fair and the original anchor, so a
     * garbage quote can never walk the price unboundedly while a genuinely moved fair is still
     * followed within the cap band. No MARKET fallback on wide spreads; unfilled at deadline (or
     * past the IST cutoff) applies deadline-action:
     *   REST   (default) — leave the LIMIT resting; the returned non-terminal ExecResult keeps the
     *          orderId so closeOrderMayBeLive() holds, the leg goes PENDING_CLOSE and the
     *          reconciler owns it (§3.5). No new status machinery.
     *   MARKET — cancelAndTopUp: the walk-exhausted cancel→confirm→top-up discipline verbatim.
     * The only aggressive fallback is the degenerate no-price-at-all case (quote-less leg cannot
     * be priced patiently); placement failure returns PLACE_FAILED without ever going MARKET.
     */
    private ExecResult placePatientLimit(Quote q, String ins, String txn, int qty, String contextLabel, long maxWaitMs) {
        ExecTrace trace = EXEC_TRACE.get();
        boolean isBuy = BUY.equals(txn);
        Quote fresh = refreshQuote(ins);
        Quote ref = fresh != null ? fresh : q;
        Double anchor = fairAnchor(ref, patientSaneSpreadPct);
        if (anchor == null) {
            log.warn("PATIENT: no usable quote or LTP — degrading to aggressive path");
            trace.record("PATIENT no usable quote/ltp -> aggressive path");
            return useLimitWalk ? placeGraduatedLimit(q, ins, txn, qty, contextLabel)
                                : placeMarketCore(ins, txn, qty, contextLabel);
        }
        final double anchor0 = anchor;
        final double cap = Math.max(patientMaxConcessionPts, anchor0 * patientMaxConcessionPct / 100.0);
        trace.quote("PATIENT anchor=%s cap=%s ltp=%s", Math.round(anchor0 * 1000.0) / 1000.0,
                Math.round(cap * 1000.0) / 1000.0, ref.lastPrice);

        OrderParams params = buildAggressiveOrderParams();
        params.orderType       = Constants.ORDER_TYPE_LIMIT;
        params.validity        = Constants.VALIDITY_DAY;
        params.transactionType = txn;
        params.tradingsymbol   = ins;
        params.quantity        = qty;
        params.price           = roundToTick(anchor0, NIFTY_OPT_TICK);
        params.tag             = patientTag();

        OrderResponse resp = kiteGateway.placeOrder(params, Constants.VARIETY_REGULAR);
        if (resp == null || resp.orderId == null) {
            resp = recoverLostPatientPlace(params, trace);
        }
        if (resp == null || resp.orderId == null) {
            log.error("PATIENT LIMIT placement unresolved — PLACE_FAILED (no MARKET fallback in patient mode)");
            trace.record("PATIENT LIMIT place unresolved -> PLACE_FAILED");
            return new ExecResult("", 0, qty, 0.0, false, PLACE_FAILED);
        }
        log.info("PATIENT LIMIT @ {} placed orderId={} (anchor={} cap={})", params.price, resp.orderId, anchor0, cap);
        trace.record("PATIENT LIMIT @ %s placed orderId=%s", params.price, resp.orderId);

        final boolean wsHealthy = orderStream != null && orderStream.isHealthy();
        CompletableFuture<Order> awaitFut = wsHealthy ? orderStream.awaitTerminal(resp.orderId) : null;

        long t0 = System.currentTimeMillis();
        long hardDeadline = maxWaitMs > 0 ? t0 + maxWaitMs : Long.MAX_VALUE;
        double lastPx = params.price;
        double lastFair = anchor0;
        boolean cutoffBail = false;
        for (int step = 0; step < patientStepDelaysMs.length; step++) {
            awaitFut = patientAwaitUntil(awaitFut, Math.min(t0 + patientStepDelaysMs[step], hardDeadline));
            AttemptResult ar = peekOrderState(resp.orderId);
            if (ar.filledQty() >= qty && isTerminal(ar.status())) {
                trace.record("PATIENT FILLED %d/%d avg=%s (step %d, slipVsAnchor=%s)", ar.filledQty(), qty,
                        ar.avgFillPrice(), step,
                        ComputeUtil.rnd(isBuy ? ar.avgFillPrice() - anchor0 : anchor0 - ar.avgFillPrice()));
                if (orderStream != null) orderStream.cancel(resp.orderId);
                return new ExecResult(resp.orderId, ar.filledQty(), qty, ar.avgFillPrice(), true, ORDER_COMPLETE);
            }
            if (pastPatientCutoff()) {
                cutoffBail = true;
                trace.record("PATIENT cutoff reached at step %d -> deadline action", step);
                break;
            }
            if (System.currentTimeMillis() >= hardDeadline) {
                trace.record("PATIENT slice budget (%dms) exhausted at step %d -> deadline action", maxWaitMs, step);
                break;
            }
            Double fairNow = fairAnchor(refreshQuote(ins), patientSaneSpreadPct);
            if (fairNow != null) lastFair = fairNow;
            double frac = patientConcessionFractions[step];
            double target = isBuy ? Math.min(lastFair + cap * frac, anchor0 + cap)
                                  : Math.max(lastFair - cap * frac, anchor0 - cap);
            double newPx = roundToTick(target, NIFTY_OPT_TICK);
            if (newPx != lastPx) {
                boolean mod = kiteGateway.modifyOrder(resp.orderId, newPx, qty, Constants.VARIETY_REGULAR);
                trace.record("PATIENT step%d -> %s (fair=%s frac=%s) filled %d/%d%s", step, newPx,
                        Math.round(lastFair * 1000.0) / 1000.0, frac, ar.filledQty(), qty, mod ? "" : " (modify FAILED)");
                lastPx = newPx;
            } else {
                trace.record("PATIENT step%d holds @ %s filled %d/%d", step, lastPx, ar.filledQty(), qty);
            }
            if (awaitFut != null) awaitFut = orderStream.awaitTerminal(resp.orderId);
        }

        AttemptResult ar = peekOrderState(resp.orderId);
        if (ar.filledQty() >= qty && isTerminal(ar.status())) {
            trace.record("PATIENT FILLED %d/%d avg=%s (final check)", ar.filledQty(), qty, ar.avgFillPrice());
            if (orderStream != null) orderStream.cancel(resp.orderId);
            return new ExecResult(resp.orderId, ar.filledQty(), qty, ar.avgFillPrice(), true, ORDER_COMPLETE);
        }
        if ("MARKET".equalsIgnoreCase(patientDeadlineAction)) {
            log.warn("PATIENT {} unfilled ({}/{}) at {} — deadline-action MARKET", ins, ar.filledQty(), qty,
                    cutoffBail ? "cutoff" : "deadline");
            return cancelAndTopUp(resp.orderId, ins, txn, qty, contextLabel, "patient deadline");
        }
        if (orderStream != null) orderStream.cancel(resp.orderId);
        String st = ar.status() != null ? ar.status() : UNKNOWN;
        if (isTerminal(st)) {
            boolean any = ar.filledQty() > 0;
            log.error("PATIENT {} order {} went terminal-unfilled ({}, filled {}/{}) before deadline handling",
                    ins, resp.orderId, st, ar.filledQty(), qty);
            trace.record("PATIENT terminal short of qty: %s filled %d/%d", st, ar.filledQty(), qty);
            return new ExecResult(any ? resp.orderId : "", ar.filledQty(), qty,
                    any ? ar.avgFillPrice() : 0.0, false, any ? PARTIAL : st);
        }
        log.warn("PATIENT {} REST: LIMIT {} left resting @ {} with filled {}/{} (status={}) — "
                + "PENDING machinery owns it from here", ins, resp.orderId, lastPx, ar.filledQty(), qty, st);
        trace.record("PATIENT %s -> REST (filled %d/%d status=%s, resting @ %s)",
                cutoffBail ? "cutoff" : "deadline", ar.filledQty(), qty, st, lastPx);
        return new ExecResult(resp.orderId, ar.filledQty(), qty, ar.avgFillPrice(), false, st);
    }

    /**
     * Waits until the given step deadline in ≤5s chunks, so an IST cutoff passing mid-wait is
     * noticed within one chunk. Uses the WS terminal-event future when available (returns as soon
     * as the event fires — the caller's REST peek stays the source of truth) and degrades to
     * sleep-polling for the remainder of the attempt on any WS error, mirroring the graduated
     * walk's D₂ behavior. Returns the (possibly nulled) future for the caller to re-arm.
     */
    private CompletableFuture<Order> patientAwaitUntil(CompletableFuture<Order> awaitFut, long deadlineMs) {
        while (true) {
            long waitMs = deadlineMs - System.currentTimeMillis();
            if (waitMs <= 0 || pastPatientCutoff()) return awaitFut;
            long chunk = Math.min(waitMs, PATIENT_WAIT_CHUNK_MS);
            if (awaitFut != null) {
                try {
                    awaitFut.get(chunk, java.util.concurrent.TimeUnit.MILLISECONDS);
                    return awaitFut;
                } catch (java.util.concurrent.TimeoutException te) {
                    continue;
                } catch (Exception e) {
                    log.warn("PATIENT WS await error: {} — sleep-poll for the rest of this attempt", e.getMessage());
                    awaitFut = null;
                }
            } else {
                sleepMillis(chunk);
            }
        }
    }

    // ─── Cancel-confirmation (closes the cancel-vs-fill over-fill race) ─────────────
    /** Max polls of getOrderHistory waiting for a cancelled order to reach a terminal state. */
    private static final int  CANCEL_CONFIRM_POLLS   = 6;
    /** Gap between cancel-confirmation polls (ms). 6 × 150ms ≈ 0.9s worst case on the rare walk-exhausted path. */
    private static final long CANCEL_CONFIRM_GAP_MS  = 150L;

    /**
     * After a cancel request, polls the order until it reaches a terminal state and returns its
     * TRUE settled fill. A Kite cancel is asynchronous and not atomic with matching, so a
     * marketable LIMIT can keep filling between the cancel request and the exchange acting on it.
     * Reading filledQuantity only once the order is CANCELLED/COMPLETE/REJECTED is what makes the
     * subsequent MARKET top-up correctly sized — sizing it from a pre-cancel snapshot double-buys
     * the in-flight fills (the 650-intended-vs-975-filled over-fill).
     *
     * If the order never confirms terminal within the budget, returns the last read (non-terminal)
     * state; the caller logs the residual over-fill risk and proceeds conservatively.
     */
    private AttemptResult confirmTerminalFill(String orderId) {
        AttemptResult ar = peekOrderState(orderId);
        for (int i = 0; i < CANCEL_CONFIRM_POLLS && !isTerminal(ar.status()); i++) {
            sleepMillis(CANCEL_CONFIRM_GAP_MS);
            ar = peekOrderState(orderId);
        }
        return ar;
    }

    /**
     * Cancel requests issued for one working order before the caller gives up on cancelling it.
     * Kite rejected a cancel with HTTP 400 "An internal error occurred" on 2026-09-22 and
     * 2026-10-07 while the order was still OPEN (a walk modify most likely still settling at the
     * exchange); a fresh request after the ~0.9s confirm budget is expected to land. Worst case
     * adds ≈ 3 × 0.9s to a leg that is already on its failure path.
     */
    private static final int CANCEL_ATTEMPTS = 3;

    /**
     * Cancels a working order and confirms its TRUE terminal state, re-issuing the cancel request
     * whenever the order is still non-terminal after a confirm budget — a rejected or lost cancel
     * must never be mistaken for a dead order. Returns the last read: terminal when the cancel
     * landed (or the order filled/rejected meanwhile), still non-terminal when every attempt left
     * it working, in which case the caller must treat the order as LIVE at the broker.
     */
    private AttemptResult cancelAndConfirm(String orderId) {
        ExecTrace trace = EXEC_TRACE.get();
        AttemptResult confirmed = null;
        for (int attempt = 1; attempt <= CANCEL_ATTEMPTS; attempt++) {
            boolean accepted = kiteGateway.cancelOrder(orderId, Constants.VARIETY_REGULAR);
            String verdict = accepted ? "accepted" : "REJECTED by Kite";
            confirmed = confirmTerminalFill(orderId);
            if (isTerminal(confirmed.status())) {
                trace.record("cancel #%d %s -> %s filled %d", attempt, verdict, confirmed.status(), confirmed.filledQty());
                return confirmed;
            }
            log.warn("LIMIT {} cancel attempt {}/{} {} — order still {} (filled {}) after the confirm budget",
                    orderId, attempt, CANCEL_ATTEMPTS, verdict, confirmed.status(), confirmed.filledQty());
            trace.record("cancel #%d %s -> still %s filled %d", attempt, verdict, confirmed.status(), confirmed.filledQty());
        }
        return confirmed;
    }

    /**
     * Pure MARKET path (extracted from old placeAggressiveOrder). Reused as the safety fallback.
     * The result carries the orderId when fills exist OR the order's last-read state is
     * non-terminal — a possibly-still-working order must stay traceable so the PENDING_CLOSE
     * reconciler can settle it later. A definitively terminal 0-fill (REJECTED/CANCELLED)
     * returns no id, preserving the legacy null openOrderId/closeOrderId that downstream
     * classification (ComputeUtil.neverTraded) and fill-fetch retries key on.
     */
    private ExecResult placeMarketCore(String ins, String txn, int qty, String contextLabel) {
        ExecTrace trace = EXEC_TRACE.get();
        OrderParams params = buildAggressiveOrderParams();
        params.orderType        = Constants.ORDER_TYPE_MARKET;
        params.validity         = Constants.VALIDITY_DAY;
        params.transactionType  = txn;
        params.tradingsymbol    = ins;
        params.quantity         = qty;
        params.marketProtection = 1;
        OrderResponse resp = kiteGateway.placeOrder(params, Constants.VARIETY_REGULAR);
        if (resp == null || resp.orderId == null) {
            log.error("MARKET placeOrder returned null");
            trace.record("MARKET placeOrder returned null -> PLACE_FAILED");
            return new ExecResult("", 0, qty, 0.0, false, PLACE_FAILED);
        }
        log.info("MARKET protection=1 placed: orderId={}", resp.orderId);
        trace.record("MARKET placed orderId=%s", resp.orderId);

        AttemptResult ar = confirmMarketFill(resp.orderId, qty, ins);
        boolean full = ar.filledQty() >= qty;
        String term  = full ? ORDER_COMPLETE : (ar.filledQty() > 0 ? PARTIAL : (ar.status() != null ? ar.status() : FAILED));
        String ids   = (ar.filledQty() > 0 || !isTerminal(ar.status())) ? resp.orderId : "";
        trace.record("MARKET %s %d/%d avg=%s", term, ar.filledQty(), qty, ar.avgFillPrice());
        return new ExecResult(ids, ar.filledQty(), qty, ar.avgFillPrice(), full, term);
    }

    // ─── MARKET fill confirmation (closes the under-report-as-FAILED gap) ────────────
    /**
     * Confirmation budget for a MARKET order. Deliberately longer than verifyAttempt's
     * because a MARKET fill plus Kite's order-state / tradebook propagation can lag a
     * second or two. 10 × 250ms ≈ 2.5s worst case, paid only while the order has not yet
     * confirmed COMPLETE.
     */
    private static final int  MARKET_CONFIRM_POLLS  = 10;
    private static final long MARKET_CONFIRM_GAP_MS = 250L;

    /**
     * Confirms a MARKET order's TRUE settled state before the caller may treat it as failed.
     * verifyAttempt reads only getOrderHistory and gives up after ~600ms; on 2026-06-25 the
     * order-history snapshot still showed OPEN/0 after that window while the broker had already
     * filled 650, so the leg was declared FAILED even though the short executed. This method
     * (1) polls to a terminal state with a longer budget and (2) reconciles against the
     * tradebook (getOrderTrades) — the authoritative record of what actually executed at the
     * broker — trusting it over the order-history snapshot whenever the two disagree. A MARKET
     * DAY order placed in-session virtually always fills, so an empty tradebook here is the rare
     * genuine reject, not the default assumption.
     *
     * If the order reaches a terminal state short of full, the filled portion is taken from the
     * tradebook (genuine partial/reject). If the poll budget is exhausted with no terminal state,
     * the tradebook is still trusted over the lagging snapshot; only a truly empty tradebook is
     * reported unconfirmed, with an error so a possibly-live leg is reconciled before re-entry.
     */
    private AttemptResult confirmMarketFill(String orderId, int qty, String ins) {
        ExecTrace trace = EXEC_TRACE.get();
        AttemptResult lastHist = new AttemptResult(orderId, 0, 0.0, UNKNOWN);
        for (int i = 0; i < MARKET_CONFIRM_POLLS; i++) {
            AttemptResult hist = peekOrderState(orderId);
            if (hist.status() != null && !UNKNOWN.equals(hist.status())) lastHist = hist;

            List<com.zerodhatech.models.Trade> trades = kiteGateway.getOrderTrades(orderId);
            int traded = tradedQty(trades);
            if (traded >= qty) {
                double vwap = weightedAvgFillPrice(trades);
                trace.record("MARKET reconciled via tradebook: filled %d/%d avg=%s (hist=%s)", traded, qty, vwap, lastHist.status());
                return new AttemptResult(orderId, traded, vwap, ORDER_COMPLETE);
            }
            if (isTerminal(lastHist.status())) {
                if (traded > 0) return new AttemptResult(orderId, traded, weightedAvgFillPrice(trades), lastHist.status());
                return lastHist;
            }
            sleepMillis(MARKET_CONFIRM_GAP_MS);
        }
        List<com.zerodhatech.models.Trade> trades = kiteGateway.getOrderTrades(orderId);
        int traded = tradedQty(trades);
        if (traded > 0) {
            double vwap = weightedAvgFillPrice(trades);
            log.warn("MARKET orderId={} not terminal after {} polls — tradebook shows {}/{} avg={}, trusting tradebook over hist status={}",
                    orderId, MARKET_CONFIRM_POLLS, traded, qty, vwap, lastHist.status());
            trace.record("MARKET budget exhausted; tradebook %d/%d avg=%s trusted over hist=%s", traded, qty, vwap, lastHist.status());
            return new AttemptResult(orderId, traded, vwap, traded >= qty ? ORDER_COMPLETE : lastHist.status());
        }
        log.error("MARKET orderId={} UNCONFIRMED after {} polls — hist status={} filled={}, tradebook empty; "
                + "leg may still be live at broker — reconcile before re-entry", orderId, MARKET_CONFIRM_POLLS, lastHist.status(), lastHist.filledQty());
        trace.record("MARKET UNCONFIRMED after %d polls (hist=%s, tradebook empty)", MARKET_CONFIRM_POLLS, lastHist.status());
        return lastHist;
    }

    // ─── PENDING_CLOSE reconciliation (closes the still-working-order-marked-FAILED gap) ───
    /**
     * True when a close attempt's order may still be working at the broker: an order was placed
     * but its last-read status is neither COMPLETE nor a definitive failure. This is the
     * 2026-08-04 trade-73 shape — a MARKET order that market-protection converted to a LIMIT sat
     * OPEN past the confirm budget, filled 56s later, and the terminal-FAILED marking meant the
     * fill was never recorded. Such a leg must become PENDING_CLOSE (reconciled later), never
     * FAILED (final).
     */
    public static boolean closeOrderMayBeLive(ExecResult er) {
        if (er == null || er.aggregateOrderIds() == null || er.aggregateOrderIds().isBlank()) return false;
        String t = er.terminalStatus();
        return !ORDER_COMPLETE.equals(t) && !ORDER_REJECTED.equals(t) && !ORDER_CANCELLED.equals(t)
                && !FAILED.equals(t) && !PLACE_FAILED.equals(t) && !OVERFILL.equals(t) && !PARTIAL.equals(t);
    }

    /** Public view of Kite terminal order states (COMPLETE / REJECTED / CANCELLED); null and UNKNOWN are non-terminal. */
    public static boolean isTerminalStatus(String status) {
        return isTerminal(status);
    }

    /** Snapshot of a close order's true broker state: tradebook qty + vwap, and the least-settled history status. */
    public record CloseOrderState(int tradedQty, double vwap, String lastStatus) {}

    /**
     * Reads the authoritative state of a (possibly multi-slice) close order: executed qty and vwap
     * come from the tradebook, status from order history. Across multiple ids the least-settled
     * status wins — one still-working slice makes the whole close non-terminal. Empty history
     * reads as UNKNOWN (non-terminal) so a lagging snapshot is never mistaken for a reject.
     */
    public CloseOrderState readCloseOrderState(String orderIds) {
        List<com.zerodhatech.models.Trade> trades = getOrderTrades(orderIds);
        String status = UNKNOWN;
        boolean sawNonTerminal = false;
        for (String id : orderIds.split(CSV_SPLIT)) {
            if (id.isBlank()) continue;
            List<Order> hist = kiteGateway.getOrderHistory(id.trim());
            String st = (hist == null || hist.isEmpty()) ? UNKNOWN : hist.get(hist.size() - 1).status;
            if (!isTerminal(st)) {
                status = st;
                sawNonTerminal = true;
            } else if (!sawNonTerminal) {
                status = st;
            }
        }
        return new CloseOrderState(tradedQty(trades), weightedAvgFillPrice(trades), status);
    }

    /**
     * Requests cancellation of every slice of a working order. Returns true only when Kite
     * accepted every request; a rejected or failed request (already terminal, modify still
     * settling, transient error) is logged and leaves that slice possibly working, so the caller
     * MUST confirm the order's settled state before acting on it.
     */
    public boolean cancelCloseOrders(String orderIds) {
        boolean allAccepted = true;
        for (String id : orderIds.split(CSV_SPLIT)) {
            if (id.isBlank()) continue;
            boolean accepted;
            try {
                accepted = kiteGateway.cancelOrder(id.trim(), Constants.VARIETY_REGULAR);
            } catch (Exception e) {
                log.warn("cancelCloseOrders: cancel threw for orderId={}: {}", id, e.getMessage());
                accepted = false;
            }
            if (!accepted) {
                log.warn("cancelCloseOrders: cancel request for orderId={} NOT accepted by Kite — the order may still be "
                        + "working; confirm its state before acting on it", id);
                allAccepted = false;
            }
        }
        return allAccepted;
    }

    /**
     * Polls a close order after a cancel request until it settles terminal, returning its TRUE
     * final fill — the same cancel-vs-fill discipline as confirmTerminalFill, applied to the
     * pending-close path so a flatten decision is never sized from an in-flight snapshot.
     */
    public CloseOrderState confirmCloseOrderSettled(String orderIds) {
        CloseOrderState s = readCloseOrderState(orderIds);
        for (int i = 0; i < CANCEL_CONFIRM_POLLS && !isTerminal(s.lastStatus()); i++) {
            sleepMillis(CANCEL_CONFIRM_GAP_MS);
            s = readCloseOrderState(orderIds);
        }
        return s;
    }

    /**
     * The reconcilers' signal-time settle: cancel every slice and confirm the aggregate settled
     * state, re-issuing the cancel up to CANCEL_ATTEMPTS times while any slice is still working.
     * Returns the last read; a non-terminal result means the order could NOT be settled and the
     * leg must stay PENDING — settling it as failed/orphan would let a late fill become an
     * untracked broker position (open side) or a double-sell past flat (close side).
     */
    public CloseOrderState cancelAndSettleCloseOrders(String orderIds) {
        CloseOrderState s = null;
        for (int attempt = 1; attempt <= CANCEL_ATTEMPTS; attempt++) {
            boolean accepted = cancelCloseOrders(orderIds);
            s = confirmCloseOrderSettled(orderIds);
            if (isTerminal(s.lastStatus())) return s;
            log.warn("cancelAndSettleCloseOrders: orderIds={} attempt {}/{} ({}) — still {} ({} traded) after the confirm budget",
                    orderIds, attempt, CANCEL_ATTEMPTS, accepted ? "cancel accepted" : "cancel REJECTED", s.lastStatus(), s.tradedQty());
        }
        return s;
    }

    /**
     * Σ tradedQuantity across an order's tradebook — authoritative executed qty.
     * <p>A malformed quantity string is skipped rather than failing the sum.
     */
    private static int tradedQty(List<com.zerodhatech.models.Trade> trades) {
        if (trades == null) return 0;
        int sum = 0;
        for (com.zerodhatech.models.Trade t : trades) {
            if (t == null || t.quantity == null) continue;
            try { sum += (int) Math.round(Double.parseDouble(t.quantity.trim())); }
            catch (NumberFormatException e) { }
        }
        return sum;
    }

    /** Top-of-book {mid, halfSpread, bid, ask} from a quote, or null if depth is missing/unusable. */
    /**
     * Effective spread paid on a fill, in points per unit: how far the achieved average
     * fill sits from the quote midpoint at order time, signed so paying up is positive
     * (BUY above mid / SELL below mid; a negative value is price improvement).
     * Measurement only — unlike midHalfSpread there is no wide-book gate, because an
     * illiquid book is exactly what the monthly liquidity guard needs to see recorded.
     * Null when depth or the fill is unusable.
     */
    public static Double effectiveSpreadPaid(Quote q, String fillSide, double avgFillPrice) {
        if (q == null || q.depth == null
            || q.depth.buy == null || q.depth.buy.isEmpty()
            || q.depth.sell == null || q.depth.sell.isEmpty()
            || avgFillPrice <= 0) return null;
        double bid = q.depth.buy.get(0).getPrice();
        double ask = q.depth.sell.get(0).getPrice();
        if (bid <= 0 || ask <= 0 || ask < bid) return null;
        double mid = (bid + ask) / 2.0;
        return ComputeUtil.rnd(BUY.equals(fillSide) ? avgFillPrice - mid : mid - avgFillPrice);
    }

    /** Monthly liquidity guard: an excessive spread on a LONG_MONTHLY fill is loud — plan §3.3.2 says re-evaluate the book's economics at this size. */
    public static void alertIfMonthlySpreadExcessive(String book, String instrument, String phase, Double spreadPaid) {
        if (path.to._40c.nqCore.util.Constants.LONG_MONTHLY.equals(book)
                && spreadPaid != null && spreadPaid > path.to._40c.nqCore.util.Constants.MONTHLY_SPREAD_ALERT_PTS) {
            log.error("MONTHLY LIQUIDITY ALERT: {} {} paid {} pts/side vs mid (threshold {}) — "
                    + "monthly spread cost at this size needs re-evaluation",
                instrument, phase, spreadPaid, path.to._40c.nqCore.util.Constants.MONTHLY_SPREAD_ALERT_PTS);
        }
    }

    private static double[] midHalfSpread(Quote q) {
        if (q == null || q.depth == null
            || q.depth.buy == null || q.depth.buy.isEmpty()
            || q.depth.sell == null || q.depth.sell.isEmpty()) return null;
        double bid = q.depth.buy.get(0).getPrice();
        double ask = q.depth.sell.get(0).getPrice();
        if (bid <= 0 || ask <= 0 || ask < bid || (ask - bid) > (ask + bid) * 0.05) return null;
        return new double[]{ (bid + ask) / 2.0, (ask - bid) / 2.0, bid, ask };
    }

    /** Fresh top-of-book quote for the walk chase; null on any failure so the caller keeps its last-known reference. */
    private Quote refreshQuote(String ins) {
        String key = Constants.EXCHANGE_NFO + ":" + ins;
        try {
            Map<String, Quote> m = kiteGateway.getQuote(new String[]{key});
            if (m == null || m.isEmpty()) return null;
            Quote qq = m.get(key);
            return qq != null ? qq : m.values().iterator().next();
        } catch (Exception e) {
            log.warn("walk: quote refresh failed for {} — keeping last-known reference: {}", ins, e.getMessage());
            return null;
        }
    }

    /** Single-shot status read (no retry loop) — used inside the LIMIT walk where the loop itself is the retry. */
    private AttemptResult peekOrderState(String orderId) {
        List<Order> history = kiteGateway.getOrderHistory(orderId);
        if (history == null || history.isEmpty()) return new AttemptResult(orderId, 0, 0.0, UNKNOWN);
        Order last = history.get(history.size() - 1);
        return new AttemptResult(orderId, parseIntSafe(last.filledQuantity), parseDoubleSafe(last.averagePrice), last.status);
    }

    /**
     * Rounds price to the nearest tick, then re-rounds to 2 decimals: tick multiples have
     * ≤2 decimals, but the binary-float product carries artifacts
     * (1842 * 0.05 = 92.10000000000001) into the price sent to Kite.
     */
    private static double roundToTick(double price, double tick) {
        return Math.round(Math.round(price / tick) * tick * 100.0) / 100.0;
    }

    /** Fallback for qty >= MAX_SIZE_PER_ORDER — Kite broker-side auto-slice using MARKET orders. */
    private ExecResult autoSliceFallback(String ins, String txn, int qty, String contextLabel) {
        ExecTrace trace = EXEC_TRACE.get();
        List<BulkOrderResponse> bulk = placeAutoSliceOrder(ins, 0.0, txn, qty);
        if (bulk == null || bulk.isEmpty()) {
            trace.record("auto-slice returned no orders -> FAILED");
            return new ExecResult("", 0, qty, 0.0, false, FAILED);
        }
        StringBuilder ids = new StringBuilder();
        int totalFilled = 0;
        double weightedSum = 0.0;
        for (BulkOrderResponse b : bulk) {
            if (b.orderId == null) continue;
            AttemptResult ar = verifyAttempt(b.orderId, ins, contextLabel);
            if (ar.filledQty() > 0) {
                appendId(ids, b.orderId);
                totalFilled += ar.filledQty();
                weightedSum += ar.filledQty() * ar.avgFillPrice();
            }
            trace.record("slice orderId=%s %s filled %d avg=%s", b.orderId, ar.status(), ar.filledQty(), ar.avgFillPrice());
        }
        double avg = totalFilled > 0 ? Math.round((weightedSum / totalFilled) * 100.0) / 100.0 : 0.0;
        boolean full = totalFilled == qty;
        String term = full ? ORDER_COMPLETE : (totalFilled > 0 ? PARTIAL : FAILED);
        trace.record("autoslice %s %d/%d avg=%s", term, totalFilled, qty, avg);
        return new ExecResult(ids.toString(), totalFilled, qty, avg, full, term);
    }

    /**
     * Reads getOrderHistory; if status is non-terminal, retries up to 2× with 200ms gaps
     * (MARKET normally settles within ms but Kite's order-state propagation can lag).
     */
    private AttemptResult verifyAttempt(String orderId, String ins, String contextLabel) {
        Order last = null;
        for (int i = 0; i < 3; i++) {
            List<Order> history = kiteGateway.getOrderHistory(orderId);
            if (history != null && !history.isEmpty()) {
                last = history.get(history.size() - 1);
                if (isTerminal(last.status)) break;
            }
            sleepMillis(200);
        }
        if (last == null) {
            log.error("orderId={} getOrderHistory empty after retries", orderId);
            return new AttemptResult(orderId, 0, 0.0, UNKNOWN);
        }
        int filledQty = parseIntSafe(last.filledQuantity);
        double avgPrice = parseDoubleSafe(last.averagePrice);
        log.debug("orderId={} status={} filled={}/{} avgPx={} statusMsg={}",
                orderId, last.status, last.filledQuantity, last.quantity, avgPrice, last.statusMessage);
        return new AttemptResult(orderId, filledQty, avgPrice, last.status);
    }

    private static boolean isTerminal(String status) {
        return ORDER_COMPLETE.equals(status)
            || ORDER_REJECTED.equals(status)
            || ORDER_CANCELLED.equals(status);
    }

    private static OrderParams buildAggressiveOrderParams() {
        OrderParams p = new OrderParams();
        p.product  = Constants.PRODUCT_NRML;
        p.exchange = Constants.EXCHANGE_NFO;
        return p;
    }

    private static final java.util.concurrent.atomic.AtomicInteger PATIENT_TAG_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /** Settle time before reading the order book after a lost place response, letting a broker-accepted order surface. */
    private static final long PLACE_RECOVERY_SETTLE_MS = 500L;

    /** Unique alphanumeric Kite order tag (≤20 chars) so a lost place response can be re-found in the order book. */
    private static String patientTag() {
        return "nqp" + System.currentTimeMillis() + (PATIENT_TAG_SEQ.getAndIncrement() % 10);
    }

    /**
     * Recovery for a lost placeOrder response in patient mode. A null response may be a timeout
     * with the order ACCEPTED at the broker, so a blind re-place risks a duplicate resting LIMIT
     * (the 2026-06-19 over-fill class). The order book is consulted for the attempt's unique tag:
     * found → adopt that orderId; verified absent → ONE re-place (same tag, so a second lost
     * response is still adoptable); book unreadable → give up WITHOUT re-placing, leaving the
     * possibly-live order findable under the logged tag.
     */
    private OrderResponse recoverLostPatientPlace(OrderParams params, ExecTrace trace) {
        sleepMillis(PLACE_RECOVERY_SETTLE_MS);
        List<Order> book = kiteGateway.getOrders();
        if (book == null) {
            log.error("PATIENT LIMIT place response lost and order book unreadable — NOT re-placing (duplicate risk); "
                    + "a resting {} {} order may be live under tag={}", params.transactionType, params.tradingsymbol, params.tag);
            trace.record("PATIENT place response lost, book unreadable -> no re-place (tag=%s)", params.tag);
            return null;
        }
        OrderResponse adopted = findByTag(book, params.tag, params.tradingsymbol);
        if (adopted != null) {
            log.warn("PATIENT LIMIT place response lost but order book shows orderId={} for tag={} — adopting, not re-placing",
                    adopted.orderId, params.tag);
            trace.record("PATIENT place response lost -> adopted orderId=%s via tag", adopted.orderId);
            return adopted;
        }
        trace.record("PATIENT place response lost, tag=%s verified absent -> one re-place", params.tag);
        OrderResponse resp = kiteGateway.placeOrder(params, Constants.VARIETY_REGULAR);
        if (resp != null && resp.orderId != null) return resp;
        sleepMillis(PLACE_RECOVERY_SETTLE_MS);
        book = kiteGateway.getOrders();
        return book == null ? null : findByTag(book, params.tag, params.tradingsymbol);
    }

    /**
     * The order-book entry carrying this placement attempt's tag (newest first), as an adoptable
     * OrderResponse; null when absent. Status is deliberately NOT filtered — adopting a REJECTED
     * order lets the normal confirm path read its terminal state honestly.
     */
    private static OrderResponse findByTag(List<Order> book, String tag, String ins) {
        for (int i = book.size() - 1; i >= 0; i--) {
            Order o = book.get(i);
            if (tag != null && tag.equals(o.tag) && ins.equals(o.tradingSymbol)) {
                OrderResponse r = new OrderResponse();
                r.orderId = o.orderId;
                return r;
            }
        }
        return null;
    }

    private static void appendId(StringBuilder sb, String id) {
        if (id == null) return;
        if (sb.length() > 0) sb.append(", ");
        sb.append(id);
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static double parseDoubleSafe(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0.0; }
    }

    static void sleepMillis(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public record ExecResult(String aggregateOrderIds, int totalFilled, int totalRequested,
                             double weightedAvgFillPrice, boolean fullyFilled, String terminalStatus) {}

    private record AttemptResult(String orderId, int filledQty, double avgFillPrice, String status) {}

    /** MDC key carrying the per-leg log tag ("&lt;context&gt;:&lt;instrument&gt; | "), rendered via %X{leg} in logback. */
    private static final String MDC_LEG_KEY = "leg";

    /**
     * Per-leg ExecTrace, bound by placeAggressiveOrder for the duration of one leg's execution so
     * the private pipeline methods (LIMIT walk, MARKET core, auto-slice, fill confirms) append to
     * the same trace without it being threaded through every signature. Legs execute on separate
     * virtual threads, so bindings never overlap; reading outside a binding throws
     * NoSuchElementException, which flags a call path that bypassed placeAggressiveOrder.
     */
    private static final ScopedValue<ExecTrace> EXEC_TRACE = ScopedValue.newInstance();

    /**
     * Per-leg execution trace: accumulates step lines with +ms offsets during one
     * placeAggressiveOrder call and renders them as a single multi-line block, so the
     * complete story of each leg appears contiguously in the log even when several legs
     * execute in parallel. Reaches the pipeline methods via the EXEC_TRACE scoped value.
     * Confined to the leg's own thread — not thread-safe by design.
     */
    private static final class ExecTrace {
        private final long t0 = System.currentTimeMillis();
        private final StringBuilder block = new StringBuilder();

        /** Records the pre-placement quote snapshot (no time offset — it anchors the walk). */
        void quote(String fmt, Object... args) {
            add("quote", fmt, args);
        }

        /** Records one execution step, stamped with milliseconds elapsed since leg start. */
        void record(String fmt, Object... args) {
            add("+" + (System.currentTimeMillis() - t0) + "ms", fmt, args);
        }

        long elapsedMs() {
            return System.currentTimeMillis() - t0;
        }

        String render() {
            return block.toString();
        }

        private void add(String label, String fmt, Object... args) {
            block.append(System.lineSeparator())
                 .append("    ").append(String.format("%-9s ", label))
                 .append(String.format(fmt, args));
        }
    }

    /**
     * NIFTY tradingsymbols for the UI autocomplete. Far-dated LEAPS are dropped by their
     * actual expiry (> ~1 year out), NOT by tradingsymbol prefix — the old
     * startsWith("NIFTY27".."NIFTY30") hack also killed every weekly/monthly contract
     * expiring on the 27th-30th of a month, and would have dropped ALL contracts from
     * January 2027 onward.
     */
    public List<String> getNiftyInstruments() {
        java.time.LocalDate horizon = java.time.LocalDate.now(ZoneId.of(ZONE_ID)).plusDays(370);
        return kiteGateway.getInstruments(NFO).stream()
            .filter(i -> i.expiry == null || !i.expiry.toInstant().atZone(ZoneId.of(ZONE_ID)).toLocalDate().isAfter(horizon))
            .map(i -> i.tradingsymbol)
            .filter(symbol -> symbol.contains(NIFTY))
            .filter(symbol -> !symbol.contains("MIDCPNIFTY"))
            .filter(symbol -> !symbol.contains("BANKNIFTY"))
            .filter(symbol -> !symbol.contains("NIFTYNXT"))
            .filter(symbol -> !symbol.contains("FINNIFTY"))
            .toList();
    }

    public static OrderParams buildOrderParams() {
        OrderParams orderParams = new OrderParams();
        orderParams.orderType = Constants.ORDER_TYPE_MARKET;
        orderParams.product = Constants.PRODUCT_NRML;
        orderParams.exchange = Constants.EXCHANGE_NFO;
        orderParams.validity = Constants.VALIDITY_DAY;
        orderParams.marketProtection = 1;
        return orderParams;
    }

    /**
     * The latest position holding LIVE legs of the given book, with only LIVE legs loaded
     * (the other book's LIVE legs load too — callers select their own via LegScope.of and
     * must never touch the rest). Row status is NOT consulted: one shared row can be LIVE,
     * PARTIAL or PENDING_* on the other book's account while this book's legs are held at
     * the broker and still need closing/rolling.
     */
    @Transactional
    public Position findLiveTradesWithLiveOrderBooks(String book) {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter(LIVE_ORDER_BOOKS).setParameter("status", LIVE);
        List<Position> trades = positionRepository.findByLegStatusAndLegBook(LIVE, book);
        session.disableFilter(LIVE_ORDER_BOOKS);
        return trades.isEmpty() ? null : trades.get(0);
    }

    /**
     * The latest PARTIAL position holding LIVE legs of the given book — an open where only
     * some of the book's legs filled — with only the still-LIVE orphan legs loaded, so the
     * closing path can flatten exactly what is held at the broker.
     */
    @Transactional
    public Position findPartialTradesWithLiveOrderBooks(String book) {
        Session session = entityManager.unwrap(Session.class);
        session.enableFilter(LIVE_ORDER_BOOKS).setParameter("status", LIVE);
        List<Position> trades = positionRepository.findByLegStatusAndLegBook(LIVE, book);
        session.disableFilter(LIVE_ORDER_BOOKS);
        return trades.stream().filter(t -> PARTIAL.equals(t.getStatus())).findFirst().orElse(null);
    }

    /**
     * Every leg of the given book whose order is still unconfirmed at the broker — PENDING_OPEN
     * (an entry that may yet fill) or PENDING_CLOSE (an exit that may yet fill). While any exists
     * the book's true broker position is unknown and no new entry may be placed for it: the
     * 2026-10-07 22650PE orphan was a second order stacked on a LIMIT whose cancel had not
     * landed. Scans by LEG status across all rows — a shared row's status may show the other
     * book's state — and matches the leg's own book stamp, so one book never blocks the other.
     */
    public List<WeeklyLeg> findUnsettledLegs(String book) {
        List<WeeklyLeg> unsettled = new ArrayList<>();
        for (String status : List.of(PENDING_OPEN, PENDING_CLOSE)) {
            for (Position p : positionRepository.findByLegStatus(status)) {
                for (WeeklyLeg l : p.getLegs()) {
                    if (status.equals(l.getStatus()) && book.equals(LegScope.bookOf(l))) unsettled.add(l);
                }
            }
        }
        return unsettled;
    }

    private List<com.zerodhatech.models.Trade> fetchWithRetry(String orderId, String context) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            List<com.zerodhatech.models.Trade> trades = getOrderTrades(orderId);
            if (trades != null && !trades.isEmpty()) return trades;
            if (attempt < maxAttempts) {
                log.warn("Empty fills for orderId={} ({}) — attempt {}/{}, retrying in 10s", orderId, context, attempt, maxAttempts);
                sleep();
            } else {
                log.error("Empty fills for orderId={} ({}) after {} attempts — execution price will default to 0", orderId, context, maxAttempts);
            }
        }
        return new ArrayList<>();
    }

    public static void sleep() {
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted: {}", e.getMessage());
        }
    }
}
