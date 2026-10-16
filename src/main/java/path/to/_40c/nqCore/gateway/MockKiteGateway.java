package path.to._40c.nqCore.gateway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static path.to._40c.nqCore.util.Constants.BUY;
import static path.to._40c.nqCore.util.Constants.CE;
import static path.to._40c.nqCore.util.Constants.SELL;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.CombinedMarginData;
import com.zerodhatech.models.ContractNote;
import com.zerodhatech.models.ContractNoteParams;
import com.zerodhatech.models.Depth;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.MarketDepth;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import com.zerodhatech.models.Quote;
import com.zerodhatech.models.User;

import static com.zerodhatech.kiteconnect.utils.Constants.ORDER_CANCELLED;
import static com.zerodhatech.kiteconnect.utils.Constants.ORDER_COMPLETE;

/**
 * Mock KiteGateway for local testing (active when spring.profiles.active=mock).
 * Simulates spot drift, slippage, margin/brokerage, and MOCK-* order IDs so flows
 * exercise end-to-end without hitting Zerodha.
 */
@Service
@Profile("mock")
@Slf4j
public class MockKiteGateway implements KiteGateway {
    /** Parses trailing strike+option-type from instrument symbols: e.g. NIFTY24D1922500CE → 22500/CE. */
    private static final Pattern STRIKE_PATTERN = Pattern.compile("(\\d{4,6})(CE|PE)$");

    private static final AtomicLong orderCounter = new AtomicLong(1000);
    private final Random random = new Random();

    /** Execution price recorded per mock order ID; replayed by getOrderTrades for consistency. */
    private final ConcurrentHashMap<String, Double> executionPrices = new ConcurrentHashMap<>();

    /**
     * Full per-order state (qty, side, price, lifecycle) so getOrderHistory / getOrderTrades echo
     * the REAL requested quantity instead of the historical hardcoded 65 — multi-lot and sliced
     * flows (PATIENT_EXECUTION_PLAN.md §7.1) are untestable without it. Unknown ids keep the
     * legacy 65-qty fallback so nothing placed outside placeOrder changes behavior.
     */
    private final ConcurrentHashMap<String, MockOrderState> orders = new ConcurrentHashMap<>();

    /**
     * Fill behavior for newly placed orders (mock.fill-mode): INSTANT (default, legacy behavior),
     * NEVER (orders rest OPEN until cancelled — drives the patient REST → PENDING_CLOSE
     * click-through), or AFTER_MODIFIES:n (fills once modifyOrder has been called n times —
     * walks the patient concession schedule visibly before filling).
     */
    @org.springframework.beans.factory.annotation.Value("${mock.fill-mode:INSTANT}")
    private String fillModeRaw;
    private volatile FillMode fillMode = FillMode.INSTANT;
    private volatile int fillAfterModifies = 0;

    private enum FillMode { INSTANT, NEVER, AFTER_MODIFIES }

    /** Lifecycle of one mock order: OPEN → COMPLETE (fill) or CANCELLED. */
    private static final class MockOrderState {
        final String symbol;
        final String txn;
        final int qty;
        final String tag;
        volatile double execPrice;
        volatile String status;
        volatile int modifies;

        MockOrderState(String symbol, String txn, int qty, String tag, double execPrice, String status) {
            this.symbol = symbol;
            this.txn = txn;
            this.qty = qty;
            this.tag = tag;
            this.execPrice = execPrice;
            this.status = status;
        }
    }

    /** Parses mock.fill-mode at startup; invalid values degrade to INSTANT. */
    @jakarta.annotation.PostConstruct
    void initFillMode() {
        setFillMode(fillModeRaw);
    }

