package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_CLOSE;
import static path.to._40c.nqCore.util.Constants.SELL;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.zerodhatech.kiteconnect.utils.Constants;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.CloseOrderState;

/**
 * Drives {@link PendingCloseReconciler} through the 2026-08-04 trade-73 incident and its
 * counterfactuals. The recorded facts: the 09:15:01 CE exit (order 2084485732528857090) was
 * still OPEN when the confirm budget expired, filled 650 @ 118.35 at 09:16:00, and under the
 * old terminal-FAILED handling the fill was never recorded — P&L stayed 0 and the capital
 * chain drifted. The reconciler must instead (a) leave a working order alone on passive ticks,
 * (b) finalize the position with post-close calc once the tradebook shows the fill, (c) revert
 * a definitively failed close to the orphan machinery, and (d) cancel a still-working order at
 * signal time so re-entry never trades against it.
 */
class PendingCloseReconcilerTest {

    private static final String CE = "NIFTY2681124650CE";
    private static final String ORDER = "CE-CLOSE-1";
    private static final int QTY = 650;

    private PositionRepository repo;
    private PositionUtil util;
    private PostTradeService postTrade;
    private PendingCloseReconciler reconciler;
    private Position position;
    private WeeklyLeg ceLeg;
    private WeeklyLeg peLeg;

    @BeforeEach
    void setUp() {
        repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        postTrade = mock(PostTradeService.class);
        ComputeUtil compute = mock(ComputeUtil.class);
        reconciler = new PendingCloseReconciler(repo, util, postTrade, compute);

        when(compute.getDtTimeNow()).thenReturn("04-08-2026 09:16:30.000");
        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

        peLeg = leg(1L, "NIFTY2681124650PE", BUY, CLOSED, "PE-CLOSE-1");
        ceLeg = leg(2L, CE, SELL, PENDING_CLOSE, ORDER);
        position = new Position();
        position.setStatus(PENDING_CLOSE);
        position.setLegs(List.of(peLeg, ceLeg));
        ReflectionTestUtils.setField(position, "id", 73L);
        when(repo.findByLegStatus(PENDING_CLOSE)).thenReturn(List.of(position));
    }

    @Test
    @DisplayName("trade-73 shape: tradebook shows the late fill → leg CLOSED, position CLOSED, post-close calc runs")
    void lateFillFinalizesPositionAndRunsPostClose() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(QTY, 118.35, Constants.ORDER_COMPLETE));

        reconciler.reconcileTick();

        assertThat(ceLeg.getStatus()).isEqualTo(CLOSED);
        assertThat(position.getStatus()).isEqualTo(CLOSED);
        assertThat(position.getClosedAt()).isNotNull();
        verify(postTrade).afterClose(position);
        verify(util, never()).cancelAndSettleCloseOrders(anyString());
    }

    @Test
    @DisplayName("passive tick leaves a still-working close order alone")
    void passiveTickWaitsOnWorkingOrder() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, "OPEN"));

        reconciler.reconcileTick();

        assertThat(ceLeg.getStatus()).isEqualTo(PENDING_CLOSE);
        assertThat(position.getStatus()).isEqualTo(PENDING_CLOSE);
        verify(util, never()).cancelAndSettleCloseOrders(anyString());
        verify(postTrade, never()).afterClose(any());
    }

    @Test
    @DisplayName("close order settled terminal with no fills → leg back to LIVE, position PARTIAL for orphan flatten")
    void terminalUnfilledCloseRevertsToOrphan() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, Constants.ORDER_CANCELLED));

        reconciler.reconcileTick();

        assertThat(ceLeg.getStatus()).isEqualTo(LIVE);
        assertThat(ceLeg.getQuantity()).isEqualTo(QTY);
        assertThat(position.getStatus()).isEqualTo(PARTIAL);
        verify(postTrade, never()).afterClose(any());
    }

    @Test
    @DisplayName("signal-time resolve cancels the working order before re-entry and flattens the remainder")
    void resolveBeforeSignalCancelsWorkingOrder() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, "OPEN"));
        when(util.cancelAndSettleCloseOrders(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, Constants.ORDER_CANCELLED));

        reconciler.resolveBeforeSignal();

        verify(util).cancelAndSettleCloseOrders(ORDER);
        assertThat(ceLeg.getStatus()).isEqualTo(LIVE);
        assertThat(position.getStatus()).isEqualTo(PARTIAL);
    }

    @Test
    @DisplayName("signal-time resolve keeps a fill that lands during the cancel — leg closes, position finalizes")
    void resolveBeforeSignalKeepsCancelRaceFill() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(195, 118.3, "OPEN"));
        when(util.cancelAndSettleCloseOrders(ORDER))
                .thenReturn(new CloseOrderState(QTY, 118.35, Constants.ORDER_COMPLETE));

        reconciler.resolveBeforeSignal();

        verify(util).cancelAndSettleCloseOrders(ORDER);
        assertThat(ceLeg.getStatus()).isEqualTo(CLOSED);
        assertThat(position.getStatus()).isEqualTo(CLOSED);
        verify(postTrade).afterClose(position);
    }

    @Test
    @DisplayName("partially filled then cancelled close trims the LIVE leg to the unfilled remainder")
    void partialCloseTrimsLegToRemainder() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(260, 118.2, Constants.ORDER_CANCELLED));

        reconciler.reconcileTick();

        assertThat(ceLeg.getStatus()).isEqualTo(LIVE);
        assertThat(ceLeg.getQuantity()).isEqualTo(QTY - 260);
        assertThat(ceLeg.getLots()).isEqualTo((QTY - 260) / 65);
        assertThat(position.getStatus()).isEqualTo(PARTIAL);
    }

    /**
     * Close-side twin of the 2026-10-07 shape: the cancel never lands and the exit order is still
     * OPEN after every attempt. Reviving the leg LIVE would make the orphan flatten sell against
     * an exit that may still fill — a double-sell past flat — so the leg must stay PENDING_CLOSE.
     */
    @Test
    @DisplayName("signal-time resolve: cancel never confirms → leg STAYS PENDING_CLOSE, never revived LIVE while the exit may still fill")
    void resolveBeforeSignalKeepsPendingWhenCancelUnconfirmed() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, "OPEN"));
        when(util.cancelAndSettleCloseOrders(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, "OPEN"));

        reconciler.resolveBeforeSignal();

        verify(util).cancelAndSettleCloseOrders(ORDER);
        assertThat(ceLeg.getStatus()).isEqualTo(PENDING_CLOSE);
        assertThat(position.getStatus()).isEqualTo(PENDING_CLOSE);
        verify(postTrade, never()).afterClose(any());
    }

    private static WeeklyLeg leg(long id, String instrument, String side, String status, String closeOrderId) {
        WeeklyLeg w = new WeeklyLeg();
        ReflectionTestUtils.setField(w, "id", id);
        w.setInstrument(instrument);
        w.setExchangeSymbol("NFO:" + instrument);
        w.setSide(side);
        w.setQuantity(QTY);
        w.setLots(10);
        w.setStatus(status);
        w.setCloseOrderId(closeOrderId);
        return w;
    }
}
