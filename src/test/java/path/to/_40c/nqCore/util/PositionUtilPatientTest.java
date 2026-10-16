package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Depth;
import com.zerodhatech.models.MarketDepth;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.gateway.KiteOrderStream;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * PATIENT execution core (PATIENT_EXECUTION_PLAN.md §3): the thin-monthly-book exit path must
 * rest at a fair-anchored LIMIT, concede slowly within a hard cap, and NEVER panic into MARKET
 * because the spread is wide — the exact inversion of the aggressive walk's 5%-spread MARKET
 * fallback. Deadline semantics hand the resting order to the PENDING_CLOSE machinery via a
 * non-terminal ExecResult (closeOrderMayBeLive contract), or — when configured — finish through
 * the same cancel→confirm→top-up discipline the walk uses (over-fill safety shared via
 * cancelAndTopUp).
 *
 * Scripting follows the PositionUtilOverfillTest conventions: consecutive-return stubbing of
 * getOrderHistory per order id, placeOrder answers keyed on orderType, and a healthy stream whose
 * awaitTerminal resolves immediately so the minutes-scale schedule collapses to ~0 ms.
 */
class PositionUtilPatientTest {

    private static final String OPEN = "OPEN";   // non-terminal Kite order status
    private static final int    QTY  = 130;      // 2 lots × 65 — the LONG_MONTHLY starting size

    private KiteGateway gw;
    private KiteOrderStream stream;
    private PositionUtil util;

    @BeforeEach
    void setUp() {
        gw = mock(KiteGateway.class);
        stream = mock(KiteOrderStream.class);
        util = new PositionUtil(gw, stream, null, null);
        ReflectionTestUtils.setField(util, "useLimitWalk", true);
        patientConfig(true, "10,20,30", "0.0,0.5,1.0", 2.0, 0.5, 2.0, "23:59", "REST");

        when(stream.isHealthy()).thenReturn(true);
        when(stream.awaitTerminal(anyString())).thenReturn(CompletableFuture.completedFuture(new Order()));
        when(gw.modifyOrder(anyString(), anyDouble(), anyInt(), anyString())).thenReturn(true);
        when(gw.cancelOrder(anyString(), anyString())).thenReturn(true);
        when(gw.placeOrder(any(OrderParams.class), anyString())).thenAnswer(inv -> {
            OrderParams p = inv.getArgument(0);
            OrderResponse r = new OrderResponse();
            r.orderId = Constants.ORDER_TYPE_MARKET.equals(p.orderType) ? "MARKET1" : "LIMIT1";
            return r;
        });
    }

    // ─── anchoring ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sane spread: patient LIMIT rests at the midpoint")
    void sellExitRestsAtMidWhenSpreadSane() {
        when(gw.getOrderHistory("LIMIT1")).thenReturn(List.of(order(Constants.ORDER_COMPLETE, QTY, 463.0)));

        ExecResult res = patientSell(quote(462.0, 464.0, 463.10));

        ArgumentCaptor<OrderParams> cap = ArgumentCaptor.forClass(OrderParams.class);
        verify(gw, times(1)).placeOrder(cap.capture(), anyString());
        OrderParams p = cap.getValue();
        assertThat(p.orderType).isEqualTo(Constants.ORDER_TYPE_LIMIT);
        assertThat(p.validity).isEqualTo(Constants.VALIDITY_DAY);
        assertThat(p.quantity).isEqualTo(QTY);
        assertThat(p.price).as("mid of 462/464").isCloseTo(463.0, offset(0.001));

        assertThat(res.fullyFilled()).isTrue();
        assertThat(res.terminalStatus()).isEqualTo(Constants.ORDER_COMPLETE);
        assertThat(res.weightedAvgFillPrice()).isCloseTo(463.0, offset(0.001));
        verify(gw, never()).modifyOrder(anyString(), anyDouble(), anyInt(), anyString());
        verify(gw, never()).cancelOrder(anyString(), anyString());
    }

