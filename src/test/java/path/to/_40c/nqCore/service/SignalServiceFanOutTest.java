package path.to._40c.nqCore.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static path.to._40c.nqCore.util.Constants.CE;
import static path.to._40c.nqCore.util.Constants.CLOSED;
import static path.to._40c.nqCore.util.Constants.LIVE;
import static path.to._40c.nqCore.util.Constants.LONG_MONTHLY;
import static path.to._40c.nqCore.util.Constants.SYNTH_WEEKLY;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import path.to._40c.nqCore.controller.SignalController.Signal;
import path.to._40c.nqCore.entity.Position;
import path.to._40c.nqCore.repo.PositionRepository;

/**
 * Business rules of the per-book signal fan-out:
 * - every signal reaches BOTH books, SYNTH_WEEKLY strictly before LONG_MONTHLY;
 * - an exception inside one book's execution never blocks the other book;
 * - toggles gate NEW positions only — a disabled book still receives exits, and a flip
 *   on a disabled book degrades to close-only (manage to natural close, open nothing);
 * - both roll triggers are mirror twins: sync the book's symbol row first, then roll
 *   that book only, isolating any failure.
 * All broker work is behind mocked opening/closing services — this is pure orchestration.
 */
class SignalServiceFanOutTest {

    private PositionOpenService openingService;
    private PositionCloseService closingService;
    private PositionRolloverService rollOverService;
    private PostTradeService postTradeService;
    private BookConfigService bookConfig;
    private WeeklySymbolService weeklySymbolService;
    private MonthlySymbolService monthlySymbolService;
    private PositionRepository positionRepository;
    private SignalService service;

    private final Position weeklyLive = livePosition(1L);
    private final Position monthlyLive = livePosition(2L);

    /**
     * interleaveAvailable() defaults to false on the mock, so fan-out tests exercise the legacy
     * paths.
     */
    @BeforeEach
    void setUp() {
        openingService = mock(PositionOpenService.class);
        closingService = mock(PositionCloseService.class);
        rollOverService = mock(PositionRolloverService.class);
        postTradeService = mock(PostTradeService.class);
        bookConfig = mock(BookConfigService.class);
        weeklySymbolService = mock(WeeklySymbolService.class);
        monthlySymbolService = mock(MonthlySymbolService.class);
        positionRepository = mock(PositionRepository.class);
        service = new SignalService(openingService, closingService, rollOverService, postTradeService,
                positionRepository, weeklySymbolService, bookConfig, monthlySymbolService,
                mock(MonthlyFlipService.class));

        when(bookConfig.isEnabled(SYNTH_WEEKLY)).thenReturn(true);
        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(true);
        when(openingService.prepareWeeklyOpen(anyString(), anyString(), any(Position.class)))
                .thenReturn(new PositionOpenService.OpenPrep(List.of(), Map.of()));
        when(openingService.openTrade(anyString(), anyString(), any(Position.class), any()))
                .thenReturn(weeklyLive);
        when(openingService.openWeeklyTrade(anyString(), anyString(), any(Position.class)))
                .thenReturn(weeklyLive);
        when(openingService.openMonthlyTrade(anyString(), anyString(), any(Position.class)))
                .thenReturn(monthlyLive);
    }

    @Test
    @DisplayName("flip with both books enabled: weekly flips first, then monthly flips — each via its own book methods")
    void flipFansOutToBothBooksWeeklyFirst() {
        service.handleFlip("24500", CE, signal("flip"));

        InOrder order = inOrder(closingService, openingService);
        order.verify(closingService).closeWeeklyTrade(eq("24500"), any(Signal.class), eq(false));
        order.verify(closingService).closeMonthlyTrade(eq("24500"), any(Signal.class), eq(false));
        order.verify(openingService).openMonthlyTrade(eq("24500"), eq(CE), any(Position.class));
        verify(openingService).openTrade(eq("24500"), eq(CE), any(Position.class), any());
    }

    @Test
    @DisplayName("flip with LONG_MONTHLY disabled: monthly degrades to close-only, opens nothing")
    void flipOnDisabledMonthlyClosesOnly() {
        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(false);
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(monthlyLive);

        service.handleFlip("24500", CE, signal("flip"));

        verify(closingService).closeMonthlyTrade(eq("24500"), any(Signal.class), eq(true));
        verify(openingService, never()).openMonthlyTrade(anyString(), anyString(), any(Position.class));
        verify(postTradeService).afterClose(monthlyLive);
        verify(openingService).openTrade(eq("24500"), eq(CE), any(Position.class), any());
    }