    /** Runtime hook for E2E scripting: "INSTANT", "NEVER", or "AFTER_MODIFIES:2". */
    public void setFillMode(String raw) {
        try {
            String s = raw == null ? "INSTANT" : raw.trim().toUpperCase();
            if (s.startsWith("AFTER_MODIFIES")) {
                fillAfterModifies = Integer.parseInt(s.substring(s.indexOf(':') + 1).trim());
                fillMode = FillMode.AFTER_MODIFIES;
            } else {
                fillMode = FillMode.valueOf(s);
            }
            log.info("[MOCK] fill-mode = {}{}", fillMode,
                    fillMode == FillMode.AFTER_MODIFIES ? ":" + fillAfterModifies : "");
        } catch (Exception e) {
            fillMode = FillMode.INSTANT;
            log.error("[MOCK] invalid mock.fill-mode '{}' — using INSTANT", raw);
        }
    }

    /** Mock WS counterpart — used by placeOrder to inject synthetic fill events that
     *  drive PositionUtil.placeGraduatedLimit's D₂ WS-await path. */
    private final MockKiteOrderStream orderStream;

    public MockKiteGateway(MockKiteOrderStream orderStream) {
        this.orderStream = orderStream;
    }

    /** Simulated NIFTY spot — drifts ±50 per getLTP call, clamped to [23000, 26000]. */
    private volatile double niftySpot = 24500.0;

    @Override
    public Map<String, LTPQuote> getLTP(String[] instruments) {
        niftySpot = Math.max(23000, Math.min(26000, niftySpot + (random.nextDouble() * 100 - 50)));
        double spot = Math.round(niftySpot * 100.0) / 100.0;

        Map<String, LTPQuote> result = new HashMap<>();
        for (String ins : instruments) {
            double price = calcOptionLTP(ins, spot);
            LTPQuote quote = new LTPQuote();
            quote.lastPrice = price;
            result.put(ins, quote);
            log.info("[MOCK] getLTP: {} | spot={} → lastPrice={}", ins, spot, price);
        }
        return result;
    }

    /**
     * Approximates weekly NIFTY option price as intrinsic + time-value × ATM-decay.
     * Time value 80–130 pts at ATM (≈16% IV, 1-2d expiry); decay = exp(-|spot-strike|/200)
     * so 50-pt OTM retains ~78%, 100-pt OTM ~61%. Clamped to [5, 800].
     */
    private double calcOptionLTP(String instrument, double spot) {
        Matcher m = STRIKE_PATTERN.matcher(instrument);
        if (!m.find()) {
            return Math.round((90 + random.nextDouble() * 40) * 100.0) / 100.0;
        }
        double strike = Double.parseDouble(m.group(1));
        boolean isCE = CE.equals(m.group(2));

        double intrinsic  = isCE ? Math.max(0, spot - strike) : Math.max(0, strike - spot);
        double timeValue  = 80 + random.nextDouble() * 50;
        double atmDecay   = Math.exp(-Math.abs(spot - strike) / 200.0);
        double price      = intrinsic + timeValue * atmDecay;
        price = Math.max(5.0, Math.min(800.0, price));
        return Math.round(price * 100.0) / 100.0;
    }

    @Override
    public List<Order> getOrderHistory(String orderId) {
        Order o = new Order();
        o.orderId = orderId;
        MockOrderState st = orders.get(orderId);
        if (st != null) {
            boolean complete = ORDER_COMPLETE.equals(st.status);
            o.status = st.status;
            o.averagePrice = complete ? String.valueOf(st.execPrice) : "0";
            o.filledQuantity = complete ? String.valueOf(st.qty) : "0";
            o.quantity = String.valueOf(st.qty);
            o.pendingQuantity = complete ? "0" : String.valueOf(st.qty);
        } else {
            o.status = ORDER_COMPLETE;
            Double execPrice = executionPrices.get(orderId);
            o.averagePrice = execPrice != null ? String.valueOf(execPrice) : "0";
            o.filledQuantity = "65";
            o.quantity = "65";
            o.pendingQuantity = "0";
        }
        log.info("[MOCK] getOrderHistory: orderId={} → status={} filled={}/{}",
                orderId, o.status, o.filledQuantity, o.quantity);
        return List.of(o);
    }