    /**
     * Deadline REST: order left resting, PENDING_CLOSE contract satisfied.
     */
    @Test
    @DisplayName("wide spread: anchor is LTP clamped into the book — and NO MARKET fallback, ever")
    void wideSpreadAnchorsToClampedLtpAndNeverGoesMarket() {
        when(gw.getOrderHistory("LIMIT1")).thenReturn(List.of(order(OPEN, 0, 0.0)));

        ExecResult res = patientSell(quote(440.0, 490.0, 464.53));

        ArgumentCaptor<OrderParams> cap = ArgumentCaptor.forClass(OrderParams.class);
        verify(gw, times(1)).placeOrder(cap.capture(), anyString());
        assertThat(cap.getValue().orderType).isEqualTo(Constants.ORDER_TYPE_LIMIT);
        assertThat(cap.getValue().price).as("LTP 464.53 tick-rounded, not the noise mid 465").isCloseTo(464.55, offset(0.001));

        assertThat(res.aggregateOrderIds()).isEqualTo("LIMIT1");
        assertThat(res.fullyFilled()).isFalse();
        assertThat(res.terminalStatus()).isEqualTo(OPEN);
        assertThat(PositionUtil.closeOrderMayBeLive(res)).as("must route the leg to PENDING_CLOSE").isTrue();
        verify(gw, never()).cancelOrder(anyString(), anyString());
        verify(gw, never()).placeOrder(argThat(p -> p != null && Constants.ORDER_TYPE_MARKET.equals(p.orderType)), anyString());
        verify(stream).cancel("LIMIT1");
    }

    @Test
    @DisplayName("wide spread with LTP above the ask: anchor clamps to the ask")
    void ltpAboveAskClampsToAsk() {
        when(gw.getOrderHistory("LIMIT1")).thenReturn(List.of(order(Constants.ORDER_COMPLETE, QTY, 490.0)));

        patientSell(quote(440.0, 490.0, 505.0));

        ArgumentCaptor<OrderParams> cap = ArgumentCaptor.forClass(OrderParams.class);
        verify(gw).placeOrder(cap.capture(), anyString());
        assertThat(cap.getValue().price).isCloseTo(490.0, offset(0.001));
    }

    // ─── concession discipline ──────────────────────────────────────────────────────────────

    /**
     * anchor0=463, cap=max(2.0, 463×0.5%)=2.315; fracs {0, .5, 1} → step0 holds at anchor, step1 →
     * 461.85, step2 → 460.70 (floor 460.685 tick-rounded).
     */
    @Test
    @DisplayName("concession walks down by capped fractions and modify always carries the original qty")
    void concessionWalksDownCappedAndKeepsOriginalQty() {
        when(gw.getOrderHistory("LIMIT1")).thenReturn(List.of(order(OPEN, 65, 0.0)));

        ExecResult res = patientSell(quote(462.0, 464.0, 463.0));

        ArgumentCaptor<Double> px = ArgumentCaptor.forClass(Double.class);
        ArgumentCaptor<Integer> q = ArgumentCaptor.forClass(Integer.class);
        verify(gw, times(2)).modifyOrder(anyString(), px.capture(), q.capture(), anyString());
        assertThat(px.getAllValues().get(0)).isCloseTo(461.85, offset(0.001));
        assertThat(px.getAllValues().get(1)).isCloseTo(460.70, offset(0.001));
        assertThat(px.getAllValues()).allSatisfy(v ->
                assertThat(v).as("never concede past anchor0−cap").isGreaterThanOrEqualTo(460.68));
        assertThat(q.getAllValues()).allSatisfy(v ->
                assertThat(v).as("modify keeps original qty (Kite semantics)").isEqualTo(QTY));

        assertThat(res.terminalStatus()).isEqualTo(OPEN);
        assertThat(res.totalFilled()).as("partial fill reported as-is on REST").isEqualTo(65);
        assertThat(PositionUtil.closeOrderMayBeLive(res)).isTrue();
    }

    /**
     * anchor0=463 (healthy first refresh), then fair collapses to 450.5: every reprice pins to the
     * floor 460.685→460.70 instead of following the collapsed book down.
     */
    @Test
    @DisplayName("collapsing fair anchor is clamped to the anchor0−cap band — garbage quotes can't walk the price away")
    void collapsingFairIsClampedToAnchorBand() {
        when(gw.getQuote(any()))
                .thenReturn(Map.of("NFO:NIFTY26AUG23850CE", quote(462.0, 464.0, 463.0)))
                .thenReturn(Map.of("NFO:NIFTY26AUG23850CE", quote(450.0, 451.0, 450.5)));
        when(gw.getOrderHistory("LIMIT1")).thenReturn(List.of(order(OPEN, 0, 0.0)));

        patientSell(quote(462.0, 464.0, 463.0));

        ArgumentCaptor<Double> px = ArgumentCaptor.forClass(Double.class);
        verify(gw, times(1)).modifyOrder(anyString(), px.capture(), anyInt(), anyString());
        assertThat(px.getValue()).isCloseTo(460.70, offset(0.001));
    }