    @Test
    @DisplayName("a weekly-book failure is isolated: the monthly book still executes the flip")
    void weeklyFailureNeverBlocksMonthly() {
        when(closingService.closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean()))
                .thenThrow(new RuntimeException("weekly quote outage"));

        boolean result = service.handleFlip("24500", CE, signal("flip"));

        assertThat(result).isTrue();
        verify(closingService).closeMonthlyTrade(eq("24500"), any(Signal.class), eq(false));
        verify(openingService).openMonthlyTrade(eq("24500"), eq(CE), any(Position.class));
    }

    @Test
    @DisplayName("a monthly-book failure (e.g. unconfigured book) is isolated and the signal still succeeds")
    void monthlyFailureNeverBlocksSignal() {
        when(openingService.openMonthlyTrade(anyString(), anyString(), any(Position.class)))
                .thenThrow(new IllegalStateException("LONG_MONTHLY has no monthly symbol configured"));

        boolean result = service.handleTradeOpen("24500", CE, signal("longEntry"));

        assertThat(result).isTrue();
        verify(openingService).openWeeklyTrade(eq("24500"), eq(CE), any(Position.class));
        verify(postTradeService).afterOpen(weeklyLive);
    }

    @Test
    @DisplayName("entry with LONG_MONTHLY disabled opens weekly only — no monthly orphan sweep, no monthly open")
    void entrySkipsDisabledMonthly() {
        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(false);

        service.handleTradeOpen("24500", CE, signal("longEntry"));

        verify(openingService).openWeeklyTrade(eq("24500"), eq(CE), any(Position.class));
        verify(openingService, never()).openMonthlyTrade(anyString(), anyString(), any(Position.class));
        verify(closingService, never()).closeMonthlyOrphanIfAny(anyString(), any(Signal.class));
    }

    @Test
    @DisplayName("entry fan-out sweeps each book's own orphans before its open")
    void entrySweepsPerBookOrphans() {
        service.handleTradeOpen("24500", CE, signal("longEntry"));

        InOrder weekly = inOrder(closingService, openingService);
        weekly.verify(closingService).closeWeeklyOrphanIfAny(eq("24500"), any(Signal.class));
        weekly.verify(openingService).openWeeklyTrade(eq("24500"), eq(CE), any(Position.class));
        InOrder monthly = inOrder(closingService, openingService);
        monthly.verify(closingService).closeMonthlyOrphanIfAny(eq("24500"), any(Signal.class));
        monthly.verify(openingService).openMonthlyTrade(eq("24500"), eq(CE), any(Position.class));
    }

    @Test
    @DisplayName("monthly opens run the DTE roll check first; a disabled monthly book never does")
    void monthlyOpenRunsRollCheckFirst() {
        service.handleTradeOpen("24500", CE, signal("longEntry"));
        InOrder order = inOrder(monthlySymbolService, openingService);
        order.verify(monthlySymbolService).syncTradedContract();
        order.verify(openingService).openMonthlyTrade(eq("24500"), eq(CE), any(Position.class));

        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(false);
        service.handleFlip("24501", CE, signal("flip"));
        verify(monthlySymbolService).syncTradedContract();
    }

    @Test
    @DisplayName("close reaches BOTH books even when both toggles are off — open positions are managed to natural close")
    void closeRunsForDisabledBooks() {
        when(bookConfig.isEnabled(SYNTH_WEEKLY)).thenReturn(false);
        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(false);
        when(closingService.closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(weeklyLive);
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(monthlyLive);

        service.handleTradeClose("24500", "PE", signal("shortExit"));

        verify(closingService).closeWeeklyTrade(eq("24500"), any(Signal.class), eq(true));
        verify(closingService).closeMonthlyTrade(eq("24500"), any(Signal.class), eq(true));
        verify(postTradeService).afterClose(weeklyLive);
        verify(postTradeService).afterClose(monthlyLive);
    }

    @Test
    @DisplayName("a weekly close failure still lets the monthly close run")
    void closeIsolatesWeeklyFailure() {
        when(closingService.closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean()))
                .thenThrow(new RuntimeException("kite down"));
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(monthlyLive);

        boolean result = service.handleTradeClose("24500", "PE", signal("shortExit"));

        assertThat(result).isTrue();
        verify(closingService).closeMonthlyTrade(eq("24500"), any(Signal.class), eq(true));
        verify(postTradeService).afterClose(monthlyLive);
    }

    @Test
    @DisplayName("the weekly rollover trigger syncs the traded contract first, rolls weekly only, and never throws")
    void weeklyRolloverTriggerIsWeeklyOnly() {
        boolean ok = service.handleWeeklyRollOver("24500");

        assertThat(ok).isTrue();
        InOrder order = inOrder(weeklySymbolService, rollOverService);
        order.verify(weeklySymbolService).syncTradedContract();
        order.verify(rollOverService).rollOverWeekly("24500");
        verify(rollOverService, never()).rollOverMonthly(anyString());

        org.mockito.Mockito.doThrow(new IllegalStateException("weekly symbol missing"))
                .when(rollOverService).rollOverWeekly(anyString());
        assertThat(service.handleWeeklyRollOver("24501")).isFalse();
    }

    @Test
    @DisplayName("the monthly rollover trigger syncs the traded contract first, rolls monthly only, and never throws")
    void monthlyRolloverTriggerIsMonthlyOnly() {
        boolean ok = service.handleMonthlyRollOver("24500");

        assertThat(ok).isTrue();
        InOrder order = inOrder(monthlySymbolService, rollOverService);
        order.verify(monthlySymbolService).syncTradedContract();
        order.verify(rollOverService).rollOverMonthly("24500");
        verify(rollOverService, never()).rollOverWeekly(anyString());

        org.mockito.Mockito.doThrow(new IllegalStateException("no monthly templates"))
                .when(rollOverService).rollOverMonthly(anyString());
        assertThat(service.handleMonthlyRollOver("24501")).isFalse();
    }

    @Test
    @DisplayName("ONE position per signal: both books' opens receive the SAME Position instance")
    void entryFanOutSharesOneRow() {
        org.mockito.ArgumentCaptor<Position> weeklyArg = org.mockito.ArgumentCaptor.forClass(Position.class);
        org.mockito.ArgumentCaptor<Position> monthlyArg = org.mockito.ArgumentCaptor.forClass(Position.class);

        service.handleTradeOpen("24500", CE, signal("longEntry"));

        verify(openingService).openWeeklyTrade(eq("24500"), eq(CE), weeklyArg.capture());
        verify(openingService).openMonthlyTrade(eq("24500"), eq(CE), monthlyArg.capture());
        assertThat(weeklyArg.getValue()).isSameAs(monthlyArg.getValue());
    }

    @Test
    @DisplayName("ONE position per signal: the flip's weekly prep and monthly open share the new row")
    void flipFanOutSharesOneNewRow() {
        org.mockito.ArgumentCaptor<Position> prepArg = org.mockito.ArgumentCaptor.forClass(Position.class);
        org.mockito.ArgumentCaptor<Position> monthlyArg = org.mockito.ArgumentCaptor.forClass(Position.class);

        service.handleFlip("24500", CE, signal("flip"));

        verify(openingService).prepareWeeklyOpen(eq("24500"), eq(CE), prepArg.capture());
        verify(openingService).openMonthlyTrade(eq("24500"), eq(CE), monthlyArg.capture());
        assertThat(prepArg.getValue()).isSameAs(monthlyArg.getValue());
    }

    @Test
    @DisplayName("post-trade runs ONCE per row: both books closing the same row triggers a single afterClose")
    void sharedClosedRowGetsOneAfterClose() {
        Position sharedRow = livePosition(7L);
        when(closingService.closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(sharedRow);
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(sharedRow);

        service.handleTradeClose("24500", "PE", signal("shortExit"));

        verify(postTradeService, org.mockito.Mockito.times(1)).afterClose(sharedRow);
    }

    @Test
    @DisplayName("open-buffer callback closes BOTH books at nQTicker's price, post-trade once per shared row")
    void executeCloseImmediateClosesBothBooks() {
        Position sharedRow = livePosition(9L);
        when(closingService.closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(sharedRow);
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(sharedRow);

        service.executeCloseImmediate("24621");

        verify(closingService).closeWeeklyTrade(eq("24621"), any(Signal.class), eq(true));
        verify(closingService).closeMonthlyTrade(eq("24621"), any(Signal.class), eq(true));
        verify(postTradeService, org.mockito.Mockito.times(1)).afterClose(sharedRow);
    }

    @Test
    @DisplayName("open-buffer callback isolates a weekly failure: the monthly book still closes")
    void executeCloseImmediateIsolatesWeeklyFailure() {
        when(closingService.closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean()))
                .thenThrow(new RuntimeException("kite down"));
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(monthlyLive);

        service.executeCloseImmediate("24621");

        verify(closingService).closeMonthlyTrade(eq("24621"), any(Signal.class), eq(true));
        verify(postTradeService).afterClose(monthlyLive);
    }

    @Test
    @DisplayName("startup seed is the newest row by id — an older row stuck LIVE must never shadow it")
    void lastTradeIsNewestRowNotNewestLive() {
        Position newest = livePosition(100L);
        newest.setStatus(CLOSED);
        when(positionRepository.findFirstByOrderByIdDesc()).thenReturn(newest);

        assertThat(service.getLastTrade()).isSameAs(newest);
        verify(positionRepository, never()).findFirstByStatusOrderByIdDesc(anyString());
    }

    @Test
    @DisplayName("weekly rollover trigger on a disabled book: symbol sync still runs, the position roll is skipped")
    void weeklyRolloverSkipsPositionRollWhenDisabled() {
        when(bookConfig.isEnabled(SYNTH_WEEKLY)).thenReturn(false);

        assertThat(service.handleWeeklyRollOver("24500")).isTrue();

        verify(weeklySymbolService).syncTradedContract();
        verify(rollOverService, never()).rollOverWeekly(anyString());
    }

    @Test
    @DisplayName("monthly rollover trigger on a disabled book: symbol sync still runs, the position roll is skipped")
    void monthlyRolloverSkipsPositionRollWhenDisabled() {
        when(bookConfig.isEnabled(LONG_MONTHLY)).thenReturn(false);

        assertThat(service.handleMonthlyRollOver("24500")).isTrue();

        verify(monthlySymbolService).syncTradedContract();
        verify(rollOverService, never()).rollOverMonthly(anyString());
    }

    @Test
    @DisplayName("a weekly symbol-sync failure on the close path never blocks post-trade accounting")
    void symbolSyncFailureNeverBlocksCloseAccounting() {
        org.mockito.Mockito.doThrow(new RuntimeException("sqlite busy"))
                .when(weeklySymbolService).syncTradedContract();
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(monthlyLive);

        boolean ok = service.handleTradeClose("24500", "PE", signal("shortExit"));

        assertThat(ok).isTrue();
        verify(postTradeService).afterClose(monthlyLive);
    }

    @Test
    @DisplayName("open-buffer fallback with nothing armed does nothing")
    void openBufferFallbackIdleWhenNothingArmed() {
        service.openBufferFallback();

        verify(closingService, never()).closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean());
        verify(closingService, never()).closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean());
    }

    @Test
    @DisplayName("open-buffer fallback closes both books when today's arm was never called back — and is one-shot")
    void openBufferFallbackClosesBothBooksOnce() throws Exception {
        armBuffer("24621", java.time.LocalDate.now(java.time.ZoneId.of(path.to._40c.nqCore.util.Constants.ZONE_ID)));
        when(closingService.closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean())).thenReturn(monthlyLive);

        service.openBufferFallback();
        service.openBufferFallback();

        verify(closingService, org.mockito.Mockito.times(1)).closeWeeklyTrade(eq("24621"), any(Signal.class), eq(true));
        verify(closingService, org.mockito.Mockito.times(1)).closeMonthlyTrade(eq("24621"), any(Signal.class), eq(true));
    }

    @Test
    @DisplayName("open-buffer fallback ignores a stale arm from a previous day")
    void openBufferFallbackIgnoresStaleArm() throws Exception {
        armBuffer("24621", java.time.LocalDate.now(java.time.ZoneId.of(path.to._40c.nqCore.util.Constants.ZONE_ID)).minusDays(1));

        service.openBufferFallback();

        verify(closingService, never()).closeWeeklyTrade(anyString(), any(Signal.class), anyBoolean());
        verify(closingService, never()).closeMonthlyTrade(anyString(), any(Signal.class), anyBoolean());
    }

    /** Arms the private one-shot buffer state the way a delegated 9:15 longExit would. */
    private void armBuffer(String price, java.time.LocalDate day) throws Exception {
        Class<?> cls = Class.forName("path.to._40c.nqCore.service.SignalService$ArmedBuffer");
        java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(String.class, java.time.LocalDate.class);
        ctor.setAccessible(true);
        Object armed = ctor.newInstance(price, day);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<Object> ref = (java.util.concurrent.atomic.AtomicReference<Object>)
                org.springframework.test.util.ReflectionTestUtils.getField(service, "armedBuffer");
        ref.set(armed);
    }

    private static Signal signal(String action) {
        return new Signal("RIDETHETIDE", action, action.startsWith("short") ? "PE" : CE, "07-Aug-2026 10.30.00 AM", "24500");
    }

    private static Position livePosition(long id) {
        Position p = new Position();
        p.setStatus(LIVE);
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", id);
        return p;
    }
}
