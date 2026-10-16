package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.FAILED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_OPEN;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.zerodhatech.kiteconnect.utils.Constants;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.CloseOrderState;

/**
 * Drives {@link PendingOpenReconciler} through the open-side trade-73 scenarios that
 * motivated it. The nightmare case is the 1-leg LONG_MONTHLY book: its single entry order
 * goes UNCONFIRMED, the old handling would mark it FAILED, and a late fill would leave the
 * ENTIRE position live at the broker but invisible to the app. The reconciler must
 * (a) leave a working entry alone on passive ticks, (b) promote the leg to LIVE and run
 * post-open calc once the tradebook shows the fill, (c) mark a definitively failed entry
 * FAILED with fill prices untouched (neverTraded semantics), and (d) cancel a still-working
 * entry at signal time so the next trade never stacks on top of it.
 */
class PendingOpenReconcilerTest {

    private static final String MONTHLY_INS = "NIFTY26AUG24500CE";
    private static final String ORDER = "M-OPEN-1";
    private static final int QTY = 130;

    private PositionRepository repo;
    private PositionUtil util;
    private PostTradeService postTrade;
    private PendingOpenReconciler reconciler;
    private Position position;
    private WeeklyLeg leg;

    @BeforeEach
    void setUp() {
        repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        postTrade = mock(PostTradeService.class);
        reconciler = new PendingOpenReconciler(repo, util, postTrade);

        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

        leg = leg(1L, MONTHLY_INS, LONG_MONTHLY, BUY, PENDING_OPEN, ORDER, QTY);
        position = new Position();
        position.setStatus(PENDING_OPEN);
        position.setLegs(List.of(leg));
        ReflectionTestUtils.setField(position, "id", 80L);
        when(repo.findByLegStatus(PENDING_OPEN)).thenReturn(List.of(position));
    }

