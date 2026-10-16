package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.PARTIAL;
import static path.to._40c.nqCore.util.Constants.SELL;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.TradeCapital;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.repo.TradeCapitalRepository;
import path.to._40c.nqCore.util.ComputeUtil;
import path.to._40c.nqCore.util.PositionUtil;

/**
 * The once-per-signal accounting lifecycle on the shared row, end to end through the REAL
 * ComputeUtil math:
 * - a mixed close (weekly CLOSED, monthly still LIVE — the 9:15 split or a mid-fan-out
 *   call) enriches but defers ALL accounting: no pointsPnl, no capital stamp;
 * - once the last leg closes, ONE afterClose pass computes the cumulative P&L across both
 *   books and advances the capital chain exactly once;
 * - a re-run on the already-accounted row must NOT advance the chain again;
 * - an explicit PARTIAL (reconciler-stamped revived orphan) is never downgraded to LIVE
 *   by afterClose's status self-correction.
 */
class PostTradeServiceSharedRowTest {

    private PositionRepository positionRepo;
    private TradeCapitalRepository capitalRepo;
    private TradeCapital capital;
    private PostTradeService service;
    private Position row;
    private WeeklyLeg weeklyCe;
    private WeeklyLeg weeklyPe;
    private WeeklyLeg monthlyCe;

    @BeforeEach
    void setUp() {
        positionRepo = mock(PositionRepository.class);
        capitalRepo = mock(TradeCapitalRepository.class);
        PositionUtil util = mock(PositionUtil.class);
        ComputeUtil compute = new ComputeUtil(mock(WeeklySymbolCache.class), mock(MonthlySymbolCache.class),
                mock(LegTemplateCache.class), capitalRepo);
        service = new PostTradeService(util, compute, positionRepo);

        capital = new TradeCapital(1L, 2_800_000.0, 5_000_000.0, 200_000, 0);
        when(capitalRepo.getTradeCapital()).thenReturn(capital);
        when(positionRepo.save(any(Position.class))).thenAnswer(inv -> inv.getArgument(0));

        weeklyCe = closedLeg("NIFTY2681224500CE", SYNTH_WEEKLY, BUY, 650, 10, 145.0, 210.0);
        weeklyPe = closedLeg("NIFTY2681224500PE", SYNTH_WEEKLY, SELL, 650, 10, 120.0, 154.0);
        monthlyCe = closedLeg("NIFTY26AUG24500CE", LONG_MONTHLY, BUY, 130, 2, 212.0, 267.0);
        monthlyCe.setStatus(LIVE);

        row = new Position();
        ReflectionTestUtils.setField(row, "id", 42L);
        row.setDirection(LONG);
        row.setStatus(LIVE);
        row.setEntrySpot(24500.0);
        row.setBaselineSpot(24500.0);
        row.setMonthlyBaselineSpot(24500.0);
        row.setExitSpot(24600.0);
        row.setLegs(List.of(weeklyCe, weeklyPe, monthlyCe));
        when(positionRepo.findById(42L)).thenReturn(Optional.of(row));
    }

    @Test
    @DisplayName("weekly closed, monthly still LIVE: accounting deferred — no points, no capital, row stays LIVE")
    void nonTerminalRowDefersAccounting() {
        service.afterClose(row);

        assertThat(row.getStatus()).isEqualTo(LIVE);
        assertThat(row.getPointsPnl()).isNull();
        assertThat(row.getStartingCapital()).isNull();
        assertThat(row.getEndingCapital()).isNull();
        assertThat(row.getClosedAt()).isNull();
        assertThat(capital.getCurrentCapital()).isEqualTo(2_800_000.0);
    }

    /**
     * weekly gross 650×65 − 650×34 = 20150; monthly gross 130×55 = 7150; charges 3×44 = 132
     */
    @Test
    @DisplayName("last leg closed: ONE pass computes cumulative two-book P&L and advances the capital chain once")
    void terminalRowAccountsOnce() {
        monthlyCe.setStatus(CLOSED);

        service.afterClose(row);

        assertThat(row.getStatus()).isEqualTo(CLOSED);
        assertThat(row.getPointsPnl()).isEqualTo(100.0);
        assertThat(row.getActualPnl()).isEqualTo(20150.0 + 7150.0 - 132.0);
        assertThat(row.getExpectedPnl()).as("weekly 650×100 + monthly 65×100").isEqualTo(71500.0);
        assertThat(row.getLots()).as("cumulative lots across books").isEqualTo(12);
        assertThat(row.getStartingCapital()).isEqualTo(2_800_000.0);
        assertThat(row.getEndingCapital()).isEqualTo(2_800_000.0 + 27168.0);
        assertThat(capital.getCurrentCapital()).isEqualTo(2_827_168.0);
        assertThat(row.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("re-running afterClose on an accounted row never advances the capital chain again")
    void reRunDoesNotDoubleAdvanceCapital() {
        monthlyCe.setStatus(CLOSED);
        service.afterClose(row);
        double after = capital.getCurrentCapital();

        service.afterClose(row);

        assertThat(capital.getCurrentCapital()).isEqualTo(after);
        assertThat(row.getStartingCapital()).isEqualTo(2_800_000.0);
        assertThat(row.getEndingCapital()).isEqualTo(after);
    }

    @Test
    @DisplayName("an explicit PARTIAL (revived orphan) is not downgraded to LIVE by the status self-correction")
    void partialIsNotDowngraded() {
        row.setStatus(PARTIAL);

        service.afterClose(row);

        assertThat(row.getStatus()).isEqualTo(PARTIAL);
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
