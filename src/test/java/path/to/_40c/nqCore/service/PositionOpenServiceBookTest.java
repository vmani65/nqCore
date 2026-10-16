package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.FAILED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.LOT_SIZE;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.PENDING_OPEN;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zerodhatech.kiteconnect.utils.Constants;
import com.zerodhatech.models.Quote;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.LegOrder;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;
import path.to._40c.nqCore.util.PositionUtil.ExecResult;

/**
 * Business rules of the book-aware open path:
 * - openWeeklyTrade opens weekly-booked legs via the weekly slot; openMonthlyTrade opens
 *   the LONG_MONTHLY-booked leg via the monthly slot — the leg's BOOK stamp is what every
 *   book-scoped operation filters on, so a wrong/missing stamp would let one book close
 *   the other's legs on the shared row.
 * - PENDING_OPEN producer (trade-73 class, open side): an entry order that finishes its
 *   confirm budget in a NON-terminal state must park the leg as PENDING_OPEN with the
 *   orderId retained — never terminally FAILED, because a late fill would then be an
 *   untracked broker position (with a 1-leg book: the entire position). A definitively
 *   REJECTED entry still fails exactly as before.
 */
class PositionOpenServiceBookTest {

    private static final String CE_INS = "NIFTY2681224500CE";
    private static final String PE_INS = "NIFTY2681224500PE";
    private static final String MONTHLY_INS = "NIFTY26AUG24500CE";
    private static final int QTY = 650;

    private PositionRepository repo;
    private PositionUtil util;
    private ComputeUtil compute;
    private PositionOpenService service;

