package path.to._40c.nqCore.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.LOSS;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;
import static path.to._40c.nqCore.util.Constants.WIN;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.TradeCapital;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.TradeCapitalRepository;
import path.to._40c.nqCore.service.LegTemplateCache;
import path.to._40c.nqCore.service.WeeklySymbolCache;

/**
 * Book-correct accounting (plan §7.2 items 10-12). The LONG_MONTHLY rules:
 * - WIN/LOSS comes from net RUPEE PnL, not the spot-points sign — a bought option's
 *   theta can turn a small spot-points winner into a rupee loser, which the delta-1
 *   synthetic's points-sign rule would mislabel WIN;
 * - expected PnL uses (qty / 2) × points, the futures-equivalent yardstick (2 monthly
 *   lots ≈ delta 1), so both books' pnlCapturePct read on the same scale;
 * - the account-level capital chain still flows from monthly closes, but the sizing
 *   fields (possibleLots / currentRiskPerLot) are weekly-margin math and stay untouched.
 * SYNTH_WEEKLY behavior is pinned unchanged throughout.
 */
class ComputeUtilBookAccountingTest {

    private TradeCapitalRepository capitalRepo;
    private ComputeUtil computeUtil;

    @BeforeEach
    void setUp() {
        capitalRepo = mock(TradeCapitalRepository.class);
        computeUtil = new ComputeUtil(mock(WeeklySymbolCache.class), mock(path.to._40c.nqCore.service.MonthlySymbolCache.class), mock(LegTemplateCache.class), capitalRepo);
    }

    // ---------------------------------------------------------------
    // WIN/LOSS source
    // ---------------------------------------------------------------

    /**
     * 2 monthly lots (130 qty) bought at 212, sold at 205 after a +20-pt drift: spot won, theta
     * lost.
     */
    @Test
    @DisplayName("monthly theta bleed: +20 spot points but negative rupee PnL → LOSS (weekly rule would say WIN)")
    void monthlyThetaBledWinnerIsRupeeLoss() {
        Position trade = monthlyTrade(24500.0, 24520.0);
        trade.setLegs(List.of(closedLeg("NIFTY26AUG24500CE", LONG_MONTHLY, BUY, 130, 2, 212.0, 205.0)));

        computeUtil.calcTradeOutcome(trade);
        assertThat(trade.getPointsPnl()).isEqualTo(20.0);
        assertThat(trade.getResult()).as("monthly outcome deferred to rupee PnL").isNull();

        computeUtil.calcPnL(trade);
        assertThat(trade.getActualPnl()).isLessThan(0);
        assertThat(trade.getResult()).isEqualTo(LOSS);
    }

    /**
     * +100 pts; leg gains 55 /unit on 130 qty = 7150 gross vs expected 65 × 100 = 6500 → capture
     * &gt; 100%.
     */
    @Test
    @DisplayName("monthly gamma winner: positive rupee PnL → WIN with capture measured against qty/2 × points")
    void monthlyGammaWinnerCapturesAgainstFuturesEquivalent() {
        Position trade = monthlyTrade(24500.0, 24600.0);
        trade.setLegs(List.of(closedLeg("NIFTY26AUG24500CE", LONG_MONTHLY, BUY, 130, 2, 212.0, 267.0)));

        computeUtil.calcTradeOutcome(trade);
        computeUtil.calcPnL(trade);

        assertThat(trade.getExpectedPnl()).isEqualTo(6500.0);
        assertThat(trade.getLegs().get(0).getExpectedPnl()).isEqualTo(6500.0);
        assertThat(trade.getActualPnl()).isEqualTo(7150.0 - 44.0);
        assertThat(trade.getResult()).isEqualTo(WIN);
    }

    @Test
    @DisplayName("weekly synthetic keeps the spot-points WIN/LOSS rule and full-qty expected (regression)")
    void weeklyRulesUnchanged() {
        Position trade = weeklyTrade(24500.0, 24600.0);
        trade.setLegs(List.of(
                closedLeg("NIFTY2681224500CE", SYNTH_WEEKLY, BUY, 650, 10, 145.0, 210.0),
                closedLeg("NIFTY2681224500PE", SYNTH_WEEKLY, SELL, 650, 10, 120.0, 154.0)));

        computeUtil.calcTradeOutcome(trade);
        assertThat(trade.getResult()).as("weekly result set from points sign before calcPnL").isEqualTo(WIN);

        computeUtil.calcPnL(trade);
        assertThat(trade.getExpectedPnl()).as("weekly expected = full qty × points").isEqualTo(65000.0);
    }

    // ---------------------------------------------------------------
    // Capital semantics
    // ---------------------------------------------------------------