    /**
     * Places a mock order and records it in the ledger. In INSTANT fill mode the order completes
     * at once and a synthetic terminal-fill event is injected (D₂) so the WS-await path completes
     * within ~50ms, mirroring real Kite WS behavior; the other fill modes leave it OPEN.
     */
    @Override
    public OrderResponse placeOrder(OrderParams params, String variety) {
        String orderId = "MOCK-" + orderCounter.getAndIncrement();
        double base = params.price != null && params.price > 0 ? params.price
                : calcOptionLTP(params.tradingsymbol, niftySpot);
        double execPrice = applySlippage(base, params.transactionType);
        executionPrices.put(orderId, execPrice);
        boolean instant = fillMode == FillMode.INSTANT;
        orders.put(orderId, new MockOrderState(params.tradingsymbol, params.transactionType,
                params.quantity, params.tag, execPrice, instant ? ORDER_COMPLETE : "OPEN"));
        log.info("[MOCK] placeOrder: symbol={} type={} qty={} price={} execPrice={} mode={} → orderId={}",
                params.tradingsymbol, params.transactionType, params.quantity,
                params.price, execPrice, fillMode, orderId);
        OrderResponse response = new OrderResponse();
        response.orderId = orderId;
        if (instant) {
            orderStream.scheduleSyntheticFill(orderId, params.tradingsymbol,
                    params.transactionType, params.quantity, execPrice);
        }
        return response;
    }

    @Override
    public Map<String, Quote> getQuote(String[] instruments) {
        niftySpot = Math.max(23000, Math.min(26000, niftySpot + (random.nextDouble() * 100 - 50)));
        double spot = Math.round(niftySpot * 100.0) / 100.0;
        Map<String, Quote> result = new HashMap<>();
        for (String ins : instruments) {
            double mid = calcOptionLTP(ins, spot);
            double halfSpread = Math.max(0.5, mid * 0.005);
            Quote q = new Quote();
            q.lastPrice = mid;
            q.depth = new MarketDepth();
            q.depth.buy = new ArrayList<>();
            q.depth.sell = new ArrayList<>();
            Depth bid = new Depth();
            bid.setPrice(Math.round((mid - halfSpread) * 20.0) / 20.0);
            bid.setQuantity(1000);
            Depth ask = new Depth();
            ask.setPrice(Math.round((mid + halfSpread) * 20.0) / 20.0);
            ask.setQuantity(1000);
            q.depth.buy.add(bid);
            q.depth.sell.add(ask);
            result.put(ins, q);
            log.info("[MOCK] getQuote: {} | mid={} bid={} ask={}", ins, mid, bid.getPrice(), ask.getPrice());
        }
        return result;
    }

    @Override
    public boolean modifyOrder(String orderId, double newPrice, int newQty, String variety) {
        executionPrices.put(orderId, newPrice);
        MockOrderState st = orders.get(orderId);
        if (st != null) {
            st.execPrice = newPrice;
            st.modifies++;
            if (fillMode == FillMode.AFTER_MODIFIES && "OPEN".equals(st.status)
                    && st.modifies >= fillAfterModifies) {
                st.status = ORDER_COMPLETE;
                orderStream.scheduleSyntheticFill(orderId, st.symbol, st.txn, st.qty, st.execPrice);
                log.info("[MOCK] modifyOrder: orderId={} FILLED after {} modifies @ {}", orderId, st.modifies, newPrice);
            }
        }
        log.info("[MOCK] modifyOrder: orderId={} newPrice={} newQty={}", orderId, newPrice, newQty);
        return true;
    }

    @Override
    public boolean cancelOrder(String orderId, String variety) {
        MockOrderState st = orders.get(orderId);
        if (st != null && "OPEN".equals(st.status)) {
            st.status = ORDER_CANCELLED;
            log.info("[MOCK] cancelOrder: orderId={} → CANCELLED (was OPEN, nothing filled)", orderId);
        } else {
            log.info("[MOCK] cancelOrder: orderId={} (no-op, status={})", orderId, st != null ? st.status : "unknown");
        }
        return true;
    }

