package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
 * Regression guard for the 2026-06-19 over-fill: the SwingMaster 14:15 flip intended to buy 650
 * on the CE leg but actually settled 975. Root cause was a cancel-vs-fill race in the graduated
 * LIMIT walk — the MARKET top-up that finishes a walk-exhausted leg was sized from the LIMIT's
 * fill count read BEFORE the (asynchronous) cancel landed. The crossing LIMIT kept filling in
 * that window, so the LIMIT settled more than the snapshot showed and the MARKET added the stale
 * remainder on top: real position = 520 (LIMIT) + 455 (MARKET) = 975 instead of 650.
 *
 * The fix in {@link PositionUtil#placeGraduatedLimit} confirms the cancel reached a terminal
 * state, re-reads the LIMIT's true settled fill, and sizes the top-up from that. These tests
 * drive the exact 2026-06-19 fill sequence through mocked Kite gateway/stream and assert the
 * MARKET top-up is sized from the confirmed post-cancel fill — never from the pre-cancel snapshot.
 */
class PositionUtilOverfillTest {

    private static final String OPEN = "OPEN"; // non-terminal Kite order status
    private static final int    QTY  = 650;    // intended position (10 lots × 65)

    private KiteGateway gw;
    private KiteOrderStream stream;
    private PositionUtil util;

    /**
     * Production path: graduated LIMIT walk (mid → walk → MARKET fallback).
     * <p>Stream is healthy and every awaitTerminal resolves immediately, so the walk's timed steps
     * don't actually sleep — the REST getOrderHistory mock remains the source of truth.
     * <p>LIMIT placement returns LIMIT1; the MARKET fallback returns MARKET1 — keyed off orderType
     * so getOrderHistory can be scripted per order id.
     */
    @BeforeEach
    void setUp() {
        gw = mock(KiteGateway.class);
        stream = mock(KiteOrderStream.class);
        util = new PositionUtil(gw, stream, null, null);
        ReflectionTestUtils.setField(util, "useLimitWalk", true);

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

    /**
     * The core regression: walk exhausts with a stale snapshot of 195, but the LIMIT actually
     * settles 520 once the cancel lands. The MARKET top-up MUST be sized to 650−520=130 (giving a
     * true position of 650), NOT to the buggy 650−195=455 (which produced 520+455=975).
     * <p>Reads in order: 1-3) walk steps: OPEN 65 → OPEN 130 → OPEN 130 4) post-walk peek: OPEN 195
     * (the stale snapshot the bug trusted) 5) post-cancel confirm: CANCELLED 520 (the TRUE settled
     * fill)
     * <p>The MARKET top-up fills exactly what it is asked for (broker fills MARKET fully). With the
     * fix it is asked for 130 and fills 130.
     * <p>Exactly two placements: the LIMIT and one MARKET top-up.
     * <p>The decisive assertion: the top-up is sized from the confirmed 520, not the stale 195.
     * Pre-fix this was 455.
     * <p>True settled position = confirmed LIMIT fill + MARKET fill (= MARKET requested, fully
     * filled).
     * <p>The cancel must have been confirmed against the broker before sizing the top-up.
     */
    @Test
    @DisplayName("walk-exhausted MARKET top-up is sized from the LIMIT's confirmed post-cancel fill (650, not 975)")
    void marketTopUpSizedFromConfirmedCancelFillNotPreCancelSnapshot() {
        when(gw.getOrderHistory("LIMIT1"))
                .thenReturn(List.of(order(OPEN, 65,  97.95)))
                .thenReturn(List.of(order(OPEN, 130, 97.97)))
                .thenReturn(List.of(order(OPEN, 130, 97.97)))
                .thenReturn(List.of(order(OPEN, 195, 97.98)))
                .thenReturn(List.of(order(Constants.ORDER_CANCELLED, 520, 98.10)));

        when(gw.getOrderHistory("MARKET1")).thenReturn(
                List.of(order(Constants.ORDER_COMPLETE, 130, 98.20)));

        ExecResult res = util.placeAggressiveOrder(
                quote(97.80, 98.05), "NIFTY2662324050CE", Constants.TRANSACTION_TYPE_BUY, QTY, "ENTRY");

        ArgumentCaptor<OrderParams> cap = ArgumentCaptor.forClass(OrderParams.class);
        verify(gw, times(2)).placeOrder(cap.capture(), anyString());
        OrderParams marketParams = cap.getAllValues().stream()
                .filter(p -> Constants.ORDER_TYPE_MARKET.equals(p.orderType))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no MARKET top-up was placed"));

        assertThat(marketParams.quantity)
                .as("MARKET top-up must cover 650−520=130, NOT the buggy 650−195=455")
                .isEqualTo(130);

        int truePosition = 520 + marketParams.quantity;
        assertThat(truePosition)
                .as("true settled position must equal the 650 intended — no over-fill")
                .isEqualTo(QTY);

        assertThat(res.totalFilled()).as("reported fill").isEqualTo(QTY);
        assertThat(res.fullyFilled()).isTrue();
        verify(gw).cancelOrder("LIMIT1", Constants.VARIETY_REGULAR);
    }

    /**
     * If the LIMIT turns out to have FULLY filled by the time the cancel lands, no MARKET top-up
     * may be placed at all. Pre-fix, the stale snapshot (here 600) would have driven a spurious
     * 50-lot MARKET on top of an already-complete 650, over-filling to 700.
     * <p>At <code>.thenReturn(List.of(order(OPEN, 600, 98.00)))</code>: stale snapshot
     * <p>At <code>.thenReturn(List.of(order(Constants.ORDER_CANCELLED, 650,...</code>: actually
     * fully filled
     * <p>At <code>verify(gw, times(1)).placeOrder(any(OrderParams.class), a...</code>: LIMIT only
     */
    @Test
    @DisplayName("no MARKET top-up when the cancelled LIMIT turns out fully filled")
    void noTopUpWhenCancelledLimitTurnsOutFullyFilled() {
        when(gw.getOrderHistory("LIMIT1"))
                .thenReturn(List.of(order(OPEN, 200, 97.95)))
                .thenReturn(List.of(order(OPEN, 400, 97.97)))
                .thenReturn(List.of(order(OPEN, 500, 97.99)))
                .thenReturn(List.of(order(OPEN, 600, 98.00)))
                .thenReturn(List.of(order(Constants.ORDER_CANCELLED, 650, 98.05)));

        ExecResult res = util.placeAggressiveOrder(
                quote(97.80, 98.05), "NIFTY2662324050CE", Constants.TRANSACTION_TYPE_BUY, QTY, "ENTRY");

        verify(gw, times(1)).placeOrder(any(OrderParams.class), anyString());
        verify(gw, never()).placeOrder(
                argThat(p -> p != null && Constants.ORDER_TYPE_MARKET.equals(p.orderType)), anyString());
        assertThat(res.totalFilled()).isEqualTo(QTY);
        assertThat(res.fullyFilled()).isTrue();
    }

    /**
     * The 2026-10-07 NIFTY26OCT22650PE double-buy, replayed: Kite rejects the cancel (HTTP 400
     * "An internal error occurred") and the LIMIT stays OPEN through every cancel attempt. The
     * old code placed a full-size MARKET anyway and dropped the LIMIT's id; the LIMIT filled
     * later and the extra lots sat untracked at the broker for seven sessions. Now: NO MARKET
     * order, the cancel is retried, and the result keeps the LIMIT id with its non-terminal
     * status so the open/close services park the leg PENDING_* for the reconciler.
     * <p>At <code>verify(gw, times(1)).placeOrder(any(OrderParams.class), a...</code>: the LIMIT,
     * nothing else
     */
    @Test
    @DisplayName("2026-10-07 replay: cancel rejected, LIMIT still OPEN → NO MARKET top-up, LIMIT id kept as possibly-live")
    void noTopUpWhenCancelNeverConfirms() {
        when(gw.cancelOrder(anyString(), anyString())).thenReturn(false);
        when(gw.getOrderHistory("LIMIT1")).thenReturn(List.of(order(OPEN, 0, 0.0)));

        ExecResult res = util.placeAggressiveOrder(
                quote(285.0, 286.05), "NIFTY26OCT22650PE", Constants.TRANSACTION_TYPE_BUY, QTY, "ENTRY");

        verify(gw, times(1)).placeOrder(any(OrderParams.class), anyString());
        verify(gw, never()).placeOrder(
                argThat(p -> p != null && Constants.ORDER_TYPE_MARKET.equals(p.orderType)), anyString());
        verify(gw, times(3)).cancelOrder("LIMIT1", Constants.VARIETY_REGULAR);
        assertThat(res.aggregateOrderIds()).as("the working LIMIT must stay traceable").isEqualTo("LIMIT1");
        assertThat(res.totalFilled()).isZero();
        assertThat(res.fullyFilled()).isFalse();
        assertThat(res.terminalStatus()).isEqualTo(OPEN);
        assertThat(PositionUtil.closeOrderMayBeLive(res)).as("drives PENDING_OPEN / PENDING_CLOSE").isTrue();
    }

    /**
     * The benign variant of the same rejection: the first cancel is rejected and the order is
     * still OPEN after the confirm budget, the retried cancel lands and confirms CANCELLED with a
     * partial fill. Only then is the MARKET top-up placed, sized from the confirmed fill.
     * <p>At <code>if (cancels.incrementAndGet() == 1) return false</code>: Kite 400 "An internal
     * error occurred"
     */
    @Test
    @DisplayName("cancel rejected once, lands on retry → top-up sized from the confirmed post-cancel fill")
    void cancelRetriedUntilConfirmedThenTopUp() {
        AtomicInteger cancels = new AtomicInteger();
        AtomicBoolean landed = new AtomicBoolean(false);
        when(gw.cancelOrder(anyString(), anyString())).thenAnswer(inv -> {
            if (cancels.incrementAndGet() == 1) return false;
            landed.set(true);
            return true;
        });
        when(gw.getOrderHistory("LIMIT1")).thenAnswer(inv -> landed.get()
                ? List.of(order(Constants.ORDER_CANCELLED, 130, 97.98))
                : List.of(order(OPEN, 130, 97.98)));
        when(gw.getOrderHistory("MARKET1")).thenReturn(
                List.of(order(Constants.ORDER_COMPLETE, 520, 98.20)));

        ExecResult res = util.placeAggressiveOrder(
                quote(97.80, 98.05), "NIFTY2662324050CE", Constants.TRANSACTION_TYPE_BUY, QTY, "ENTRY");

        verify(gw, times(2)).cancelOrder("LIMIT1", Constants.VARIETY_REGULAR);
        ArgumentCaptor<OrderParams> cap = ArgumentCaptor.forClass(OrderParams.class);
        verify(gw, times(2)).placeOrder(cap.capture(), anyString());
        OrderParams marketParams = cap.getAllValues().stream()
                .filter(p -> Constants.ORDER_TYPE_MARKET.equals(p.orderType))
                .findFirst().orElseThrow(() -> new AssertionError("no MARKET top-up was placed"));
        assertThat(marketParams.quantity).as("650 − confirmed 130").isEqualTo(520);
        assertThat(res.totalFilled()).isEqualTo(QTY);
        assertThat(res.fullyFilled()).isTrue();
        assertThat(res.aggregateOrderIds()).isEqualTo("LIMIT1, MARKET1");
    }

    /**
     * Walk-chase regression (2026-06-25): the LIMIT walk must re-anchor to the LIVE book each step,
     * not reprice within the stale snapshot it was handed. Here the passed-in quote is 113.75/113.95
     * but the market has run away to 119.90/120.10 by walk time. Every walk modify must target the
     * fresh ~120 book (chasing the move), never the stale ~113.95 ask — otherwise the LIMIT sits
     * unfilled and only MARKET catches it, paying the very slippage the walk exists to avoid.
     * <p>Live book has moved well above the stale snapshot handed to the walk.
     * <p>LIMIT never fills (it's chasing); walk exhausts → cancel → MARKET completes the leg.
     * <p>Every walk reprice must target the fresh book (~120), never the stale snapshot ask
     * (113.95).
     */
    @Test
    @DisplayName("walk re-anchors each step to the fresh book, not the stale passed-in quote")
    void walkChasesFreshQuoteNotStaleSnapshot() {
        when(gw.getQuote(any())).thenReturn(java.util.Map.of("NFO:NIFTY2662324050CE", quote(119.90, 120.10)));

        when(gw.getOrderHistory("LIMIT1"))
                .thenReturn(List.of(order(OPEN, 0, 0.0)))
                .thenReturn(List.of(order(OPEN, 0, 0.0)))
                .thenReturn(List.of(order(OPEN, 0, 0.0)))
                .thenReturn(List.of(order(OPEN, 0, 0.0)))
                .thenReturn(List.of(order(Constants.ORDER_CANCELLED, 0, 0.0)));
        when(gw.getOrderHistory("MARKET1")).thenReturn(
                List.of(order(Constants.ORDER_COMPLETE, QTY, 120.35)));

        ExecResult res = util.placeAggressiveOrder(
                quote(113.75, 113.95), "NIFTY2662324050CE", Constants.TRANSACTION_TYPE_BUY, QTY, "ENTRY");

        ArgumentCaptor<Double> px = ArgumentCaptor.forClass(Double.class);
        verify(gw, times(3)).modifyOrder(anyString(), px.capture(), anyInt(), anyString());
        assertThat(px.getAllValues())
                .as("walk must chase the fresh ~120 book, not reprice within the stale ~113.95 spread")
                .allSatisfy(p -> assertThat(p).isGreaterThan(118.0));

        assertThat(res.totalFilled()).isEqualTo(QTY);
        assertThat(res.fullyFilled()).isTrue();
    }

    // ─── helpers ────────────────────────────────────────────────────────────────────────────

    private static Order order(String status, int filled, double avgPrice) {
        Order o = new Order();
        o.status = status;
        o.filledQuantity = String.valueOf(filled);
        o.pendingQuantity = String.valueOf(QTY - filled);
        o.quantity = String.valueOf(QTY);
        o.averagePrice = String.valueOf(avgPrice);
        return o;
    }

    private static Quote quote(double bid, double ask) {
        Quote q = new Quote();
        q.lastPrice = (bid + ask) / 2.0;
        Depth b = new Depth();
        b.setPrice(bid);
        b.setQuantity(100000);
        Depth s = new Depth();
        s.setPrice(ask);
        s.setQuantity(100000);
        MarketDepth d = new MarketDepth();
        d.buy = List.of(b);
        d.sell = List.of(s);
        q.depth = d;
        return q;
    }
}