    @Test
    @DisplayName("monthly-only close flows through the capital chain but leaves lot sizing untouched")
    void monthlyCloseSkipsLotSizing() {
        TradeCapital capital = capital(2_800_000.0, 200_000, 14);
        when(capitalRepo.getTradeCapital()).thenReturn(capital);
        Position trade = monthlyTrade(24500.0, 24600.0);
        trade.setLegs(List.of(closedLeg("NIFTY26AUG24500CE", LONG_MONTHLY, BUY, 130, 2, 212.0, 267.0)));
        trade.setActualPnl(7106.0);
        trade.setLots(2);

        computeUtil.recalculateCapital(trade);

        assertThat(trade.getStartingCapital()).isEqualTo(2_800_000.0);
        assertThat(trade.getEndingCapital()).isEqualTo(2_807_106.0);
        assertThat(capital.getCurrentCapital()).isEqualTo(2_807_106.0);
        assertThat(capital.getPossibleLots()).as("weekly-margin sizing untouched by a monthly-only close").isEqualTo(14);
    }

    @Test
    @DisplayName("a close with weekly legs still recalculates lot sizing, sized by the WEEKLY lots (regression)")
    void weeklyCloseStillSizesLots() {
        TradeCapital capital = capital(2_800_000.0, 200_000, 0);
        when(capitalRepo.getTradeCapital()).thenReturn(capital);
        Position trade = weeklyTrade(24500.0, 24600.0);
        trade.setLegs(List.of(
                closedLeg("NIFTY2681224500CE", SYNTH_WEEKLY, BUY, 650, 10, 145.0, 210.0),
                closedLeg("NIFTY2681224500PE", SYNTH_WEEKLY, SELL, 650, 10, 120.0, 154.0)));
        trade.setActualPnl(60_000.0);
        trade.setLots(10);

        computeUtil.recalculateCapital(trade);

        assertThat(capital.getPossibleLots()).as("2.86M / 200k = 14 lots > 10 traded").isEqualTo(14);
        assertThat(capital.getCurrentRiskPerLot()).isEqualTo(14);
    }

    @Test
    @DisplayName("zero or null definedRiskPerLot updates the capital chain but skips sizing instead of blowing up")
    void unsetRiskPerLotIsGuarded() {
        TradeCapital zeroRisk = capital(2_800_000.0, 0, 5);
        when(capitalRepo.getTradeCapital()).thenReturn(zeroRisk);
        Position trade = weeklyTradeWithPnl();
        trade.setActualPnl(1000.0);

        assertThatCode(() -> computeUtil.recalculateCapital(trade)).doesNotThrowAnyException();
        assertThat(zeroRisk.getCurrentCapital()).isEqualTo(2_801_000.0);
        assertThat(zeroRisk.getPossibleLots()).as("sizing left as-was").isEqualTo(5);

        TradeCapital nullRisk = capital(2_800_000.0, null, 5);
        when(capitalRepo.getTradeCapital()).thenReturn(nullRisk);
        assertThatCode(() -> computeUtil.recalculateCapital(weeklyTradeWithPnl())).doesNotThrowAnyException();
    }

    private static TradeCapital capital(Double currentCapital, Integer definedRiskPerLot, Integer possibleLots) {
        return new TradeCapital(1L, currentCapital, 5_000_000.0, definedRiskPerLot, possibleLots);
    }

    private Position weeklyTradeWithPnl() {
        Position t = weeklyTrade(24500.0, 24600.0);
        t.setLegs(List.of(
                closedLeg("NIFTY2681224500CE", SYNTH_WEEKLY, BUY, 650, 10, 145.0, 210.0),
                closedLeg("NIFTY2681224500PE", SYNTH_WEEKLY, SELL, 650, 10, 120.0, 154.0)));
        t.setActualPnl(1000.0);
        t.setLots(10);
        return t;
    }

    private static Position monthlyTrade(double entry, double exit) {
        Position t = new Position();
        t.setDirection(LONG);
        t.setEntrySpot(entry);
        t.setBaselineSpot(entry);
        t.setMonthlyBaselineSpot(entry);
        t.setExitSpot(exit);
        return t;
    }

    private static Position weeklyTrade(double entry, double exit) {
        Position t = new Position();
        t.setDirection(LONG);
        t.setEntrySpot(entry);
        t.setBaselineSpot(entry);
        t.setExitSpot(exit);
        return t;
    }

    private static WeeklyLeg closedLeg(String instrument, String book, String side, int qty, int lots,
            double openPrice, double closePrice) {
        WeeklyLeg w = new WeeklyLeg();
        w.setInstrument(instrument);
        w.setExchangeSymbol("NFO:" + instrument);
        w.setBook(book);
        w.setSide(side);
        w.setMoneyness("ATM");
        w.setQuantity(qty);
        w.setLots(lots);
        w.setStatus(CLOSED);
        if (BUY.equals(side)) {
            w.setBuyFillPrice(openPrice);
            w.setSellFillPrice(closePrice);
        } else {
            w.setSellFillPrice(openPrice);
            w.setBuyFillPrice(closePrice);
        }
        w.setOpenCharges(22.0);
        w.setCloseCharges(22.0);
        return w;
    }
}