    @Override
    public List<Order> getOrders() {
        List<Order> book = new ArrayList<>();
        orders.forEach((id, st) -> {
            Order o = new Order();
            o.orderId = id;
            o.tradingSymbol = st.symbol;
            o.transactionType = st.txn;
            o.status = st.status;
            o.quantity = String.valueOf(st.qty);
            o.tag = st.tag;
            book.add(o);
        });
        log.info("[MOCK] getOrders: {} orders in book", book.size());
        return book;
    }

    /**
     * Net positions derived from the mock order ledger: every COMPLETE order contributes its
     * signed quantity (BUY +, SELL −) to its symbol, so the broker-position check sees exactly
     * the book the mock "broker" holds.
     */
    @Override
    public List<com.zerodhatech.models.Position> getNetPositions() {
        Map<String, Integer> net = new java.util.TreeMap<>();
        orders.forEach((id, st) -> {
            if (!ORDER_COMPLETE.equals(st.status)) return;
            int signed = path.to._40c.nqCore.util.Constants.BUY.equals(st.txn) ? st.qty : -st.qty;
            net.merge(st.symbol, signed, Integer::sum);
        });
        List<com.zerodhatech.models.Position> out = new ArrayList<>();
        net.forEach((symbol, qty) -> {
            com.zerodhatech.models.Position p = new com.zerodhatech.models.Position();
            p.tradingSymbol = symbol;
            p.exchange = path.to._40c.nqCore.util.Constants.NFO;
            p.product = com.zerodhatech.kiteconnect.utils.Constants.PRODUCT_NRML;
            p.netQuantity = qty;
            p.buyQuantity = Math.max(qty, 0);
            p.sellQuantity = Math.max(-qty, 0);
            out.add(p);
        });
        log.info("[MOCK] getNetPositions: {} symbol(s) with a net position", out.size());
        return out;
    }

    @Override
    public List<BulkOrderResponse> placeAutoSliceOrder(OrderParams params, String variety) {
        String orderId = "MOCK-" + orderCounter.getAndIncrement();
        double base = params.price != null && params.price > 0 ? params.price
                : calcOptionLTP(params.tradingsymbol, niftySpot);
        double execPrice = applySlippage(base, params.transactionType);
        executionPrices.put(orderId, execPrice);
        orders.put(orderId, new MockOrderState(params.tradingsymbol, params.transactionType,
                params.quantity, params.tag, execPrice, ORDER_COMPLETE));
        log.info("[MOCK] placeAutoSliceOrder: symbol={} type={} qty={} price={} execPrice={} → orderId={}",
                params.tradingsymbol, params.transactionType, params.quantity,
                params.price, execPrice, orderId);
        BulkOrderResponse response = new BulkOrderResponse();
        response.orderId = orderId;
        return List.of(response);
    }

    /** Market-order slippage: BUYs pay 0.1–0.4% above LTP, SELLs receive 0.1–0.4% below. */
    private double applySlippage(Double basePrice, String side) {
        if (basePrice == null) return 0.0;
        double slippagePct = 0.001 + random.nextDouble() * 0.003;
        double multiplier = BUY.equals(side) ? (1 + slippagePct) : (1 - slippagePct);
        return Math.round(basePrice * multiplier * 100.0) / 100.0;
    }

    @Override
    public List<MarginCalculationData> getMarginCalculation(List<MarginCalculationParams> params) {
        List<MarginCalculationData> result = new ArrayList<>();
        double spot = niftySpot;
        for (MarginCalculationParams p : params) {
            double margin = calcMargin(p.quantity, spot);
            double brokerage = Math.round((18 + random.nextDouble() * 7) * 100.0) / 100.0;
            MarginCalculationData data = new MarginCalculationData();
            data.tradingSymbol = p.tradingSymbol;
            data.total = margin;
            data.charges = data.new Charges();
            data.charges.total = brokerage;
            log.info("[MOCK] getMarginCalculation: symbol={} type={} qty={} spot={} → margin={} brokerage={}",
                    p.tradingSymbol, p.transactionType, p.quantity, spot, margin, brokerage);
            result.add(data);
        }
        return result;
    }

