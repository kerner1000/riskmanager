package com.github.riskmanager;



import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
public class CurrencyConversionService {

    @ConfigProperty(name = "risk.base-currency", defaultValue = "EUR")
    String baseCurrency;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache exchange rates (currency -> rate to EUR)
    private final Map<String, BigDecimal> ratesToBase = new ConcurrentHashMap<>();
    private volatile long lastRefresh = 0;
    private static final long REFRESH_INTERVAL_MS = 3600_000; // 1 hour

    @PostConstruct
    void init() {
        refreshRates();
    }

    public String getBaseCurrency() {
        return baseCurrency;
    }

    public BigDecimal convertToBase(BigDecimal amount, String fromCurrency) {
        if (amount == null || fromCurrency == null) {
            return amount;
        }

        if (fromCurrency.equalsIgnoreCase(baseCurrency)) {
            return amount;
        }

        refreshRatesIfNeeded();

        BigDecimal rate = ratesToBase.get(fromCurrency.toUpperCase());
        if (rate == null) {
            System.out.println("WARN: No exchange rate found for " + fromCurrency + ", using 1:1");
            return amount;
        }

        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private void refreshRatesIfNeeded() {
        if (System.currentTimeMillis() - lastRefresh > REFRESH_INTERVAL_MS) {
            refreshRates();
        }
    }

    private synchronized void refreshRates() {
        if (System.currentTimeMillis() - lastRefresh < REFRESH_INTERVAL_MS) {
            return; // Already refreshed by another thread
        }

        Log.infof("Refreshing exchange rates for %s", baseCurrency);

        try {
            // Using exchangerate.host API (free, no key required)
            // Gets rates with EUR as base
            String url = "https://api.frankfurter.app/latest?from=" + baseCurrency;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                JsonNode rates = root.get("rates");

                ratesToBase.clear();
                ratesToBase.put(baseCurrency.toUpperCase(), BigDecimal.ONE);

                if (rates != null) {
                    rates.fields().forEachRemaining(entry -> {
                        // API gives us: 1 EUR = X currency
                        // We need: 1 currency = Y EUR (inverse)
                        BigDecimal rateFromBase = new BigDecimal(entry.getValue().asText());
                        BigDecimal rateToBase = BigDecimal.ONE.divide(rateFromBase, 10, RoundingMode.HALF_UP);
                        ratesToBase.put(entry.getKey(), rateToBase);
                    });
                }

                lastRefresh = System.currentTimeMillis();
                System.out.println("INFO: Refreshed exchange rates, " + ratesToBase.size() + " currencies loaded");
            } else {
                System.out.println("WARN: Failed to fetch exchange rates, status: " + response.statusCode());
            }
        } catch (Exception e) {
            System.out.println("WARN: Failed to refresh exchange rates: " + e.getMessage());
        }
    }
}