    @BeforeEach
    void setUp() {
        repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        compute = mock(ComputeUtil.class);
        service = new PositionOpenService(repo, util, compute);
        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("openWeeklyTrade stamps SYNTH_WEEKLY and opens the weekly pair LIVE on full fills")
    void weeklyOpenStampsBookAndGoesLive() {
        stubWeeklyPair();
        stubFill(CE_INS, "CE-OPEN-1", QTY, Constants.ORDER_COMPLETE);
        stubFill(PE_INS, "PE-OPEN-1", QTY, Constants.ORDER_COMPLETE);

        Position saved = service.openWeeklyTrade("24500", "CE", new Position());

        assertThat(saved.getLegs()).allSatisfy(l -> assertThat(l.getBook()).isEqualTo(SYNTH_WEEKLY));
        assertThat(saved.getDirection()).isEqualTo(LONG);
        assertThat(saved.getStatus()).isEqualTo(LIVE);
        assertThat(saved.getLegs()).hasSize(2);
        verify(compute).buildWeeklyInstrument(eq("24500"), any(Position.class));
        verify(compute, never()).buildMonthlyInstrument(anyString(), any(Position.class));
    }

    @Test
    @DisplayName("openMonthlyTrade stamps LONG_MONTHLY on the leg and opens it via the monthly build")
    void monthlyOpenStampsBookAndUsesMonthlyBuild() {
        LegOrder leg = legOrder(MONTHLY_INS, LONG_MONTHLY, BUY, 2);
        when(compute.buildMonthlyInstrument(eq("24500"), any(Position.class))).thenReturn(List.of(leg));
        when(util.getQuote(any(String[].class))).thenReturn(Map.of("NFO:" + MONTHLY_INS, new Quote()));
        stubFill(MONTHLY_INS, "M-OPEN-1", 2 * LOT_SIZE, Constants.ORDER_COMPLETE);

        Position saved = service.openMonthlyTrade("24500", "CE", new Position());

        assertThat(saved.getLegs().get(0).getBook()).isEqualTo(LONG_MONTHLY);
        assertThat(saved.getStatus()).isEqualTo(LIVE);
        assertThat(saved.getLegs()).hasSize(1);
        assertThat(saved.getLegs().get(0).getQuantity()).isEqualTo(2 * LOT_SIZE);
        verify(compute, never()).buildWeeklyInstrument(anyString(), any(Position.class));
    }

    @Test
    @DisplayName("entry order still OPEN after the confirm budget → leg + position PENDING_OPEN, orderId retained")
    void unconfirmedWorkingEntryBecomesPendingOpen() {
        LegOrder leg = legOrder(MONTHLY_INS, LONG_MONTHLY, BUY, 10);
        when(compute.buildMonthlyInstrument(anyString(), any(Position.class))).thenReturn(List.of(leg));
        when(util.getQuote(any(String[].class))).thenReturn(Map.of("NFO:" + MONTHLY_INS, new Quote()));
        stubExec(MONTHLY_INS, new ExecResult("M-OPEN-1", 0, QTY, 0.0, false, "OPEN"));

        Position saved = service.openMonthlyTrade("24500", "CE", new Position());

        assertThat(saved.getStatus()).isEqualTo(PENDING_OPEN);
        assertThat(saved.getLegs().get(0).getStatus()).isEqualTo(PENDING_OPEN);
        assertThat(saved.getLegs().get(0).getOpenOrderId())
                .as("reconciler needs the working orderId").isEqualTo("M-OPEN-1");
        assertThat(saved.getLegs().get(0).getQuantity())
                .as("intended qty retained until the tradebook settles the real fill").isEqualTo(QTY);
    }

    @Test
    @DisplayName("definitively REJECTED entry still fails terminally — PENDING_OPEN is only for possibly-live orders")
    void rejectedEntryStillFailsTerminally() {
        LegOrder leg = legOrder(MONTHLY_INS, LONG_MONTHLY, BUY, 10);
        when(compute.buildMonthlyInstrument(anyString(), any(Position.class))).thenReturn(List.of(leg));
        when(util.getQuote(any(String[].class))).thenReturn(Map.of("NFO:" + MONTHLY_INS, new Quote()));
        stubExec(MONTHLY_INS, new ExecResult("", 0, QTY, 0.0, false, Constants.ORDER_REJECTED));

        Position saved = service.openMonthlyTrade("24500", "CE", new Position());

        assertThat(saved.getStatus()).isEqualTo(FAILED);
        assertThat(saved.getLegs().get(0).getStatus()).isEqualTo(FAILED);
    }

    @Test
    @DisplayName("two-leg weekly open: one leg filled, one still working → position PENDING_OPEN (not PARTIAL yet)")
    void mixedFillAndWorkingLegParksPendingOpen() {
        stubWeeklyPair();
        stubFill(CE_INS, "CE-OPEN-1", QTY, Constants.ORDER_COMPLETE);
        stubExec(PE_INS, new ExecResult("PE-OPEN-1", 0, QTY, 0.0, false, "OPEN"));

        Position saved = service.openWeeklyTrade("24500", "CE", new Position());

        assertThat(saved.getStatus()).isEqualTo(PENDING_OPEN);
        assertThat(saved.getLegs()).extracting(l -> l.getStatus())
                .containsExactlyInAnyOrder(LIVE, PENDING_OPEN);
    }

    @Test
    @DisplayName("two-leg weekly open: one filled, one terminally rejected → PARTIAL (orphan machinery), unchanged semantics")
    void mixedFillAndRejectStaysPartial() {
        stubWeeklyPair();
        stubFill(CE_INS, "CE-OPEN-1", QTY, Constants.ORDER_COMPLETE);
        stubExec(PE_INS, new ExecResult("", 0, QTY, 0.0, false, Constants.ORDER_REJECTED));

        Position saved = service.openWeeklyTrade("24500", "CE", new Position());

        assertThat(saved.getStatus()).isEqualTo(PARTIAL);
    }

    /**
     * The open guard for the 2026-10-07 class: while any leg of this book still has an order
     * unconfirmed at the broker (PENDING_OPEN / PENDING_CLOSE), a fresh entry would stack on an
     * order that may yet fill. Nothing may be placed; the signal's legs are recorded FAILED with
     * no order id (neverTraded semantics), exactly like the unreadable-quote abort.
     */
    @Test
    @DisplayName("open BLOCKED while the book has a leg with an order unsettled at the broker — nothing placed, legs FAILED")
    void openBlockedWhileBookHasUnsettledLeg() {
        LegOrder leg = legOrder(MONTHLY_INS, LONG_MONTHLY, BUY, 1);
        when(compute.buildMonthlyInstrument(anyString(), any(Position.class))).thenReturn(List.of(leg));
        when(util.getQuote(any(String[].class))).thenReturn(Map.of("NFO:" + MONTHLY_INS, new Quote()));
        WeeklyLeg stuck = new WeeklyLeg();
        stuck.setInstrument("NIFTY26OCT22650PE");
        stuck.setBook(LONG_MONTHLY);
        stuck.setStatus(PENDING_OPEN);
        stuck.setOpenOrderId("LIMIT-STUCK");
        when(util.findUnsettledLegs(LONG_MONTHLY)).thenReturn(List.of(stuck));

        Position saved = service.openMonthlyTrade("24500", "CE", new Position());

        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        assertThat(saved.getStatus()).isEqualTo(FAILED);
        assertThat(saved.getLegs()).hasSize(1);
        assertThat(saved.getLegs().get(0).getStatus()).isEqualTo(FAILED);
        assertThat(saved.getLegs().get(0).getOpenOrderId()).isNull();
    }

    private void stubWeeklyPair() {
        List<LegOrder> pair = List.of(legOrder(CE_INS, SYNTH_WEEKLY, BUY, 10), legOrder(PE_INS, SYNTH_WEEKLY, SELL, 10));
        when(compute.buildWeeklyInstrument(anyString(), any(Position.class))).thenReturn(pair);
        when(util.getQuote(any(String[].class))).thenReturn(Map.of(
                "NFO:" + CE_INS, new Quote(), "NFO:" + PE_INS, new Quote()));
    }

    private void stubFill(String instrument, String orderId, int filled, String status) {
        stubExec(instrument, new ExecResult(orderId, filled, filled, 100.0, true, status));
    }

    private void stubExec(String instrument, ExecResult er) {
        when(util.placeAggressiveOrder(any(), eq(instrument), anyString(), anyInt(), anyString())).thenReturn(er);
    }

    private static LegOrder legOrder(String instrument, String book, String side, int lots) {
        LegOrder w = new LegOrder();
        w.setInstrument(instrument);
        w.setExchangeSymbol("NFO:" + instrument);
        w.setBook(book);
        w.setSide(side);
        w.setLots(lots);
        w.setMoneyness("ATM");
        w.setOptionType(instrument.endsWith("CE") ? "CE" : "PE");
        return w;
    }
}
