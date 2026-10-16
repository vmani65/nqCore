package path.to._40c.nqCore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.PENDING_OPEN;
import static path.to._40c.nqCore.util.Constants.SELL;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.entity.WeeklyLeg;
import path.to._40c.nqCore.gateway.KiteGateway;
import path.to._40c.nqCore.repo.PositionRepository;
import path.to._40c.nqCore.service.BrokerPositionReconciler.Mismatch;

/**
 * The detection safety net for the 2026-10-07 orphan: 65 NIFTY26OCT22650PE sat at the broker
 * with no app leg for seven sessions because nothing ever compared the broker's book with the
 * app's. The check must (a) report quantity the broker holds that the app does not track, and
 * escalate once it persists, (b) stay silent when the books agree, (c) report the opposite
 * shape too, (d) not count an instrument whose order is still unsettled, (e) never read an
 * unreadable broker book as flat, and (f) ignore instruments outside the app's universe.
 */
class BrokerPositionReconcilerTest {

    private static final String CE = "NIFTY26OCT22650CE";
    private static final String PE = "NIFTY26OCT22650PE";

    private KiteGateway gw;
    private PositionRepository repo;
    private BrokerPositionReconciler reconciler;

    @BeforeEach
    void setUp() {
        gw = mock(KiteGateway.class);
        repo = mock(PositionRepository.class);
        reconciler = new BrokerPositionReconciler(gw, repo);
        when(repo.findByLegStatus(anyString())).thenReturn(List.of());
    }

    @Test
    @DisplayName("2026-10-07 shape: broker holds a PE the app has no leg for → reported, escalates on the second check")
    void untrackedBrokerPositionIsReportedAndEscalates() {
        appHolds(live(CE, BUY, 65));
        when(gw.getNetPositions()).thenReturn(List.of(broker(CE, 65), broker(PE, 65)));

        List<Mismatch> first = reconciler.reconcile();
        List<Mismatch> second = reconciler.reconcile();

        assertThat(first).containsExactly(new Mismatch(PE, 65, 0, 1));
        assertThat(second).containsExactly(new Mismatch(PE, 65, 0, BrokerPositionReconciler.CONFIRMED_AFTER_CHECKS));
        assertThat(second.get(0).delta()).as("positive = untracked at the broker").isEqualTo(65);
        assertThat(reconciler.lastMismatches()).isEqualTo(second);
    }

    @Test
    @DisplayName("broker net book equal to the app's LIVE legs (incl. a flat row closed today) → no mismatch")
    void inSyncReportsNothing() {
        appHolds(live(CE, BUY, 65), live(PE, SELL, 130));
        when(gw.getNetPositions()).thenReturn(List.of(broker(CE, 65), broker(PE, -130), broker("NIFTY26OCT22400PE", 0)));

        assertThat(reconciler.reconcile()).isEmpty();
    }

    @Test
    @DisplayName("app believes it holds more than the broker → negative-delta mismatch")
    void appHoldsMoreThanBroker() {
        appHolds(live(CE, BUY, 130));
        when(gw.getNetPositions()).thenReturn(List.of(broker(CE, 65)));

        List<Mismatch> found = reconciler.reconcile();

        assertThat(found).containsExactly(new Mismatch(CE, 65, 130, 1));
        assertThat(found.get(0).delta()).isEqualTo(-65);
    }

    @Test
    @DisplayName("an instrument carrying a PENDING leg is not counted — its broker state is legitimately unknown")
    void pendingLegSkipsItsInstrument() {
        when(repo.findByLegStatus(PENDING_OPEN)).thenReturn(List.of(position(leg(PE, BUY, 65, PENDING_OPEN))));
        when(gw.getNetPositions()).thenReturn(List.of(broker(PE, 65)));

        assertThat(reconciler.reconcile()).isEmpty();
    }

    @Test
    @DisplayName("unreadable broker book is never read as flat — previous report kept, no DB scan")
    void unreadableBrokerKeepsLastReport() {
        appHolds(live(CE, BUY, 65));
        when(gw.getNetPositions()).thenReturn(List.of(broker(CE, 65), broker(PE, 65)));
        List<Mismatch> before = reconciler.reconcile();

        when(gw.getNetPositions()).thenReturn(null);
        List<Mismatch> after = reconciler.reconcile();

        assertThat(after).isEqualTo(before).isNotEmpty();
        verify(repo, times(3)).findByLegStatus(anyString()); // LIVE + two PENDING scans, first pass only
    }

    @Test
    @DisplayName("other indices and non-NFO rows are ignored; a cleared mismatch restarts its count from one")
    void filtersAndClears() {
        appHolds(live(CE, BUY, 65));
        com.zerodhatech.models.Position nxt = broker("NIFTYNXT5026OCT30000CE", 10);
        com.zerodhatech.models.Position bank = broker("BANKNIFTY26OCT50000CE", 15);
        com.zerodhatech.models.Position nse = broker("NIFTYBEES", 100);
        nse.exchange = "NSE";
        when(gw.getNetPositions()).thenReturn(List.of(broker(CE, 65), nxt, bank, nse, broker(PE, 65)));
        assertThat(reconciler.reconcile()).containsExactly(new Mismatch(PE, 65, 0, 1));

        when(gw.getNetPositions()).thenReturn(List.of(broker(CE, 65)));
        assertThat(reconciler.reconcile()).isEmpty();

        when(gw.getNetPositions()).thenReturn(List.of(broker(CE, 65), broker(PE, 65)));
        assertThat(reconciler.reconcile()).containsExactly(new Mismatch(PE, 65, 0, 1));
    }

    private void appHolds(WeeklyLeg... legs) {
        when(repo.findByLegStatus(LIVE)).thenReturn(List.of(position(legs)));
    }

    private static Position position(WeeklyLeg... legs) {
        Position p = new Position();
        p.setStatus(LIVE);
        p.setLegs(List.of(legs));
        return p;
    }

    private static WeeklyLeg live(String instrument, String side, int qty) {
        return leg(instrument, side, qty, LIVE);
    }

    private static WeeklyLeg leg(String instrument, String side, int qty, String status) {
        WeeklyLeg w = new WeeklyLeg();
        w.setInstrument(instrument);
        w.setExchangeSymbol("NFO:" + instrument);
        w.setBook(LONG_MONTHLY);
        w.setSide(side);
        w.setQuantity(qty);
        w.setLots(qty / 65);
        w.setStatus(status);
        return w;
    }

    private static com.zerodhatech.models.Position broker(String symbol, int netQty) {
        com.zerodhatech.models.Position p = new com.zerodhatech.models.Position();
        p.tradingSymbol = symbol;
        p.exchange = "NFO";
        p.product = "NRML";
        p.netQuantity = netQty;
        return p;
    }
}
