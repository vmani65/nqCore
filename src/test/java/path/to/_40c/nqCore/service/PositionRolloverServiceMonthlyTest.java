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
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SELL;

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
 * The LONG_MONTHLY position roll (owner requirement 2026-08-08: same shape as weekly —
 * sell whatever is in hand, buy at the current ATM on the latest monthly contract).
 * Accounting must be exact:
 * - the closed segment's spot points are BANKED (bankedPoints += direction-adjusted
 *   rollPrice − baselineSpot) and baselineSpot resets to the roll price, so the final
 *   close still computes pointsPnl = banked + last segment with no double count;
 * - each closed leg's expectedPnl is stamped at qty × 0.5 × segment — the same
 *   futures-equivalent scale calcPnL uses for this book's final segment, so per-leg
 *   capture percentages stay comparable across segments;
 * - a SELL of the in-hand leg and a BUY of the new contract, old leg CLOSED, new leg
 *   LIVE, position stays LIVE on the same row;
 * - no-churn guard: a roll happens ONLY on a contract change — held legs already on the
 *   current contract mean nothing trades, even when the ATM has drifted (re-striking on
 *   the same contract would be a recenter, which LONG_MONTHLY never does by policy);
 * - a failed close aborts before any new position exists (never leave the book naked
 *   AND doubled).
 */
class PositionRolloverServiceMonthlyTest {

    private static final String OLD_INS = "NIFTY26AUG24500CE";
    private static final String NEW_INS = "NIFTY26SEP24800CE";
    private static final int QTY = 130;

    private PositionUtil util;
    private ComputeUtil compute;
    private PostTradeService postTrade;
    private PositionRolloverService service;
    private Position monthly;
    private WeeklyLeg heldLeg;