    @Test
    @DisplayName("BUY side concedes upward, symmetrically capped")
    void buyExitConcedesUpwardWithCap() {
        when(gw.getOrderHistory("LIMIT1")).thenReturn(List.of(order(OPEN, 0, 0.0)));

        util.placeAggressiveOrder(quote(462.0, 464.0, 463.0), "NIFTY26AUG23850CE",
                Constants.TRANSACTION_TYPE_BUY, QTY, "EXIT", ExecMode.PATIENT);

        ArgumentCaptor<Double> px = ArgumentCaptor.forClass(Double.class);
        verify(gw, times(2)).modifyOrder(anyString(), px.capture(), anyInt(), anyString());
        assertThat(px.getAllValues().get(0)).isCloseTo(464.15, offset(0.001));
        assertThat(px.getAllValues().get(1)).isCloseTo(465.30, offset(0.001));
        assertThat(px.getAllValues()).allSatisfy(v -> assertThat(v).isLessThanOrEqualTo(465.32));
    }

    // ─── fills and deadline semantics ───────────────────────────────────────────────────────

    @Test
    @DisplayName("fill mid-schedule returns COMPLETE with the true weighted average")
    void fillMidScheduleReturnsComplete() {
        when(gw.getOrderHistory("LIMIT1"))
                .thenReturn(List.of(order(OPEN, 0, 0.0)))
                .thenReturn(List.of(order(OPEN, 65, 462.9)))
                .thenReturn(List.of(order(Constants.ORDER_COMPLETE, QTY, 462.8)));

        ExecResult res = patientSell(quote(462.0, 464.0, 463.0));

        assertThat(res.fullyFilled()).isTrue();
        assertThat(res.totalFilled()).isEqualTo(QTY);
        assertThat(res.weightedAvgFillPrice()).isCloseTo(462.8, offset(0.001));
        assertThat(res.aggregateOrderIds()).isEqualTo("LIMIT1");
        verify(gw, times(1)).modifyOrder(anyString(), anyDouble(), anyInt(), anyString());
        verify(gw, never()).cancelOrder(anyString(), anyString());
    }

    @Test
    @DisplayName("deadline-action MARKET: cancel is confirmed BEFORE the top-up is sized (over-fill discipline)")
    void deadlineMarketUsesCancelConfirmDiscipline() {
        patientConfig(true, "10,20,30", "0.0,0.5,1.0", 2.0, 0.5, 2.0, "23:59", "MARKET");
        when(gw.getOrderHistory("LIMIT1"))
                .thenReturn(List.of(order(OPEN, 0, 0.0)))
                .thenReturn(List.of(order(OPEN, 65, 464.0)))
                .thenReturn(List.of(order(OPEN, 65, 464.0)))
                .thenReturn(List.of(order(OPEN, 65, 464.0)))
                .thenReturn(List.of(order(Constants.ORDER_CANCELLED, 65, 464.0)));
        when(gw.getOrderHistory("MARKET1")).thenReturn(
                List.of(order(Constants.ORDER_COMPLETE, 65, 462.0)));

        ExecResult res = patientSell(quote(462.0, 464.0, 463.0));

        ArgumentCaptor<OrderParams> cap = ArgumentCaptor.forClass(OrderParams.class);
        verify(gw, times(2)).placeOrder(cap.capture(), anyString());
        OrderParams market = cap.getAllValues().stream()
                .filter(p -> Constants.ORDER_TYPE_MARKET.equals(p.orderType))
                .findFirst().orElseThrow(() -> new AssertionError("no MARKET top-up placed"));
        assertThat(market.quantity).as("sized from the CONFIRMED 65, remainder 130−65").isEqualTo(65);

        InOrder order = inOrder(gw);
        order.verify(gw).cancelOrder("LIMIT1", Constants.VARIETY_REGULAR);
        order.verify(gw).placeOrder(argThat(p -> Constants.ORDER_TYPE_MARKET.equals(p.orderType)), anyString());

        assertThat(res.totalFilled()).isEqualTo(QTY);
        assertThat(res.fullyFilled()).isTrue();
        assertThat(res.weightedAvgFillPrice()).as("65@464 + 65@462").isCloseTo(463.0, offset(0.001));
        assertThat(res.aggregateOrderIds()).isEqualTo("LIMIT1, MARKET1");
    }