    /** Approximates NRML margin as 15% of notional (spot × qty) with ±5% noise. */
    private double calcMargin(int quantity, double spot) {
        double notional = spot * quantity;
        double baseMargin = notional * 0.15;
        double noise = 1 + (random.nextDouble() * 0.10 - 0.05);
        return Math.round(baseMargin * noise * 100.0) / 100.0;
    }

    @Override
    public CombinedMarginData getCombinedMarginCalculation(List<MarginCalculationParams> params,
                                                           boolean considerPositions) {
        List<MarginCalculationData> perOrder = getMarginCalculation(params);
        double sumTotal = perOrder.stream().mapToDouble(d -> d.total).sum();
        double finalTotal = Math.round(sumTotal * 0.93 * 100.0) / 100.0;

        MarginCalculationData initial = new MarginCalculationData();
        initial.total = Math.round(sumTotal * 100.0) / 100.0;
        MarginCalculationData finalM  = new MarginCalculationData();
        finalM.total = finalTotal;

        CombinedMarginData combined = new CombinedMarginData();
        combined.initialMargin = initial;
        combined.finalMargin   = finalM;
        combined.orders        = perOrder;
        log.info("[MOCK] getCombinedMarginCalculation: legs={} initial={} final={}",
                params.size(), initial.total, finalM.total);
        return combined;
    }

    /**
     * Synthetic per-order charges matching Zerodha's F&O formula: brokerage ₹20 flat,
     * STT 0.05% of turnover on sells of options, exchange/SEBI on turnover, GST 18% on
     * (brokerage+exch+SEBI). Calibrated to live API responses (BUY ~₹27, SELL ~₹42).
     */
    @Override
    public List<ContractNote> getVirtualContractNote(List<ContractNoteParams> params) {
        List<ContractNote> result = new ArrayList<>();
        for (ContractNoteParams p : params) {
            double turnover    = p.averagePrice * p.quantity;
            double brokerage   = 20.0;
            double stt         = SELL.equals(p.transactionType) ? Math.round(turnover * 0.0005 * 100.0) / 100.0 : 0.0;
            double exch        = Math.round(turnover * 0.000035 * 100.0) / 100.0;
            double sebi        = Math.round(turnover * 0.0000001 * 100.0) / 100.0;
            double gstTotal    = Math.round((brokerage + exch + sebi) * 0.18 * 100.0) / 100.0;
            double total       = Math.round((brokerage + stt + exch + sebi + gstTotal) * 100.0) / 100.0;

            MarginCalculationData parent  = new MarginCalculationData();
            MarginCalculationData.Charges charges = parent.new Charges();
            charges.brokerage              = brokerage;
            charges.transactionTax         = stt;
            charges.transactionTaxType     = "stt";
            charges.exchangeTurnoverCharge = exch;
            charges.SEBITurnoverCharge     = sebi;
            charges.stampDuty              = 0.0;
            MarginCalculationData.GST gst  = parent.new GST();
            gst.total                      = gstTotal;
            charges.gst                    = gst;
            charges.total                  = total;

            ContractNote note      = new ContractNote();
            note.tradingSymbol     = p.tradingSymbol;
            note.transactionType = p.transactionType;
            note.exchange          = p.exchange;
            note.variety           = p.variety;
            note.product           = p.product;
            note.orderType         = p.orderType;
            note.quantity          = p.quantity;
            note.price             = p.averagePrice;
            note.charges           = charges;
            result.add(note);
            log.info("[MOCK] getVirtualContractNote: {} {} qty={} px={} → total={}",
                    p.tradingSymbol, p.transactionType, p.quantity, p.averagePrice, total);
        }
        return result;
    }

    @Override
    public List<com.zerodhatech.models.Trade> getOrderTrades(String singleOrderId) {
        MockOrderState st = orders.get(singleOrderId);
        if (st != null && !ORDER_COMPLETE.equals(st.status)) {
            log.info("[MOCK] getOrderTrades: orderId={} status={} → no trades", singleOrderId, st.status);
            return List.of();
        }
        com.zerodhatech.models.Trade trade = new com.zerodhatech.models.Trade();
        trade.orderId = singleOrderId;
        if (st != null) {
            trade.averagePrice = String.valueOf(st.execPrice);
            trade.quantity = String.valueOf(st.qty);
            trade.tradingSymbol = st.symbol;
            trade.transactionType = st.txn;
        } else {
            trade.averagePrice = String.valueOf(executionPrices.getOrDefault(singleOrderId, 120.50));
            trade.quantity = "65";
        }
        log.info("[MOCK] getOrderTrades: orderId={} → qty={} averagePrice={}",
                singleOrderId, trade.quantity, trade.averagePrice);
        return List.of(trade);
    }

