package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.pojo.EquityCurve;
import path.to._40c.nqCore.repo.PositionRepository;

/**
 * The equity curve under one-position-per-signal: the "All" curve is one point per signal
 * off the row's capital chain (the owner's stated requirement), and a single-book view is
 * a synthetic cumulative chain of that book's OWN leg P&L — the account capital chain is
 * signal-level, so a per-book capital chain no longer exists to plot.
 */
class EquityCurveServiceBookTest {

    private PositionRepository repo;
    private EquityCurveService service;

    @BeforeEach
    void setUp() {
        repo = mock(PositionRepository.class);
        service = new EquityCurveService(repo);
    }

    @Test
    @DisplayName("All view: a two-book signal is ONE equity point using the row's capital chain")
    void allViewIsOnePointPerSignal() {
        Position signal = sharedRow("01-08-2026 10:00:00.000", 2_800_000.0, 2_827_168.0);
        when(repo.findAllByOrderByOpenedAtAsc()).thenReturn(List.of(signal));

        EquityCurve curve = service.getEquityCurveData("All", "All");

        assertThat(curve.getEquity()).containsExactly(2_827_168.0);
        assertThat(curve.getStartingCapitals()).containsExactly(2_800_000.0);
    }

    /**
     * weekly legs: 650×(210−145) − 650×(154−120) = 20150 gross − 88 charges
     * <p>monthly leg: 130×(267−212) = 7150 gross − 44 charges
     */
    @Test
    @DisplayName("book view selects rows by that book's legs and accumulates ONLY that book's leg P&L")
    void bookViewIsLegScoped() {
        Position signal = sharedRow("01-08-2026 10:00:00.000", 2_800_000.0, 2_827_168.0);
        when(repo.findAllByOrderByOpenedAtAsc()).thenReturn(List.of(signal));

        EquityCurve weekly = service.getEquityCurveData("All", SYNTH_WEEKLY);
        assertThat(weekly.getEquity()).containsExactly(2_800_000.0 + 20150.0 - 88.0);

        EquityCurve monthly = service.getEquityCurveData("All", LONG_MONTHLY);
        assertThat(monthly.getEquity()).containsExactly(2_800_000.0 + 7150.0 - 44.0);
    }

    @Test
    @DisplayName("a book view excludes signals where that book never traded")
    void bookViewExcludesUntradedSignals() {
        Position weeklyOnly = new Position();
        weeklyOnly.setOpenedAt("01-08-2026 10:00:00.000");
        weeklyOnly.setStartingCapital(2_800_000.0);
        weeklyOnly.setEndingCapital(2_810_000.0);
        weeklyOnly.setLegs(List.of(leg("NIFTY2681224500CE", SYNTH_WEEKLY, 650, 10, 145.0, 210.0)));
        when(repo.findAllByOrderByOpenedAtAsc()).thenReturn(List.of(weeklyOnly));

        EquityCurve monthly = service.getEquityCurveData("All", LONG_MONTHLY);

        assertThat(monthly.getEquity()).isEmpty();
    }

    private static Position sharedRow(String openedAt, double starting, double ending) {
        Position p = new Position();
        p.setOpenedAt(openedAt);
        p.setStartingCapital(starting);
        p.setEndingCapital(ending);
        WeeklyLeg ce = leg("NIFTY2681224500CE", SYNTH_WEEKLY, 650, 10, 145.0, 210.0);
        WeeklyLeg pe = leg("NIFTY2681224500PE", SYNTH_WEEKLY, 650, 10, 154.0, 120.0);
        pe.setActualPnl(650.0 * (120.0 - 154.0));
        WeeklyLeg m = leg("NIFTY26AUG24500CE", LONG_MONTHLY, 130, 2, 212.0, 267.0);
        p.setLegs(List.of(ce, pe, m));
        return p;
    }

    private static WeeklyLeg leg(String instrument, String book, int qty, int lots, double buy, double sell) {
        WeeklyLeg w = new WeeklyLeg();
        w.setInstrument(instrument);
        w.setBook(book);
        w.setSide(BUY);
        w.setQuantity(qty);
        w.setLots(lots);
        w.setStatus(CLOSED);
        w.setBuyFillPrice(buy);
        w.setSellFillPrice(sell);
        w.setActualPnl(qty * (sell - buy));
        w.setOpenCharges(22.0);
        w.setCloseCharges(22.0);
        return w;
    }
}
