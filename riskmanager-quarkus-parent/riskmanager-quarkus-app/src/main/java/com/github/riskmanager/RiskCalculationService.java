package com.github.riskmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.riskmanager.ib.api.AccountApi;
import com.github.riskmanager.ib.api.ApiException;
import com.github.riskmanager.ib.api.OrderApi;
import com.github.riskmanager.ib.api.PortfolioApi;
import com.github.riskmanager.ib.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class RiskCalculationService {

    @RestClient
    @Inject
    PortfolioApi portfolioApi;

    @RestClient
    @Inject
    OrderApi orderApi;

    @RestClient
    @Inject
    AccountApi accountApi;

    @Inject
    CurrencyConversionService currencyService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "risk.accounts")
    List<String> accounts;

    @ConfigProperty(name = "risk.unprotected-loss-percentage", defaultValue = "50")
    BigDecimal unprotectedLossPercentage;

    // Composite key for position uniqueness across accounts
    private record PositionKey(Integer conid, String accountId) {}

    // ==================== Risk Calculation ====================

    public RiskReport calculateWorstCaseScenarioForAccounts(List<String> accountIds) throws Exception {
        List<PositionInner> allPositions = fetchAllPositions(accountIds);
        List<IserverAccountOrdersGet200ResponseOrdersInner> stopOrders = fetchAllStopOrders();

        Map<PositionKey, PositionInner> positionsByKey = buildPositionMap(allPositions);

        RiskAccumulator accumulator = new RiskAccumulator(currencyService);
        Set<PositionKey> protectedPositions = processProtectedPositions(stopOrders, positionsByKey, accumulator);
        processUnprotectedPositions(allPositions, protectedPositions, accumulator);

        return accumulator.toReport(unprotectedLossPercentage);
    }

    private List<PositionInner> fetchAllPositions(List<String> accountIds) throws ApiException {
        List<PositionInner> allPositions = new ArrayList<>();
        for (String accountId : accountIds) {
            List<PositionInner> positions = portfolioApi.portfolioAccountIdPositionsPageIdGet(
                    accountId, "0", null, null, null, null);
            if (positions != null) {
                allPositions.addAll(positions);
            }
        }
        return allPositions;
    }

    private List<IserverAccountOrdersGet200ResponseOrdersInner> fetchAllStopOrders() throws Exception {
        Set<String> seenOrderIds = new HashSet<>();
        List<IserverAccountOrdersGet200ResponseOrdersInner> stopOrders = new ArrayList<>();

        for (String acct : accounts) {
            // Switch to this account before fetching orders
            try {
                SetAccount setAccount = new SetAccount();
                setAccount.setAcctId(acct);
                var response = accountApi.iserverAccountPost(setAccount);
                System.out.println("DEBUG: Switched to account " + acct + ", success=" + response.getSet());
                Thread.sleep(200);
            } catch (Exception e) {
                System.out.println("DEBUG: Failed to switch to account " + acct + ": " + e.getMessage());
            }

            // Force refresh for this account
            orderApi.iserverAccountOrdersGet(null);
            Thread.sleep(300);

            for (IserverAccountOrdersGet200ResponseOrdersInner order : fetchStopOrdersForAccount(acct)) {
                if (order.getOrderId() != null && seenOrderIds.add(order.getOrderId())) {
                    stopOrders.add(order);
                }
            }
        }
        System.out.println("DEBUG: Unique stop orders: " + stopOrders.size());
        return stopOrders;
    }

    private List<IserverAccountOrdersGet200ResponseOrdersInner> fetchStopOrdersForAccount(String accountId) throws ApiException {
        IserverAccountOrdersGet200Response response = orderApi.iserverAccountOrdersGet(null);

        if (response == null || response.getOrders() == null) {
            System.out.println("DEBUG: Orders for " + accountId + ": null");
            return List.of();
        }

        System.out.println("DEBUG: Orders for " + accountId + ": " + response.getOrders().size());

        return response.getOrders().stream()
                .filter(o -> {
                    String type = o.getOrderType();
                    return type != null && (
                            type.toLowerCase().contains("stop") ||
                                    type.equalsIgnoreCase("STP")
                    );
                })
                .filter(o -> {
                    String status = o.getStatus();
                    System.out.println("DEBUG Stop Order: ticker=" + o.getTicker() +
                            ", acct=" + o.getAcct() +
                            ", conid=" + o.getConid() +
                            ", status=" + status);
                    return status == null || (
                            !status.equalsIgnoreCase("Cancelled") &&
                                    !status.equalsIgnoreCase("Filled")
                    );
                })
                .toList();
    }

    private Map<PositionKey, PositionInner> buildPositionMap(List<PositionInner> positions) {
        return positions.stream()
                .collect(Collectors.toMap(
                        p -> new PositionKey(p.getConid(), p.getAcctId()),
                        Function.identity(),
                        (a, b) -> a
                ));
    }

    private Set<PositionKey> processProtectedPositions(
            List<IserverAccountOrdersGet200ResponseOrdersInner> stopOrders,
            Map<PositionKey, PositionInner> positionsByKey,
            RiskAccumulator accumulator
    ) {
        Set<PositionKey> protectedPositions = new HashSet<>();

        for (IserverAccountOrdersGet200ResponseOrdersInner order : stopOrders) {
            Integer conid = order.getConid() != null ? order.getConid().intValue() : null;
            if (conid == null) continue;

            PositionKey key = new PositionKey(conid, order.getAcct());
            PositionInner position = positionsByKey.get(key);

            BigDecimal stopPrice = extractStopPrice(order);
            if (stopPrice == null || position == null) continue;

            protectedPositions.add(key);

            BigDecimal quantity = order.getRemainingQuantity() != null
                    ? order.getRemainingQuantity()
                    : (order.getFilledQuantity() != null ? order.getFilledQuantity() : BigDecimal.ZERO);

            BigDecimal potentialLoss = calculateLossPerShare(position.getPosition(), position.getAvgPrice(), stopPrice)
                    .multiply(quantity.abs());

            accumulator.addProtectedLoss(potentialLoss, position.getCurrency());

            BigDecimal positionValue = position.getPosition().abs().multiply(position.getMktPrice());
            String currency = position.getCurrency();

            accumulator.addRisk(new PositionRisk(
                    position.getAcctId(),
                    order.getTicker() != null ? order.getTicker() : position.getContractDesc(),
                    position.getPosition(),
                    position.getAvgPrice(),
                    position.getMktPrice(),
                    stopPrice,
                    quantity.abs(),
                    potentialLoss,
                    positionValue,
                    currency,
                    currencyService.convertToBase(potentialLoss, currency),
                    currencyService.convertToBase(positionValue, currency),
                    currencyService.getBaseCurrency(),
                    true
            ));
        }
        return protectedPositions;
    }

    private BigDecimal calculateLossPerShare(BigDecimal positionSize, BigDecimal avgPrice, BigDecimal stopPrice) {
        if (positionSize.compareTo(BigDecimal.ZERO) > 0) {
            return avgPrice.subtract(stopPrice);
        } else {
            return stopPrice.subtract(avgPrice);
        }
    }

    /**
     * Extracts stop price from an order. For stop orders, the price field may be null,
     * so we fall back to parsing the orderDesc field (e.g., "Sell 500 CSTM Stop 22.80, GTC").
     */
    private BigDecimal extractStopPrice(IserverAccountOrdersGet200ResponseOrdersInner order) {
        // First, try the price field directly
        if (order.getPrice() != null) {
            return order.getPrice();
        }

        // Fall back to parsing orderDesc for stop orders
        String orderDesc = order.getOrderDesc();
        if (orderDesc != null && orderDesc.toLowerCase().contains("stop")) {
            // Pattern: "Sell 500 CSTM Stop 22.80, GTC" or similar
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?i)stop\\s+([\\d,]+\\.?\\d*)"
            );
            java.util.regex.Matcher matcher = pattern.matcher(orderDesc);
            if (matcher.find()) {
                try {
                    String priceStr = matcher.group(1).replace(",", "");
                    return new BigDecimal(priceStr);
                } catch (NumberFormatException e) {
                    System.out.println("WARN: Could not parse stop price from orderDesc: " + orderDesc);
                }
            }
        }

        return null;
    }

    private void processUnprotectedPositions(
            List<PositionInner> allPositions,
            Set<PositionKey> protectedPositions,
            RiskAccumulator accumulator
    ) {
        for (PositionInner position : allPositions) {
            PositionKey key = new PositionKey(position.getConid(), position.getAcctId());
            if (protectedPositions.contains(key) || position.getPosition().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal quantity = position.getPosition().abs();
            BigDecimal assumedStopPrice = calculateAssumedStopPrice(position);
            BigDecimal potentialLoss = calculateLossPerShare(position.getPosition(), position.getAvgPrice(), assumedStopPrice)
                    .multiply(quantity);

            accumulator.addUnprotectedLoss(potentialLoss, position.getCurrency());

            BigDecimal positionValue = position.getPosition().abs().multiply(position.getMktPrice());
            String currency = position.getCurrency();

            accumulator.addRisk(new PositionRisk(
                    position.getAcctId(),
                    position.getContractDesc(),
                    position.getPosition(),
                    position.getAvgPrice(),
                    position.getMktPrice(),
                    assumedStopPrice,
                    quantity,
                    potentialLoss,
                    positionValue,
                    currency,
                    currencyService.convertToBase(potentialLoss, currency),
                    currencyService.convertToBase(positionValue, currency),
                    currencyService.getBaseCurrency(),
                    false
            ));
        }
    }

    // ==================== Stop Loss Creation ====================

    public List<StopLossResult> createMissingStopLosses(String accountId, BigDecimal lossPercentage) throws Exception {
        List<PositionInner> positions = fetchPositionsForAccount(accountId);
        List<IserverAccountOrdersGet200ResponseOrdersInner> stopOrders = fetchStopOrdersForAccountWithRefresh(accountId);

        // Find positions without stop orders
        Set<Integer> protectedConids = stopOrders.stream()
                .map(o -> o.getConid() != null ? o.getConid().intValue() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<StopLossResult> results = new ArrayList<>();

        for (PositionInner position : positions) {
            if (protectedConids.contains(position.getConid())) {
                continue; // Already has stop loss
            }

            if (position.getPosition().compareTo(BigDecimal.ZERO) == 0) {
                continue; // No position
            }

            results.add(createStopLossOrder(accountId, position, lossPercentage));
        }

        return results;
    }

    public StopLossResult createStopLossForPosition(String accountId, Integer conid, BigDecimal lossPercentage) throws Exception {
        List<PositionInner> positions = fetchPositionsForAccount(accountId);

        PositionInner position = positions.stream()
                .filter(p -> conid.equals(p.getConid()))
                .findFirst()
                .orElse(null);

        if (position == null) {
            return new StopLossResult(accountId, null, conid, null, null, false,
                    "Position not found for conid: " + conid);
        }

        return createStopLossOrder(accountId, position, lossPercentage);
    }

    public StopLossResult createStopLossForPositionByTicker(String accountId, String ticker, BigDecimal lossPercentage) throws Exception {
        List<PositionInner> positions = fetchPositionsForAccount(accountId);

        PositionInner position = positions.stream()
                .filter(p -> ticker.equalsIgnoreCase(p.getContractDesc()))
                .findFirst()
                .orElse(null);

        if (position == null) {
            return new StopLossResult(accountId, ticker, null, null, null, false,
                    "Position not found for ticker: " + ticker);
        }

        return createStopLossOrder(accountId, position, lossPercentage);
    }

    private StopLossResult createStopLossOrder(String accountId, PositionInner position, BigDecimal lossPercentage) {
        if (position.getPosition().compareTo(BigDecimal.ZERO) == 0) {
            return new StopLossResult(accountId, position.getContractDesc(), position.getConid(),
                    null, BigDecimal.ZERO, false, "Position size is zero");
        }

        // Check for existing stop loss
        try {
            List<IserverAccountOrdersGet200ResponseOrdersInner> existingStops =
                    fetchStopOrdersForConid(accountId, position.getConid());
            if (!existingStops.isEmpty()) {
                IserverAccountOrdersGet200ResponseOrdersInner existing = existingStops.getFirst();
                return new StopLossResult(accountId, position.getContractDesc(), position.getConid(),
                        existing.getPrice(),
                        existing.getRemainingQuantity() != null ? existing.getRemainingQuantity() : BigDecimal.ZERO,
                        false, "Stop loss already exists at price " + existing.getPrice());
            }
        } catch (Exception e) {
            System.out.println("WARN: Could not check for existing stop orders: " + e.getMessage());
        }

        try {
            BigDecimal stopPrice = calculateStopPrice(position, lossPercentage);
            boolean isLong = position.getPosition().compareTo(BigDecimal.ZERO) > 0;

            // Build order request using generated model
            OrderRequest orderRequest = new OrderRequest();
            orderRequest.setConid(position.getConid());
            orderRequest.setOrderType("STP");
            orderRequest.setPrice(stopPrice);
            orderRequest.setQuantity(position.getPosition().abs());
            orderRequest.setSide(isLong ? "SELL" : "BUY");
            orderRequest.setTif("GTC");

            IserverAccountAccountIdOrdersPostRequest request = new IserverAccountAccountIdOrdersPostRequest();
            request.setOrders(List.of(orderRequest));

            List<IserverAccountAccountIdOrderPost200ResponseInner> responses =
                    orderApi.iserverAccountAccountIdOrdersPost(accountId, request);

            // Handle confirmation if needed
            if (responses != null && !responses.isEmpty()) {
                IserverAccountAccountIdOrderPost200ResponseInner first = responses.getFirst();
                if (first.getId() != null && first.getMessage() != null && !first.getMessage().isEmpty()) {
                    // Needs confirmation
                    IserverReplyReplyidPostRequest confirmRequest = new IserverReplyReplyidPostRequest();
                    confirmRequest.setConfirmed(true);
                    orderApi.iserverReplyReplyidPost(first.getId(), confirmRequest);
                }
            }

            return new StopLossResult(accountId, position.getContractDesc(), position.getConid(),
                    stopPrice, position.getPosition().abs(), true, "Stop loss created successfully");

        } catch (Exception e) {
            return new StopLossResult(accountId, position.getContractDesc(), position.getConid(),
                    null, position.getPosition().abs(), false, "Failed: " + e.getMessage());
        }
    }

    public String createStopLossForPositionDebug(String accountId, String ticker, BigDecimal lossPercentage) throws Exception {
        List<PositionInner> positions = fetchPositionsForAccount(accountId);

        PositionInner position = positions.stream()
                .filter(p -> ticker.equalsIgnoreCase(p.getContractDesc()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Position not found: " + ticker));

        BigDecimal stopPrice = calculateStopPrice(position, lossPercentage);
        boolean isLong = position.getPosition().compareTo(BigDecimal.ZERO) > 0;

        OrderRequest orderRequest = new OrderRequest();
        orderRequest.setConid(position.getConid());
        orderRequest.setOrderType("STP");
        orderRequest.setPrice(stopPrice);
        orderRequest.setQuantity(position.getPosition().abs());
        orderRequest.setSide(isLong ? "SELL" : "BUY");
        orderRequest.setTif("GTC");

        IserverAccountAccountIdOrdersPostRequest request = new IserverAccountAccountIdOrdersPostRequest();
        request.setOrders(List.of(orderRequest));

        StringBuilder debug = new StringBuilder();
        debug.append("=== Request ===\n");
        debug.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
        debug.append("\n\n=== Response 1 (Place Order) ===\n");

        List<IserverAccountAccountIdOrderPost200ResponseInner> responses =
                orderApi.iserverAccountAccountIdOrdersPost(accountId, request);
        debug.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(responses));

        if (responses != null && !responses.isEmpty()) {
            IserverAccountAccountIdOrderPost200ResponseInner first = responses.getFirst();
            if (first.getId() != null && first.getMessage() != null && !first.getMessage().isEmpty()) {
                debug.append("\n\n=== Response 2 (Confirm) ===\n");
                IserverReplyReplyidPostRequest confirmRequest = new IserverReplyReplyidPostRequest();
                confirmRequest.setConfirmed(true);
                var confirmResponse = orderApi.iserverReplyReplyidPost(first.getId(), confirmRequest);
                debug.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(confirmResponse));
            }
        }

        return debug.toString();
    }

    // ==================== Helper Methods ====================

    private List<PositionInner> fetchPositionsForAccount(String accountId) throws ApiException {
        List<PositionInner> positions = portfolioApi.portfolioAccountIdPositionsPageIdGet(
                accountId, "0", null, null, null, null);
        return positions != null ? positions : List.of();
    }

    private List<IserverAccountOrdersGet200ResponseOrdersInner> fetchStopOrdersForAccountWithRefresh(String accountId) throws Exception {
        // Switch account
        SetAccount setAccount = new SetAccount();
        setAccount.setAcctId(accountId);
        accountApi.iserverAccountPost(setAccount);
        Thread.sleep(200);

        // Force refresh
        orderApi.iserverAccountOrdersGet(null);
        Thread.sleep(200);

        return fetchStopOrdersForAccount(accountId);
    }

    private List<IserverAccountOrdersGet200ResponseOrdersInner> fetchStopOrdersForConid(String accountId, Integer conid) throws Exception {
        List<IserverAccountOrdersGet200ResponseOrdersInner> allStops = fetchStopOrdersForAccountWithRefresh(accountId);
        return allStops.stream()
                .filter(o -> conid.equals(o.getConid() != null ? o.getConid().intValue() : null))
                .toList();
    }

    private BigDecimal calculateAssumedStopPrice(PositionInner position) {
        BigDecimal lossMultiplier = BigDecimal.ONE.subtract(
                unprotectedLossPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
        );

        if (position.getPosition().compareTo(BigDecimal.ZERO) > 0) {
            return position.getAvgPrice().multiply(lossMultiplier);
        } else {
            BigDecimal gainMultiplier = BigDecimal.ONE.add(
                    unprotectedLossPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
            );
            return position.getAvgPrice().multiply(gainMultiplier);
        }
    }

    private BigDecimal calculateStopPrice(PositionInner position, BigDecimal lossPercentage) {
        BigDecimal multiplier = lossPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        if (position.getPosition().compareTo(BigDecimal.ZERO) > 0) {
            // Long: stop below current price
            return position.getMktPrice().multiply(BigDecimal.ONE.subtract(multiplier))
                    .setScale(2, RoundingMode.DOWN);
        } else {
            // Short: stop above current price
            return position.getMktPrice().multiply(BigDecimal.ONE.add(multiplier))
                    .setScale(2, RoundingMode.UP);
        }
    }

    // ==================== Inner Classes & Records ====================

    private static class RiskAccumulator {
        private final CurrencyConversionService currencyService;
        private BigDecimal protectedLossBase = BigDecimal.ZERO;
        private BigDecimal unprotectedLossBase = BigDecimal.ZERO;
        private final List<PositionRisk> risks = new ArrayList<>();

        RiskAccumulator(CurrencyConversionService currencyService) {
            this.currencyService = currencyService;
        }

        void addProtectedLoss(BigDecimal loss, String currency) {
            protectedLossBase = protectedLossBase.add(currencyService.convertToBase(loss, currency));
        }

        void addUnprotectedLoss(BigDecimal loss, String currency) {
            unprotectedLossBase = unprotectedLossBase.add(currencyService.convertToBase(loss, currency));
        }

        void addRisk(PositionRisk risk) {
            risks.add(risk);
        }

        RiskReport toReport(BigDecimal unprotectedLossPercentage) {
            List<PositionRisk> sortedRisks = risks.stream()
                    .sorted(Comparator.comparing(PositionRisk::potentialLossBase).reversed())
                    .toList();

            return new RiskReport(
                    protectedLossBase.add(unprotectedLossBase),
                    protectedLossBase,
                    unprotectedLossBase,
                    currencyService.getBaseCurrency(),
                    unprotectedLossPercentage,
                    sortedRisks
            );
        }
    }

    public record StopLossResult(
            String accountId,
            String ticker,
            Integer conid,
            BigDecimal stopPrice,
            BigDecimal quantity,
            boolean success,
            String message
    ) {}

    public record PositionRisk(
            String accountId,
            String ticker,
            BigDecimal positionSize,
            BigDecimal avgPrice,
            BigDecimal currentPrice,
            BigDecimal stopPrice,
            BigDecimal orderQuantity,
            BigDecimal potentialLoss,
            BigDecimal positionValue,
            String currency,
            BigDecimal potentialLossBase,
            BigDecimal positionValueBase,
            String baseCurrency,
            boolean hasStopLoss
    ) {}

    public record RiskReport(
            BigDecimal totalPotentialLoss,
            BigDecimal protectedLoss,
            BigDecimal unprotectedLoss,
            String currency,
            BigDecimal unprotectedLossPercentageUsed,
            List<PositionRisk> positionRisks
    ) {}
}