    /**
     * Fabricates a realistic NIFTY option chain so the monthly DTE-roll automation and
     * the symbol autocomplete work under the mock profile: weekly expiries every Tuesday
     * for the next 10 weeks, with the last expiry of each calendar month carrying the
     * monthly naming (NIFTY26AUG...) exactly as NSE does; strikes 23500-25500 step 100.
     */
    @Override
    public List<Instrument> getInstruments(String exchange) {
        java.time.ZoneId ist = java.time.ZoneId.of(path.to._40c.nqCore.util.Constants.ZONE_ID);
        java.time.LocalDate firstExpiry = java.time.LocalDate.now(ist)
                .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.TUESDAY));
        List<Instrument> out = new ArrayList<>();
        for (int week = 0; week < 10; week++) {
            java.time.LocalDate expiry = firstExpiry.plusWeeks(week);
            boolean lastOfMonth = expiry.plusWeeks(1).getMonth() != expiry.getMonth();
            String prefix = lastOfMonth ? monthlyPrefix(expiry) : weeklyPrefix(expiry);
            for (int strike = 23500; strike <= 25500; strike += 100) {
                for (String type : List.of("CE", "PE")) {
                    Instrument i = new Instrument();
                    i.tradingsymbol = "NIFTY" + prefix + strike + type;
                    i.name = "NIFTY";
                    i.instrument_type = type;
                    i.strike = strike + ".0";
                    i.lot_size = 65;
                    i.segment = "NFO-OPT";
                    i.exchange = exchange;
                    i.expiry = java.util.Date.from(expiry.atStartOfDay(ist).toInstant());
                    out.add(i);
                }
            }
        }
        log.info("[MOCK] getInstruments: exchange={} → fabricated {} NIFTY option rows across 10 expiries", exchange, out.size());
        return out;
    }

    /** NSE monthly naming: NIFTY{yy}{MMM}, e.g. 26AUG. */
    private static String monthlyPrefix(java.time.LocalDate expiry) {
        String yy = String.format("%02d", expiry.getYear() % 100);
        String mon = expiry.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH).toUpperCase();
        return yy + mon;
    }

    /** NSE weekly naming: NIFTY{yy}{m}{dd} with m = 1-9 / O / N / D, e.g. 26812 for 2026-08-12. */
    private static String weeklyPrefix(java.time.LocalDate expiry) {
        String yy = String.format("%02d", expiry.getYear() % 100);
        int m = expiry.getMonthValue();
        String monthToken = switch (m) {
            case 10 -> "O";
            case 11 -> "N";
            case 12 -> "D";
            default -> String.valueOf(m);
        };
        return yy + monthToken + String.format("%02d", expiry.getDayOfMonth());
    }

    @Override
    public User generateSession(String requestToken, String apiSecret) {
        log.info("[MOCK] generateSession: requestToken={} → returning fake session", requestToken);
        User user = new User();
        user.accessToken = "mock-access-" + System.currentTimeMillis();
        user.publicToken = "mock-public-" + System.currentTimeMillis();
        user.userId = "MOCK-USER";
        return user;
    }

    @Override
    public String getLoginURL() {
        String url = "http://localhost:8080/mock-login-url";
        log.info("[MOCK] getLoginURL → {}", url);
        return url;
    }

    @Override
    public Map<String, Object> testConnection() {
        log.info("[MOCK] testConnection → OK");
        return Map.of("status", "OK", "message", "[MOCK] Connection healthy.");
    }

    @Override
    public void invalidateCache() {
        log.debug("[MOCK] invalidateCache called (no-op)");
    }
}
