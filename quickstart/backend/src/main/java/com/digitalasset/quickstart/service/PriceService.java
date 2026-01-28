package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.HoldingPoolResponse;
import com.digitalasset.quickstart.dto.PriceQuote;
import com.digitalasset.quickstart.dto.PriceResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PriceService {
    private static final Logger LOG = LoggerFactory.getLogger(PriceService.class);

    private static final String STATUS_OK = "OK";
    private static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    private static final String STATUS_STALE = "STALE";

    private static final String SYMBOL_CBTC = "CBTC";
    private static final String SYMBOL_CC = "CC";

    private static final String REASON_NO_SOURCE = "NO_RELIABLE_SOURCE";
    private static final String REASON_FETCH_FAILED = "FETCH_FAILED";
    private static final String REASON_ESTIMATED = "ESTIMATED_FROM_POOL";
    private static final String REASON_MISSING_ID = "MISSING_COINGECKO_ID";
    private static final String REASON_BTC_UNAVAILABLE = "BTC_PRICE_UNAVAILABLE";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final HoldingPoolService holdingPoolService;

    @Value("${clearportx.prices.cache-ttl-seconds:60}")
    private long cacheTtlSeconds;

    @Value("${clearportx.prices.http-timeout-ms:2000}")
    private long httpTimeoutMs;

    @Value("${clearportx.prices.btc-source:COINGECKO}")
    private String btcSource;

    @Value("${clearportx.prices.cc-mode:NONE}")
    private String ccMode;

    @Value("${clearportx.prices.cc-coingecko-id:}")
    private String ccCoingeckoId;

    @Value("${clearportx.prices.cc-pool-cid:}")
    private String ccPoolCid;

    private final Map<String, CachedQuote> cache = new ConcurrentHashMap<>();

    public PriceService(final HoldingPoolService holdingPoolService) {
        this.holdingPoolService = holdingPoolService;
    }

    public PriceResponse getQuotes(Set<String> symbols, String requestId) {
        Instant now = Instant.now();
        Set<String> normalized = normalizeSymbols(symbols);
        Map<String, PriceQuote> quotes = new LinkedHashMap<>();

        boolean needsBtc = normalized.contains(SYMBOL_CBTC) || needsBtcForCc();
        PriceQuote btcQuote = null;
        if (needsBtc) {
            btcQuote = getOrFetch(SYMBOL_CBTC, now, requestId, this::fetchBtcUsd);
            if (normalized.contains(SYMBOL_CBTC)) {
                quotes.put(SYMBOL_CBTC, btcQuote);
            }
        }

        if (normalized.contains(SYMBOL_CC)) {
            PriceQuote ccQuote = getOrFetch(SYMBOL_CC, now, requestId, () -> fetchCcUsd(btcQuote, requestId));
            quotes.put(SYMBOL_CC, ccQuote);
        }

        for (String symbol : normalized) {
            if (!quotes.containsKey(symbol)) {
                quotes.put(symbol, unavailable(symbol, null, REASON_NO_SOURCE));
            }
        }

        return new PriceResponse(quotes, now);
    }

    private Set<String> normalizeSymbols(Set<String> symbols) {
        Set<String> normalized = new LinkedHashSet<>();
        if (symbols != null) {
            for (String symbol : symbols) {
                if (symbol == null || symbol.isBlank()) continue;
                normalized.add(symbol.trim().toUpperCase());
            }
        }
        if (normalized.isEmpty()) {
            normalized.add(SYMBOL_CBTC);
            normalized.add(SYMBOL_CC);
        }
        return normalized;
    }

    private boolean needsBtcForCc() {
        if (ccMode == null) {
            return false;
        }
        return "FROM_POOL".equalsIgnoreCase(ccMode);
    }

    private PriceQuote getOrFetch(String symbol,
                                  Instant now,
                                  String requestId,
                                  PriceFetcher fetcher) {
        CachedQuote cached = cache.get(symbol);
        if (cached != null && now.isBefore(cached.expiresAt)) {
            logQuote(requestId, symbol, true, cached.quote);
            return cached.quote;
        }
        PriceQuote fresh = fetcher.fetch(requestId);
        if (fresh != null && STATUS_OK.equals(fresh.status)) {
            cache.put(symbol, new CachedQuote(fresh, now.plusSeconds(Math.max(5, cacheTtlSeconds))));
            logQuote(requestId, symbol, false, fresh);
            return fresh;
        }
        if (cached != null) {
            PriceQuote stale = cloneWithStatus(cached.quote, STATUS_STALE, REASON_FETCH_FAILED);
            logQuote(requestId, symbol, false, stale);
            return stale;
        }
        logQuote(requestId, symbol, false, fresh);
        return fresh != null ? fresh : unavailable(symbol, null, REASON_FETCH_FAILED);
    }

    private PriceQuote fetchBtcUsd(String requestId) {
        if (btcSource == null || !"COINGECKO".equalsIgnoreCase(btcSource)) {
            return unavailable(SYMBOL_CBTC, null, REASON_NO_SOURCE);
        }
        BigDecimal usd = fetchCoinGeckoUsd("bitcoin");
        if (usd == null) {
            return unavailable(SYMBOL_CBTC, "coingecko:bitcoin", REASON_FETCH_FAILED);
        }
        return new PriceQuote(SYMBOL_CBTC, usd, "coingecko:bitcoin", STATUS_OK, null);
    }

    private PriceQuote fetchCcUsd(PriceQuote btcQuote, String requestId) {
        if (ccMode == null || ccMode.isBlank() || "NONE".equalsIgnoreCase(ccMode)) {
            return unavailable(SYMBOL_CC, null, REASON_NO_SOURCE);
        }
        if ("COINGECKO_ID".equalsIgnoreCase(ccMode)) {
            if (ccCoingeckoId == null || ccCoingeckoId.isBlank()) {
                return unavailable(SYMBOL_CC, null, REASON_MISSING_ID);
            }
            BigDecimal usd = fetchCoinGeckoUsd(ccCoingeckoId.trim());
            if (usd == null) {
                return unavailable(SYMBOL_CC, "coingecko:" + ccCoingeckoId.trim(), REASON_FETCH_FAILED);
            }
            return new PriceQuote(SYMBOL_CC, usd, "coingecko:" + ccCoingeckoId.trim(), STATUS_OK, null);
        }
        if ("FROM_POOL".equalsIgnoreCase(ccMode)) {
            BigDecimal btcUsd = btcQuote != null ? btcQuote.priceUsd : null;
            if (btcUsd == null) {
                return unavailable(SYMBOL_CC, "amm-spot+btc-usd", REASON_BTC_UNAVAILABLE);
            }
            BigDecimal estimated = estimateCcFromPool(btcUsd);
            if (estimated == null) {
                return unavailable(SYMBOL_CC, "amm-spot+btc-usd", REASON_FETCH_FAILED);
            }
            return new PriceQuote(SYMBOL_CC, estimated, "amm-spot+btc-usd", STATUS_OK, REASON_ESTIMATED);
        }
        return unavailable(SYMBOL_CC, null, REASON_NO_SOURCE);
    }

    private BigDecimal estimateCcFromPool(BigDecimal btcUsd) {
        HoldingPoolResponse pool = resolvePoolForCc();
        if (pool == null) {
            return null;
        }
        BigDecimal reserveA = parseDecimal(pool.reserveAmountA);
        BigDecimal reserveB = parseDecimal(pool.reserveAmountB);
        String symbolA = mapInstrumentToSymbol(pool.instrumentA.id);
        String symbolB = mapInstrumentToSymbol(pool.instrumentB.id);
        BigDecimal reserveCc;
        BigDecimal reserveCbtc;
        if (SYMBOL_CC.equals(symbolA) && SYMBOL_CBTC.equals(symbolB)) {
            reserveCc = reserveA;
            reserveCbtc = reserveB;
        } else if (SYMBOL_CBTC.equals(symbolA) && SYMBOL_CC.equals(symbolB)) {
            reserveCc = reserveB;
            reserveCbtc = reserveA;
        } else {
            return null;
        }
        if (reserveCc.compareTo(BigDecimal.ZERO) <= 0 || reserveCbtc.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal ratio = reserveCbtc.divide(reserveCc, MathContext.DECIMAL64);
        return ratio.multiply(btcUsd, MathContext.DECIMAL64);
    }

    private HoldingPoolResponse resolvePoolForCc() {
        if (ccPoolCid != null && !ccPoolCid.isBlank()) {
            Result<HoldingPoolResponse, com.digitalasset.quickstart.common.DomainError> res =
                    holdingPoolService.getByContractId(ccPoolCid.trim()).join();
            return res.isOk() ? res.getValueUnsafe() : null;
        }
        Result<java.util.List<HoldingPoolResponse>, com.digitalasset.quickstart.common.DomainError> res =
                holdingPoolService.list().join();
        if (res.isErr()) {
            return null;
        }
        return res.getValueUnsafe().stream()
                .filter(pool -> pool.status != null && "active".equalsIgnoreCase(pool.status))
                .filter(this::isCcCbtcPool)
                .findFirst()
                .orElse(null);
    }

    private boolean isCcCbtcPool(HoldingPoolResponse pool) {
        String symbolA = mapInstrumentToSymbol(pool.instrumentA.id);
        String symbolB = mapInstrumentToSymbol(pool.instrumentB.id);
        return (SYMBOL_CC.equals(symbolA) && SYMBOL_CBTC.equals(symbolB))
                || (SYMBOL_CBTC.equals(symbolA) && SYMBOL_CC.equals(symbolB));
    }

    private String mapInstrumentToSymbol(String instrumentId) {
        if (instrumentId == null) {
            return "";
        }
        String trimmed = instrumentId.trim();
        if ("AMULET".equalsIgnoreCase(trimmed)) {
            return SYMBOL_CC;
        }
        return trimmed.toUpperCase();
    }

    private BigDecimal fetchCoinGeckoUsd(String coinId) {
        try {
            String url = "https://api.coingecko.com/api/v3/simple/price?ids=" + coinId + "&vs_currencies=usd";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(500, httpTimeoutMs)))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode usdNode = root.path(coinId).path("usd");
            if (usdNode.isMissingNode() || usdNode.isNull()) {
                return null;
            }
            return usdNode.decimalValue();
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal parseDecimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private PriceQuote unavailable(String symbol, String source, String reason) {
        return new PriceQuote(symbol, null, source, STATUS_UNAVAILABLE, reason);
    }

    private PriceQuote cloneWithStatus(PriceQuote quote, String status, String reason) {
        if (quote == null) {
            return null;
        }
        return new PriceQuote(quote.symbol, quote.priceUsd, quote.source, status, reason);
    }

    private void logQuote(String requestId, String symbol, boolean cacheHit, PriceQuote quote) {
        if (quote == null) {
            LOG.info("[Prices] requestId={} symbol={} cacheHit={} status=UNAVAILABLE source=none", requestId, symbol, cacheHit);
            return;
        }
        LOG.info("[Prices] requestId={} symbol={} cacheHit={} source={} status={} reason={}",
                requestId,
                symbol,
                cacheHit,
                quote.source,
                quote.status,
                quote.reason);
    }

    private interface PriceFetcher {
        PriceQuote fetch(String requestId);
    }

    private record CachedQuote(PriceQuote quote, Instant expiresAt) {}
}