    @Test
    @DisplayName("place response lost with the order book unreadable: PLACE_FAILED after ONE place — never a blind re-place, never MARKET")
    void placeFailedNoMarketFallback() {
        when(gw.placeOrder(any(OrderParams.class), anyString())).thenReturn(null);
        when(gw.getOrders()).thenReturn(null);

        ExecResult res = patientSell(quote(462.0, 464.0, 463.0));

        ArgumentCaptor<OrderParams> cap = ArgumentCaptor.forClass(OrderParams.class);
        verify(gw, times(1)).placeOrder(cap.capture(), anyString());
        assertThat(cap.getValue().orderType).isEqualTo(Constants.ORDER_TYPE_LIMIT);
        assertThat(res.terminalStatus()).isEqualTo("PLACE_FAILED");
        assertThat(res.aggregateOrderIds()).isEmpty();
        assertThat(res.totalFilled()).isZero();
    }

    @Test
    @DisplayName("place response lost but the tagged order is in the book: adopted, never re-placed")
    void lostPlaceResponseAdoptsTaggedOrder() {
        java.util.concurrent.atomic.AtomicReference<String> tag = new java.util.concurrent.atomic.AtomicReference<>();
        when(gw.placeOrder(any(OrderParams.class), anyString())).thenAnswer(inv -> {
            tag.set(((OrderParams) inv.getArgument(0)).tag);
            return null;
        });
        when(gw.getOrders()).thenAnswer(inv -> {
            Order o = order(Constants.ORDER_COMPLETE, QTY, 463.0);
            o.orderId = "LIMIT1";
            o.tradingSymbol = "NIFTY26AUG23850CE";
            o.tag = tag.get();
            return List.of(o);
        });
        when(gw.getOrderHistory("LIMIT1")).thenReturn(List.of(order(Constants.ORDER_COMPLETE, QTY, 463.0)));

        ExecResult res = patientSell(quote(462.0, 464.0, 463.0));

        verify(gw, times(1)).placeOrder(any(OrderParams.class), anyString());
        assertThat(tag.get()).as("Kite tag contract: alphanumeric, max 20 chars").matches("[A-Za-z0-9]{1,20}");
        assertThat(res.aggregateOrderIds()).isEqualTo("LIMIT1");
        assertThat(res.fullyFilled()).isTrue();
    }

    @Test
    @DisplayName("place response lost and the book verifiably lacks the tag: exactly one verified re-place")
    void lostPlaceResponseVerifiedAbsentReplacesOnce() {
        java.util.concurrent.atomic.AtomicInteger placeCalls = new java.util.concurrent.atomic.AtomicInteger();
        when(gw.placeOrder(any(OrderParams.class), anyString())).thenAnswer(inv -> {
            if (placeCalls.getAndIncrement() == 0) return null;
            OrderResponse r = new OrderResponse();
            r.orderId = "LIMIT1";
            return r;
        });
        when(gw.getOrders()).thenReturn(List.of());
        when(gw.getOrderHistory("LIMIT1")).thenReturn(List.of(order(Constants.ORDER_COMPLETE, QTY, 463.0)));

        ExecResult res = patientSell(quote(462.0, 464.0, 463.0));

        verify(gw, times(2)).placeOrder(any(OrderParams.class), anyString());
        assertThat(res.aggregateOrderIds()).isEqualTo("LIMIT1");
        assertThat(res.fullyFilled()).isTrue();
    }

    // ─── routing gates ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("patient disabled: PATIENT request degrades to the walk, whose wide-spread gate goes MARKET")
    void patientDisabledDegradesToAggressive() {
        patientConfig(false, "10,20,30", "0.0,0.5,1.0", 2.0, 0.5, 2.0, "23:59", "REST");
        when(gw.getOrderHistory("MARKET1")).thenReturn(List.of(order(Constants.ORDER_COMPLETE, QTY, 460.0)));

        ExecResult res = patientSell(quote(440.0, 490.0, 464.53));

        verify(gw).placeOrder(argThat(p -> Constants.ORDER_TYPE_MARKET.equals(p.orderType)), anyString());
        assertThat(res.fullyFilled()).isTrue();
    }