    @Test
    @DisplayName("late fill lands in the tradebook → leg LIVE, position LIVE, post-open calc runs")
    void lateFillPromotesPositionToLive() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(QTY, 212.4, Constants.ORDER_COMPLETE));

        reconciler.reconcileTick();

        assertThat(leg.getStatus()).isEqualTo(LIVE);
        assertThat(position.getStatus()).isEqualTo(LIVE);
        verify(postTrade).afterOpen(position);
        verify(util, never()).cancelAndSettleCloseOrders(anyString());
    }

    @Test
    @DisplayName("passive tick leaves a still-working entry order alone")
    void passiveTickWaitsOnWorkingOrder() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, "OPEN"));

        reconciler.reconcileTick();

        assertThat(leg.getStatus()).isEqualTo(PENDING_OPEN);
        assertThat(position.getStatus()).isEqualTo(PENDING_OPEN);
        verify(util, never()).cancelAndSettleCloseOrders(anyString());
        verify(postTrade, never()).afterOpen(any());
    }

    @Test
    @DisplayName("entry settled terminal with no fills → leg FAILED (never opened), position FAILED, no post-open calc")
    void terminalUnfilledEntryFailsCleanly() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, Constants.ORDER_CANCELLED));

        reconciler.reconcileTick();

        assertThat(leg.getStatus()).isEqualTo(FAILED);
        assertThat(leg.getBuyFillPrice()).as("neverTraded semantics need null fill prices").isNull();
        assertThat(position.getStatus()).isEqualTo(FAILED);
        verify(postTrade, never()).afterOpen(any());
    }

    @Test
    @DisplayName("entry settled terminal with a partial fill → leg LIVE trimmed to the filled qty")
    void partialFillTrimsLegToFilledQty() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(65, 212.0, Constants.ORDER_CANCELLED));

        reconciler.reconcileTick();

        assertThat(leg.getStatus()).isEqualTo(LIVE);
        assertThat(leg.getQuantity()).isEqualTo(65);
        assertThat(leg.getLots()).isEqualTo(1);
        assertThat(position.getStatus()).isEqualTo(LIVE);
        verify(postTrade).afterOpen(position);
    }

    @Test
    @DisplayName("signal-time resolve cancels the working entry before the next trade")
    void resolveBeforeSignalCancelsWorkingEntry() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, "OPEN"));
        when(util.cancelAndSettleCloseOrders(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, Constants.ORDER_CANCELLED));

        reconciler.resolveBeforeSignal();

        verify(util).cancelAndSettleCloseOrders(ORDER);
        assertThat(leg.getStatus()).isEqualTo(FAILED);
        assertThat(position.getStatus()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("signal-time resolve keeps a fill that lands during the cancel — leg LIVE, position LIVE")
    void resolveBeforeSignalKeepsCancelRaceFill() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, "OPEN"));
        when(util.cancelAndSettleCloseOrders(ORDER))
                .thenReturn(new CloseOrderState(QTY, 212.4, Constants.ORDER_COMPLETE));

        reconciler.resolveBeforeSignal();

        verify(util).cancelAndSettleCloseOrders(ORDER);
        assertThat(leg.getStatus()).isEqualTo(LIVE);
        assertThat(position.getStatus()).isEqualTo(LIVE);
        verify(postTrade).afterOpen(position);
    }

    @Test
    @DisplayName("two-leg weekly pending: working leg fails terminally while the other is LIVE → position PARTIAL for orphan flatten")
    void weeklyMixedSettleDropsToPartial() {
        WeeklyLeg liveLeg = leg(2L, "NIFTY2681224500CE", SYNTH_WEEKLY, BUY, LIVE, "CE-OPEN-1", 650);
        WeeklyLeg pendingLeg = leg(3L, "NIFTY2681224500PE", SYNTH_WEEKLY, SELL, PENDING_OPEN, "PE-OPEN-1", 650);
        Position weekly = new Position();
        weekly.setStatus(PENDING_OPEN);
        weekly.setLegs(List.of(liveLeg, pendingLeg));
        ReflectionTestUtils.setField(weekly, "id", 81L);
        when(repo.findByLegStatus(PENDING_OPEN)).thenReturn(List.of(weekly));
        when(util.readCloseOrderState("PE-OPEN-1"))
                .thenReturn(new CloseOrderState(0, 0.0, Constants.ORDER_REJECTED));

        reconciler.reconcileTick();

        assertThat(pendingLeg.getStatus()).isEqualTo(FAILED);
        assertThat(weekly.getStatus()).isEqualTo(PARTIAL);
        verify(postTrade).afterOpen(weekly);
    }

    /**
     * 2026-10-07 shape at reconcile time: the cancel is rejected / never lands and the entry
     * order is still OPEN after every attempt. Marking it FAILED here would make a late fill an
     * untracked broker position, so the leg must stay PENDING_OPEN with its order id intact.
     */
    @Test
    @DisplayName("signal-time resolve: cancel never confirms → leg STAYS PENDING_OPEN, never FAILED while the order may still fill")
    void resolveBeforeSignalKeepsPendingWhenCancelUnconfirmed() {
        when(util.readCloseOrderState(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, "OPEN"));
        when(util.cancelAndSettleCloseOrders(ORDER))
                .thenReturn(new CloseOrderState(0, 0.0, "OPEN"));

        reconciler.resolveBeforeSignal();

        verify(util).cancelAndSettleCloseOrders(ORDER);
        assertThat(leg.getStatus()).isEqualTo(PENDING_OPEN);
        assertThat(leg.getOpenOrderId()).as("order id must survive for the next attempt").isEqualTo(ORDER);
        assertThat(position.getStatus()).isEqualTo(PENDING_OPEN);
        verify(postTrade, never()).afterOpen(any());
    }

    private static WeeklyLeg leg(long id, String instrument, String book, String side, String status, String openOrderId, int qty) {
        WeeklyLeg w = new WeeklyLeg();
        ReflectionTestUtils.setField(w, "id", id);
        w.setInstrument(instrument);
        w.setExchangeSymbol("NFO:" + instrument);
        w.setBook(book);
        w.setSide(side);
        w.setQuantity(qty);
        w.setLots(qty / 65);
        w.setStatus(status);
        w.setOpenOrderId(openOrderId);
        return w;
    }
}
