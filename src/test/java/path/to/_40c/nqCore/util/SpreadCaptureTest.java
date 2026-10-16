package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SELL;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Depth;
import com.zerodhatech.models.MarketDepth;
import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.service.PendingCloseReconciler;
import path.to._40c.nqCore.service.PendingOpenReconciler;
import path.to._40c.nqCore.service.PositionCloseService;
import path.to._40c.nqCore.service.PositionOpenService;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Monthly liquidity guard (plan §3.3.2): effective spread paid = achieved avg fill vs the
 * quote midpoint at order time, signed so paying up is positive, recorded per leg at both
 * entry (OPEN_SPREAD_PAID) and exit (CLOSE_SPREAD_PAID). Unlike the execution path's
 * midHalfSpread, measurement has NO wide-book gate — an illiquid monthly book is exactly
 * what must get recorded. Persisting this is what makes the ">~2 pts/side means the
 * monthly economics need re-evaluation" check possible from data instead of anecdote.
 */
class SpreadCaptureTest {

    private static final String INS = "NIFTY26AUG24500CE";

    // ---------------------------------------------------------------
    // Helper math
    // ---------------------------------------------------------------

    @Test
    @DisplayName("BUY above mid and SELL below mid are positive; price improvement is negative")
    void spreadSignConvention() {
        Quote q = quote(99.9, 100.1);

        assertThat(PositionUtil.effectiveSpreadPaid(q, BUY, 100.5)).isEqualTo(0.5);
        assertThat(PositionUtil.effectiveSpreadPaid(q, SELL, 99.5)).isEqualTo(0.5);
        assertThat(PositionUtil.effectiveSpreadPaid(q, BUY, 99.8)).isEqualTo(-0.2);
        assertThat(PositionUtil.effectiveSpreadPaid(q, SELL, 100.3)).isEqualTo(-0.3);
    }

    @Test
    @DisplayName("a wide illiquid book is still measured — no execution-quality gate on measurement")
    void wideBookIsStillMeasured() {
        Quote q = quote(90.0, 110.0);

        assertThat(PositionUtil.effectiveSpreadPaid(q, BUY, 110.0)).isEqualTo(10.0);
    }

    @Test
    @DisplayName("missing depth or unusable fill yields null, never a fake zero")
    void unusableInputsYieldNull() {
        assertThat(PositionUtil.effectiveSpreadPaid(null, BUY, 100.0)).isNull();
        assertThat(PositionUtil.effectiveSpreadPaid(new Quote(), BUY, 100.0)).isNull();
        assertThat(PositionUtil.effectiveSpreadPaid(quote(99.9, 100.1), BUY, 0.0)).isNull();
        assertThat(PositionUtil.effectiveSpreadPaid(quote(100.1, 99.9), BUY, 100.0))
                .as("crossed book rejected").isNull();
    }

    // ---------------------------------------------------------------
    // Service wiring
    // ---------------------------------------------------------------

    private PositionRepository repo;
    private PositionUtil util;
    private ComputeUtil compute;

    @BeforeEach
    void setUp() {
        repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        compute = mock(ComputeUtil.class);
        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("entry records openSpreadPaid on the persisted leg from the order-time quote")
    void entryRecordsOpenSpread() {
        PositionOpenService opening = new PositionOpenService(repo, util, compute);
        LegOrder leg = new LegOrder();
        leg.setInstrument(INS);
        leg.setExchangeSymbol("NFO:" + INS);
        leg.setBook(LONG_MONTHLY);
        leg.setSide(BUY);
        leg.setLots(2);
        leg.setMoneyness("ATM");
        leg.setOptionType("CE");
        when(compute.buildMonthlyInstrument(anyString(), any(Position.class))).thenReturn(List.of(leg));
        when(util.getQuote(any(String[].class))).thenReturn(Map.of("NFO:" + INS, quote(211.5, 212.5)));
        when(util.placeAggressiveOrder(any(), eq(INS), anyString(), anyInt(), anyString()))
                .thenReturn(new ExecResult("M-1", 130, 130, 212.4, true, Constants.ORDER_COMPLETE));

        Position saved = opening.openMonthlyTrade("24500", "CE", new Position());

        assertThat(saved.getLegs().get(0).getOpenSpreadPaid()).isEqualTo(0.4);
    }

    /**
     * closing a bought leg SELLs: fill 219.6 vs mid 220.0 → paid 0.4 on the way out. A LIVE
     * LONG_MONTHLY signal close must route PATIENT, so the mode is asserted here.
     */
    @Test
    @DisplayName("exit records closeSpreadPaid measured on the closing transaction side")
    void exitRecordsCloseSpread() {
        PositionCloseService closing = new PositionCloseService(repo, util, compute,
                mock(PendingCloseReconciler.class), mock(PendingOpenReconciler.class));
        when(compute.getDtTimeNow()).thenReturn("07-08-2026 14:30:00.000");
        WeeklyLeg leg = new WeeklyLeg();
        leg.setInstrument(INS);
        leg.setExchangeSymbol("NFO:" + INS);
        leg.setBook(LONG_MONTHLY);
        leg.setSide(BUY);
        leg.setQuantity(130);
        leg.setLots(2);
        leg.setStatus(LIVE);
        Position monthly = new Position();
        monthly.setStatus(LIVE);
        monthly.setLegs(List.of(leg));
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(monthly);
        when(util.getQuote(any(String[].class))).thenReturn(Map.of("NFO:" + INS, quote(219.5, 220.5)));
        when(util.placeAggressiveOrder(any(), eq(INS), eq(SELL), anyInt(), anyString(), eq(ExecMode.PATIENT)))
                .thenReturn(new ExecResult("M-CLOSE-1", 130, 130, 219.6, true, Constants.ORDER_COMPLETE));

        closing.closeMonthlyTrade("24600", new Signal("RIDETHETIDE", "longExit", "CE", "", "24600"), true);

        assertThat(leg.getCloseSpreadPaid()).isEqualTo(0.4);
    }

    private static Quote quote(double bid, double ask) {
        Quote q = new Quote();
        q.lastPrice = (bid + ask) / 2.0;
        q.depth = new MarketDepth();
        q.depth.buy = new ArrayList<>();
        q.depth.sell = new ArrayList<>();
        Depth b = new Depth();
        b.setPrice(bid);
        b.setQuantity(1000);
        Depth a = new Depth();
        a.setPrice(ask);
        a.setQuantity(1000);
        q.depth.buy.add(b);
        q.depth.sell.add(a);
        return q;
    }
}