    @Test
    @DisplayName("past the IST cutoff: PATIENT request degrades to aggressive")
    void cutoffPassedDegradesToAggressive() {
        patientConfig(true, "10,20,30", "0.0,0.5,1.0", 2.0, 0.5, 2.0, "00:00", "REST");
        when(gw.getOrderHistory("MARKET1")).thenReturn(List.of(order(Constants.ORDER_COMPLETE, QTY, 460.0)));

        assertThat(util.patientModeAvailable()).isFalse();
        patientSell(quote(440.0, 490.0, 464.53));

        verify(gw).placeOrder(argThat(p -> Constants.ORDER_TYPE_MARKET.equals(p.orderType)), anyString());
    }

    @Test
    @DisplayName("invalid config (length mismatch) disables patient mode instead of failing")
    void invalidConfigDisablesPatient() {
        patientConfig(true, "10,20,30", "0.0,0.5", 2.0, 0.5, 2.0, "23:59", "REST");
        when(gw.getOrderHistory("MARKET1")).thenReturn(List.of(order(Constants.ORDER_COMPLETE, QTY, 460.0)));

        assertThat(util.patientModeAvailable()).isFalse();
        patientSell(quote(440.0, 490.0, 464.53));

        verify(gw).placeOrder(argThat(p -> Constants.ORDER_TYPE_MARKET.equals(p.orderType)), anyString());
    }

    @Test
    @DisplayName("legacy 5-arg overload is always AGGRESSIVE even with patient fully enabled")
    void fiveArgOverloadNeverPatient() {
        when(gw.getOrderHistory("MARKET1")).thenReturn(List.of(order(Constants.ORDER_COMPLETE, QTY, 460.0)));

        util.placeAggressiveOrder(quote(440.0, 490.0, 464.53), "NIFTY26AUG23850CE",
                Constants.TRANSACTION_TYPE_SELL, QTY, "EXIT");

        verify(gw).placeOrder(argThat(p -> Constants.ORDER_TYPE_MARKET.equals(p.orderType)), anyString());
    }

    @Test
    @DisplayName("no quote and no LTP at all: the one legitimate aggressive fallback")
    void noQuoteNoLtpDegradesToAggressive() {
        when(gw.getOrderHistory("MARKET1")).thenReturn(List.of(order(Constants.ORDER_COMPLETE, QTY, 460.0)));

        ExecResult res = util.placeAggressiveOrder(null, "NIFTY26AUG23850CE",
                Constants.TRANSACTION_TYPE_SELL, QTY, "EXIT", ExecMode.PATIENT);

        verify(gw).placeOrder(argThat(p -> Constants.ORDER_TYPE_MARKET.equals(p.orderType)), anyString());
        assertThat(res.fullyFilled()).isTrue();
    }

    // ─── helpers ────────────────────────────────────────────────────────────────────────────

    private ExecResult patientSell(Quote q) {
        return util.placeAggressiveOrder(q, "NIFTY26AUG23850CE",
                Constants.TRANSACTION_TYPE_SELL, QTY, "EXIT", ExecMode.PATIENT);
    }

    private void patientConfig(boolean enabled, String delays, String fracs, double pts, double pct,
            double sane, String cutoff, String action) {
        ReflectionTestUtils.setField(util, "patientEnabled", enabled);
        ReflectionTestUtils.setField(util, "patientStepDelaysRaw", delays);
        ReflectionTestUtils.setField(util, "patientConcessionFractionsRaw", fracs);
        ReflectionTestUtils.setField(util, "patientMaxConcessionPts", pts);
        ReflectionTestUtils.setField(util, "patientMaxConcessionPct", pct);
        ReflectionTestUtils.setField(util, "patientSaneSpreadPct", sane);
        ReflectionTestUtils.setField(util, "patientCutoffRaw", cutoff);
        ReflectionTestUtils.setField(util, "patientDeadlineAction", action);
        util.initPatientConfig();
    }

    private static Order order(String status, int filled, double avgPrice) {
        Order o = new Order();
        o.status = status;
        o.filledQuantity = String.valueOf(filled);
        o.pendingQuantity = String.valueOf(QTY - filled);
        o.quantity = String.valueOf(QTY);
        o.averagePrice = String.valueOf(avgPrice);
        return o;
    }

    private static Quote quote(double bid, double ask, double ltp) {
        Quote q = new Quote();
        q.lastPrice = ltp;
        Depth b = new Depth();
        b.setPrice(bid);
        b.setQuantity(1000);
        Depth s = new Depth();
        s.setPrice(ask);
        s.setQuantity(1000);
        MarketDepth d = new MarketDepth();
        d.buy = List.of(b);
        d.sell = List.of(s);
        q.depth = d;
        return q;
    }
}