    @BeforeEach
    void setUp() {
        PositionRepository repo = mock(PositionRepository.class);
        util = mock(PositionUtil.class);
        compute = mock(ComputeUtil.class);
        postTrade = mock(PostTradeService.class);
        PositionCloseService realCloser = new PositionCloseService(mock(PositionRepository.class),
                mock(PositionUtil.class), mock(ComputeUtil.class),
                mock(PendingCloseReconciler.class), mock(PendingOpenReconciler.class));
        service = new PositionRolloverService(repo, util, compute, postTrade, realCloser);
        when(repo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

        heldLeg = new WeeklyLeg();
        heldLeg.setInstrument(OLD_INS);
        heldLeg.setExchangeSymbol("NFO:" + OLD_INS);
        heldLeg.setBook(LONG_MONTHLY);
        heldLeg.setSide(BUY);
        heldLeg.setQuantity(QTY);
        heldLeg.setLots(2);
        heldLeg.setStatus(LIVE);

        monthly = new Position();
        monthly.setDirection(LONG);
        monthly.setStatus(LIVE);
        monthly.setEntrySpot(24500.0);
        monthly.setMonthlyBaselineSpot(24500.0);
        monthly.setMonthlyBankedPoints(0.0);
        monthly.setLegs(List.of(heldLeg));
        when(util.findLiveTradesWithLiveOrderBooks(LONG_MONTHLY)).thenReturn(monthly);
        when(util.getQuote(any(String[].class))).thenReturn(Map.of(
                "NFO:" + OLD_INS, new Quote(), "NFO:" + NEW_INS, new Quote()));
    }

    /**
     * segment = 24800 - 24500 = +300 pts, banked once into the MONTHLY chain, its baseline reset
     * <p>closed leg stamped on the futures-equivalent scale: 130 × 0.5 × 300
     * <p>new leg LIVE on the latest contract, same position row
     */
    @Test
    @DisplayName("monthly roll: sells in-hand leg, banks qty/2-scaled segment, buys latest contract at ATM, stays LIVE")
    void monthlyRollBanksAndReopensOnLatestContract() {
        when(compute.monthlyContractPrefix()).thenReturn("NIFTY26SEP");
        when(compute.buildMonthlyInstrument(eq("24800"), any(Position.class)))
                .thenReturn(List.of(legOrder(NEW_INS, 2)));
        when(util.placeAggressiveOrder(any(), eq(OLD_INS), eq(SELL), anyInt(), anyString()))
                .thenReturn(new ExecResult("M-ROLL-CLOSE", QTY, QTY, 310.0, true, Constants.ORDER_COMPLETE));
        when(util.placeAggressiveOrder(any(), eq(NEW_INS), eq(BUY), anyInt(), anyString()))
                .thenReturn(new ExecResult("M-ROLL-OPEN", QTY, QTY, 260.0, true, Constants.ORDER_COMPLETE));

        service.rollOverMonthly("24800");

        assertThat(monthly.getMonthlyBankedPoints()).isEqualTo(300.0);
        assertThat(monthly.getMonthlyBaselineSpot()).isEqualTo(24800.0);
        assertThat(monthly.getBaselineSpot()).as("weekly chain untouched by a monthly roll").isNull();
        assertThat(monthly.getEntrySpot()).as("entrySpot immutable").isEqualTo(24500.0);
        assertThat(heldLeg.getStatus()).isEqualTo(CLOSED);
        assertThat(heldLeg.getExpectedPnl()).isEqualTo(19500.0);
        assertThat(heldLeg.getCloseOrderId()).isEqualTo("M-ROLL-CLOSE");
        assertThat(monthly.getStatus()).isEqualTo(LIVE);
        List<WeeklyLeg> newLegs = monthly.getLegs().stream().filter(l -> NEW_INS.equals(l.getInstrument())).toList();
        assertThat(newLegs).hasSize(1);
        assertThat(newLegs.get(0).getStatus()).isEqualTo(LIVE);
        assertThat(newLegs.get(0).getOpenOrderId()).isEqualTo("M-ROLL-OPEN");
        verify(postTrade).afterOpen(monthly);
    }

    @Test
    @DisplayName("no-churn guard: position already on the current contract → nothing trades, even with the ATM drifted 300 pts")
    void sameContractSkipsRollEvenWhenAtmDrifted() {
        when(compute.monthlyContractPrefix()).thenReturn("NIFTY26AUG");

        service.rollOverMonthly("24800");

        verify(compute, never()).buildMonthlyInstrument(anyString(), any(Position.class));
        verify(util, never()).placeAggressiveOrder(any(), anyString(), anyString(), anyInt(), anyString());
        assertThat(heldLeg.getStatus()).isEqualTo(LIVE);
        assertThat(monthly.getMonthlyBankedPoints()).isEqualTo(0.0);
        verify(postTrade, never()).afterOpen(any());
    }

    @Test
    @DisplayName("failed close aborts the roll — nothing banked, no new leg, no phantom position")
    void failedCloseAbortsBeforeOpen() {
        when(compute.monthlyContractPrefix()).thenReturn("NIFTY26SEP");
        when(compute.buildMonthlyInstrument(eq("24800"), any(Position.class)))
                .thenReturn(List.of(legOrder(NEW_INS, 2)));
        when(util.placeAggressiveOrder(any(), eq(OLD_INS), eq(SELL), anyInt(), anyString()))
                .thenReturn(new ExecResult("", 0, QTY, 0.0, false, Constants.ORDER_REJECTED));

        service.rollOverMonthly("24800");

        verify(util, never()).placeAggressiveOrder(any(), eq(NEW_INS), anyString(), anyInt(), anyString());
        assertThat(monthly.getMonthlyBankedPoints()).as("segment must not be banked on an aborted roll").isEqualTo(0.0);
        assertThat(monthly.getMonthlyBaselineSpot()).isEqualTo(24500.0);
        verify(postTrade, never()).afterOpen(any());
    }

    private static LegOrder legOrder(String instrument, int lots) {
        LegOrder w = new LegOrder();
        w.setInstrument(instrument);
        w.setExchangeSymbol("NFO:" + instrument);
        w.setBook(LONG_MONTHLY);
        w.setSide(BUY);
        w.setLots(lots);
        w.setMoneyness("ATM");
        w.setOptionType("CE");
        return w;
    }
}
