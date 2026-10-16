package path.to._40c.nqCore.gateway;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.json.JSONException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.BulkOrderResponse;
import com.zerodhatech.models.CombinedMarginData;
import com.zerodhatech.models.ContractNote;
import com.zerodhatech.models.ContractNoteParams;
import com.zerodhatech.models.Instrument;
import com.zerodhatech.models.LTPQuote;
import com.zerodhatech.models.MarginCalculationData;
import com.zerodhatech.models.MarginCalculationParams;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.OrderResponse;
import com.zerodhatech.models.Quote;
import com.zerodhatech.models.User;

import path.to._40c.nqCore.entity.KiteAuthDetails;
import path.to._40c.nqCore.repo.KiteAuthDetailsRepository;

import static path.to._40c.nqCore.util.Constants.ZONE_ID;

/**
 * Production implementation of KiteGateway — wraps the Kite Connect SDK.
 * Active when: spring.profiles.active=live
 */
@Service
@Profile("live")
@Slf4j
public class KiteConnectGateway implements KiteGateway {
    private final String apiKey;
    private final String userId;
    private final KiteAuthDetailsRepository repository;

    /** Session cache keyed by today's date; avoids a DB hit on every call. Invalidated on new auth. */
    private volatile KiteConnect cachedKiteConnect = null;
    private volatile LocalDate cacheDate = null;

    public KiteConnectGateway(
            @Value("${kite.api-key}") String apiKey,
            @Value("${kite.user-id}") String userId,
            KiteAuthDetailsRepository repository) {
        this.apiKey = apiKey;
        this.userId = userId;
        this.repository = repository;
    }

    @Override
    public Map<String, LTPQuote> getLTP(String[] instruments) {
        var kite = getKiteConnectObject();
        if (kite == null) {
            log.error("KiteConnect object is null — cannot fetch LTP (auth not set for today?)");
            return Collections.emptyMap();
        }
        try {
            return kite.getLTP(instruments);
        } catch (JSONException | IOException | KiteException e) {
            log.error("Exception while fetching LTP", e);
        }
        return Collections.emptyMap();
    }

    @Override
    public List<Order> getOrderHistory(String orderId) {
        var kite = getKiteConnectObject();
        if (kite == null) { log.error("KiteConnect null — cannot fetch order history"); return Collections.emptyList(); }
        try {
            return kite.getOrderHistory(orderId);
        } catch (KiteException e) {
            log.error("Exception while fetching order history for {} — code={} message={}", orderId, e.code, e.getMessage());
        } catch (JSONException | IOException e) {
            log.error("Exception while fetching order history for {}", orderId, e);
        }
        return Collections.emptyList();
    }

    @Override
    public List<Order> getOrders() {
        var kite = getKiteConnectObject();
        if (kite == null) { log.error("KiteConnect null — cannot fetch order book"); return null; }
        try {
            return kite.getOrders();
        } catch (KiteException e) {
            log.error("Exception while fetching order book — code={} message={}", e.code, e.getMessage());
        } catch (JSONException | IOException e) {
            log.error("Exception while fetching order book", e);
        }
        return null;
    }

    @Override
    public List<com.zerodhatech.models.Position> getNetPositions() {
        var kite = getKiteConnectObject();
        if (kite == null) { log.error("KiteConnect null — cannot fetch positions"); return null; }
        try {
            Map<String, List<com.zerodhatech.models.Position>> all = kite.getPositions();
            List<com.zerodhatech.models.Position> net = all != null ? all.get("net") : null;
            return net != null ? net : Collections.emptyList();
        } catch (KiteException e) {
            log.error("Exception while fetching positions — code={} message={}", e.code, e.getMessage());
        } catch (JSONException | IOException e) {
            log.error("Exception while fetching positions", e);
        }
        return null;
    }

    @Override
    public OrderResponse placeOrder(OrderParams params, String variety) {
        var kite = getKiteConnectObject();
        if (kite == null) { log.error("KiteConnect null — cannot place order"); return null; }
        try {
            return kite.placeOrder(params, variety);
        } catch (KiteException e) {
            log.error("Exception while placing order — code={} message={}", e.code, e.getMessage(), e);
        } catch (JSONException | IOException e) {
            log.error("Exception while placing order", e);
        }
        return null;
    }

