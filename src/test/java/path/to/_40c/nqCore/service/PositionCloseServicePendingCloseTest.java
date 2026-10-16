package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.FAILED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.PENDING_CLOSE;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.ExecMode;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Regression guard for the 2026-08-04 trade-73 phantom-FAILED close. At the 09:15 shortExit
 * both exit legs fell back to MARKET+protection; the PE filled in 2.7s but the CE order —
 * converted by market protection into a resting LIMIT — was still OPEN when the ~3s confirm
 * budget ran out. The old code marked the leg and the whole position terminally FAILED, so
 * when the order filled fully 56 seconds later the fill was never recorded: the DB kept
 * P&L 0 while the broker had closed the trade, and the capital chain drifted by ₹10.8k.
 *
 * With the fix, a close whose order may still be working at the broker becomes PENDING_CLOSE
 * (closedAt deferred, close orderId retained) so {@link PendingCloseReconciler} can settle it
 * from the tradebook — while a definitively rejected close still fails exactly as before.
 */
class PositionCloseServicePendingCloseTest {

    private static final String CE = "NIFTY2681124650CE";
    private static final String PE = "NIFTY2681124650PE";
    private static final int QTY = 650;

    private PositionUtil util;
    private PendingCloseReconciler reconciler;
    private PositionCloseService service;
    private Position position;
    private WeeklyLeg ceLeg;
    private WeeklyLeg peLeg;

    /**
     * PE exit fills fully; the CE exit is the incident leg, scripted per test. Weekly signal closes
     * must stay AGGRESSIVE under the patient-mode routing, so the mode is asserted here.
     */
    @BeforeEach
    void setUp() {
        PositionRepository repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        ComputeUtil compute = mock(ComputeUtil.class);
        reconciler = mock(PendingCloseReconciler.class);
        service = new PositionCloseService(repo, util, compute, reconciler, mock(PendingOpenReconciler.class));

        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
        when(compute.getDtTimeNow()).thenReturn("04-08-2026 09:15:04.328");

        ceLeg = leg(CE, SELL);
        peLeg = leg(PE, BUY);
        position = new Position();
        position.setStatus(LIVE);
        position.setLegs(List.of(peLeg, ceLeg));
        when(util.findLiveTradesWithLiveOrderBooks(SYNTH_WEEKLY)).thenReturn(position);
        when(util.getQuote(any(String[].class))).thenReturn(Map.of("NFO:" + CE, new Quote(), "NFO:" + PE, new Quote()));

        when(util.placeAggressiveOrder(any(), eq(PE), eq(SELL), anyInt(), anyString(), eq(ExecMode.AGGRESSIVE)))
                .thenReturn(new ExecResult("PE-CLOSE-1", QTY, QTY, 153.5, true, Constants.ORDER_COMPLETE));
    }

    @Test
    @DisplayName("exit order still OPEN at broker after confirm budget → PENDING_CLOSE with orderId retained, not FAILED")
    void unconfirmedWorkingCloseBecomesPendingCloseNotFailed() {
        when(util.placeAggressiveOrder(any(), eq(CE), eq(BUY), anyInt(), anyString(), eq(ExecMode.AGGRESSIVE)))
                .thenReturn(new ExecResult("CE-CLOSE-1", 0, QTY, 0.0, false, "OPEN"));

        Position closed = service.closeWeeklyTrade("24665.50", signal(), true);

        assertThat(closed.getStatus()).isEqualTo(PENDING_CLOSE);
        assertThat(closed.getClosedAt()).as("closedAt must stay unset until the close settles").isNull();
        assertThat(ceLeg.getStatus()).isEqualTo(PENDING_CLOSE);
        assertThat(ceLeg.getCloseOrderId()).as("reconciler needs the working orderId").isEqualTo("CE-CLOSE-1");
        assertThat(peLeg.getStatus()).isEqualTo(CLOSED);
    }

    @Test
    @DisplayName("definitively REJECTED exit still fails terminally — PENDING_CLOSE is only for possibly-live orders")
    void rejectedCloseStillFailsTerminally() {
        when(util.placeAggressiveOrder(any(), eq(CE), eq(BUY), anyInt(), anyString(), eq(ExecMode.AGGRESSIVE)))
                .thenReturn(new ExecResult("", 0, QTY, 0.0, false, Constants.ORDER_REJECTED));

        Position closed = service.closeWeeklyTrade("24665.50", signal(), true);

        assertThat(closed.getStatus()).isEqualTo(FAILED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(ceLeg.getStatus()).isEqualTo(FAILED);
        assertThat(ceLeg.getCloseOrderId()).as("terminal reject records no close orderId (legacy semantics)").isNull();
    }

    private static WeeklyLeg leg(String instrument, String side) {
        WeeklyLeg w = new WeeklyLeg();
        w.setInstrument(instrument);
        w.setExchangeSymbol("NFO:" + instrument);
        w.setSide(side);
        w.setQuantity(QTY);
        w.setLots(10);
        w.setStatus(LIVE);
        w.setOpenOrderId(instrument + "-OPEN-1");
        return w;
    }

    private static Signal signal() {
        return new Signal("SwingMaster", "shortExit", "PE", "04-Aug-2026 09.15.00 AM", "24665.50");
    }
}