    @Override
    public Map<String, Quote> getQuote(String[] instruments) {
        var kite = getKiteConnectObject();
        if (kite == null) { log.error("KiteConnect null — cannot fetch quote"); return Collections.emptyMap(); }
        try {
            return kite.getQuote(instruments);
        } catch (JSONException | IOException | KiteException e) {
            log.error("Exception while fetching quote", e);
        }
        return Collections.emptyMap();
    }

    @Override
    public boolean modifyOrder(String orderId, double newPrice, int newQty, String variety) {
        var kite = getKiteConnectObject();
        if (kite == null) { log.error("KiteConnect null — cannot modify order"); return false; }
        try {
            OrderParams params = new OrderParams();
            params.orderType = com.zerodhatech.kiteconnect.utils.Constants.ORDER_TYPE_LIMIT;
            params.price     = newPrice;
            params.quantity  = newQty;
            kite.modifyOrder(orderId, params, variety);
            return true;
        } catch (KiteException e) {
            log.warn("modifyOrder failed for {} — code={} message={}", orderId, e.code, e.getMessage());
        } catch (JSONException | IOException e) {
            log.error("modifyOrder exception for {}", orderId, e);
        }
        return false;
    }

    /**
     * Kite rejects a cancel both for a benign reason (order already terminal) and for a dangerous
     * one — 2026-09-22 and 2026-10-07: HTTP 400 "An internal error occurred" while the order was
     * still OPEN (a modify most likely still settling at the exchange). The message does not
     * distinguish them, so this never claims success: the caller must read the order's state and
     * act only on a confirmed terminal.
     */
    @Override
    public boolean cancelOrder(String orderId, String variety) {
        var kite = getKiteConnectObject();
        if (kite == null) { log.error("KiteConnect null — cannot cancel order"); return false; }
        try {
            kite.cancelOrder(orderId, variety);
            return true;
        } catch (KiteException e) {
            log.warn("cancelOrder for {} NOT accepted — code={} message={} (order may be terminal OR still working; "
                    + "confirm its state before placing anything else for this leg)", orderId, e.code, e.getMessage());
            return false;
        } catch (JSONException | IOException e) {
            log.error("cancelOrder exception for {} — order state unknown, confirm before acting", orderId, e);
        }
        return false;
    }

    @Override
    public List<BulkOrderResponse> placeAutoSliceOrder(OrderParams params, String variety) {
        var kite = getKiteConnectObject();
        if (kite == null) { log.error("KiteConnect null — cannot place auto-slice order"); return new ArrayList<>(); }
        try {
            params.autoslice = true;
            OrderResponse response = kite.placeOrder(params, variety);
            return response != null && response.children != null ? response.children : new ArrayList<>();
        } catch (KiteException e) {
            log.error("Exception while placing auto-slice order — code={} message={}", e.code, e.getMessage(), e);
        } catch (JSONException | IOException e) {
            log.error("Exception while placing auto-slice order", e);
        }
        return new ArrayList<>();
    }

    @Override
    public List<MarginCalculationData> getMarginCalculation(List<MarginCalculationParams> params) {
        var kite = getKiteConnectObject();
        if (kite == null) {
            log.error("KiteConnect null — skipping margin calculation");
            return new ArrayList<>();
        }
        try {
            return kite.getMarginCalculation(params);
        } catch (JSONException | IOException | KiteException e) {
            log.error("Exception while fetching margin calculation", e);
        }
        return new ArrayList<>();
    }

    @Override
    public List<ContractNote> getVirtualContractNote(List<ContractNoteParams> params) {
        var kite = getKiteConnectObject();
        if (kite == null) {
            log.error("KiteConnect null — skipping virtual contract note");
            return new ArrayList<>();
        }
        try {
            return kite.getVirtualContractNote(params);
        } catch (JSONException | IOException | KiteException e) {
            log.error("Exception while fetching virtual contract note", e);
        }
        return new ArrayList<>();
    }

    @Override
    public CombinedMarginData getCombinedMarginCalculation(List<MarginCalculationParams> params,
                                                           boolean considerPositions) {
        var kite = getKiteConnectObject();
        if (kite == null) {
            log.error("KiteConnect null — skipping combined margin calculation");
            return null;
        }
        try {
            return kite.getCombinedMarginCalculation(params, considerPositions, false);
        } catch (JSONException | IOException | KiteException e) {
            log.error("Exception while fetching combined margin calculation", e);
        }
        return null;
    }

    @Override
    public List<com.zerodhatech.models.Trade> getOrderTrades(String singleOrderId) {
        var kite = getKiteConnectObject();
        if (kite == null) { log.error("KiteConnect null — cannot fetch order trades"); return new ArrayList<>(); }
        try {
            return kite.getOrderTrades(singleOrderId);
        } catch (JSONException | IOException | KiteException e) {
            log.error("Exception while fetching trades for orderId: {}", singleOrderId, e);
        }
        return new ArrayList<>();
    }

    @Override
    public List<Instrument> getInstruments(String exchange) {
        var kite = getKiteConnectObject();
        if (kite == null) { log.error("KiteConnect null — cannot fetch instruments"); return Collections.emptyList(); }
        try {
            return kite.getInstruments(exchange);
        } catch (JSONException | IOException | KiteException e) {
            log.error("Exception while fetching instruments for {}", exchange, e);
        }
        return Collections.emptyList();
    }

    @Override
    public User generateSession(String requestToken, String apiSecret) {
        try {
            return buildLoginKiteConnect().generateSession(requestToken, apiSecret);
        } catch (JSONException | IOException | KiteException e) {
            log.error("Exception while generating session", e);
        }
        return null;
    }

    @Override
    public String getLoginURL() {
        return buildLoginKiteConnect().getLoginURL();
    }

    @Override
    public Map<String, Object> testConnection() {
        var kite = getKiteConnectObject();
        if (kite == null) {
            return Map.of("status", "NO_AUTH", "message", "No auth token found for today. Login and save your request token first.");
        }
        try {
            Map<String, LTPQuote> ltpMap = kite.getLTP(new String[]{"NSE:NIFTY 50"});
            if (ltpMap != null && !ltpMap.isEmpty()) {
                return Map.of("status", "OK", "message", "Connection healthy.");
            }
            return Map.of("status", "ERROR", "message", "LTP returned no data.");
        } catch (KiteException e) {
            log.error("Kite connection test failed — code={} message={}", e.code, e.getMessage());
            return Map.of("status", "KITE_ERROR", "message", e.getMessage(), "code", e.code);
        } catch (Exception e) {
            log.error("Kite connection test failed", e);
            return Map.of("status", "ERROR", "message", e.getMessage());
        }
    }

    @Override
    public void invalidateCache() {
        cachedKiteConnect = null;
        cacheDate = null;
        log.info("KiteConnect cache invalidated");
    }

    /** Builds a base KiteConnect for login/session operations (no access token needed). */
    private KiteConnect buildLoginKiteConnect() {
        KiteConnect kite = new KiteConnect(apiKey);
        kite.setUserId(userId);
        return kite;
    }

    /** Builds/returns a cached KiteConnect for trading operations (needs access token from DB). */
    public KiteConnect getKiteConnectObject() {
        LocalDate today = LocalDate.now(ZoneId.of(ZONE_ID));
        if (cachedKiteConnect != null && today.equals(cacheDate)) {
            return cachedKiteConnect;
        }
        Optional<KiteAuthDetails> existing = repository.findByAuthDate(today);
        if (existing.isPresent()) {
            KiteConnect kite = new KiteConnect(existing.get().getApiKey());
            kite.setAccessToken(existing.get().getAccessToken());
            kite.setPublicToken(existing.get().getPublicToken());
            cachedKiteConnect = kite;
            cacheDate = today;
            return kite;
        }
        return null;
    }
}
