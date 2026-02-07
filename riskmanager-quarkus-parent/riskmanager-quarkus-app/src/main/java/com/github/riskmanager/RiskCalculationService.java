package com.github.riskmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.riskmanager.dto.Order;
import com.github.riskmanager.dto.OrdersResponse;
import com.github.riskmanager.dto.*;
import com.github.riskmanager.dto.Position;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class RiskCalculationService {

    @Inject
    @RestClient
    IBClient ibClient;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "risk.accounts")
    List<String> accounts;

    @ConfigProperty(name = "risk.unprotected-loss-percentage", defaultValue = "50")
    BigDecimal unprotectedLossPercentage;

    // Composite key for position uniqueness across accounts
    private record PositionKey(Long conid, String accountId) {}

    public RiskReport calculateWorstCaseScenarioForAccounts(List<String> accountIds) throws Exception {
        List<Position> allPositions = fetchAllPositions(accountIds);
        List<Order> stopOrders = fetchAllStopOrders();

        Map<PositionKey, Position> positionsByKey = buildPositionMap(allPositions);

        RiskAccumulator accumulator = new RiskAccumulator();
        Set<PositionKey> protectedPositions = processProtectedPositions(stopOrders, positionsByKey, accumulator);
        processUnprotectedPositions(allPositions, protectedPositions, accumulator);

        return accumulator.toReport(unprotectedLossPercentage);
    }

    private List<Position> fetchAllPositions(List<String> accountIds) {
        List<Position> allPositions = new ArrayList<>();
        for (String accountId : accountIds) {
            allPositions.addAll(ibClient.getPositions(accountId));
        }
        return allPositions;
    }

    private List<Order> fetchAllStopOrders() throws Exception {
        Set<Long> seenOrderIds = new HashSet<>();
        List<Order> stopOrders = new ArrayList<>();

        for (String acct : accounts) {
            // Switch to this account before fetching orders
            try {
                var response = ibClient.switchAccount(new IBClient.SwitchAccountRequest(acct));
                System.out.println("DEBUG: Switched to account " + acct + ", success=" + response.set());
                Thread.sleep(200);
            } catch (Exception e) {
                System.out.println("DEBUG: Failed to switch to account " + acct + ": " + e.getMessage());
            }

            // Force refresh for this account
            ibClient.getOrders(acct, null, true);
            Thread.sleep(300);

            for (Order order : fetchStopOrdersForAccount(acct)) {
                if (order.orderId() != null && seenOrderIds.add(order.orderId())) {
                    stopOrders.add(order);
                }
            }
        }
        System.out.println("DEBUG: Unique stop orders: " + stopOrders.size());
        return stopOrders;
    }

    private Map<PositionKey, Position> buildPositionMap(List<Position> positions) {
        return positions.stream()
                .collect(Collectors.toMap(
                        p -> new PositionKey(p.conid(), p.acctId()),
                        Function.identity(),
                        (a, b) -> a
                ));
    }

    private Set<PositionKey> processProtectedPositions(
            List<Order> stopOrders,
            Map<PositionKey, Position> positionsByKey,
            RiskAccumulator accumulator
    ) {
        Set<PositionKey> protectedPositions = new HashSet<>();

        for (Order order : stopOrders) {
            PositionKey key = new PositionKey(order.conid(), order.getEffectiveAccount());
            Position position = positionsByKey.get(key);

            BigDecimal stopPrice = order.getEffectiveStopPrice();
            if (stopPrice == null || position == null) continue;

            protectedPositions.add(key);

            BigDecimal quantity = order.remainingQuantity() != null
                    ? order.remainingQuantity()
                    : order.totalSize();

            BigDecimal potentialLoss = calculateLossPerShare(position.position(), position.avgPrice(), stopPrice)
                    .multiply(quantity);

            accumulator.addProtectedLoss(potentialLoss);
            accumulator.addRisk(new PositionRisk(
                    position.acctId(),
                    order.ticker() != null ? order.ticker() : position.contractDesc(),
                    position.position(),
                    position.avgPrice(),
                    position.mktPrice(),
                    stopPrice,
                    quantity,
                    potentialLoss,
                    true
            ));
        }
        return protectedPositions;
    }

    private void processUnprotectedPositions(
            List<Position> allPositions,
            Set<PositionKey> protectedPositions,
            RiskAccumulator accumulator
    ) {
        for (Position position : allPositions) {
            PositionKey key = new PositionKey(position.conid(), position.acctId());
            if (protectedPositions.contains(key) || position.position().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal quantity = position.position().abs();
            BigDecimal assumedStopPrice = calculateAssumedStopPrice(position);
            BigDecimal potentialLoss = calculateLossPerShare(position.position(), position.avgPrice(), assumedStopPrice)
                    .multiply(quantity);

            accumulator.addUnprotectedLoss(potentialLoss);
            accumulator.addRisk(new PositionRisk(
                    position.acctId(),
                    position.contractDesc() != null ? position.contractDesc() : position.contractDesc(),
                    position.position(),
                    position.avgPrice(),
                    position.mktPrice(),
                    assumedStopPrice,
                    quantity,
                    potentialLoss,
                    false
            ));
        }
    }

    // Helper class to accumulate risk calculations
    private static class RiskAccumulator {
        private BigDecimal protectedLoss = BigDecimal.ZERO;
        private BigDecimal unprotectedLoss = BigDecimal.ZERO;
        private final List<PositionRisk> risks = new ArrayList<>();

        void addProtectedLoss(BigDecimal loss) {
            protectedLoss = protectedLoss.add(loss);
        }

        void addUnprotectedLoss(BigDecimal loss) {
            unprotectedLoss = unprotectedLoss.add(loss);
        }

        void addRisk(PositionRisk risk) {
            risks.add(risk);
        }

        RiskReport toReport(BigDecimal unprotectedLossPercentage) {
            return new RiskReport(
                    protectedLoss.add(unprotectedLoss),
                    protectedLoss,
                    unprotectedLoss,
                    unprotectedLossPercentage,
                    risks
            );
        }
    }

    public RiskReport calculateWorstCaseScenario(String accountId) throws Exception {
        List<Position> positions = fetchPositions(accountId);

        // Fetch stop orders for ALL configured accounts
        Set<Long> seenOrderIds = new HashSet<>();
        List<Order> stopOrders = new ArrayList<>();

        for (String acct : accounts) {
            for (Order order : fetchStopOrdersForAccount(acct)) {
                // Deduplicate by orderId
                System.out.println("DEBUG: Stop order: " + order);
                if (order.orderId() != null && seenOrderIds.add(order.orderId())) {
                    stopOrders.add(order);
                }
            }
        }

        System.out.println("DEBUG: Unique stop orders: " + stopOrders.size());

        // Map positions by conid for quick lookup
        Map<Long, Position> positionsByConid = positions.stream()
                .collect(Collectors.toMap(Position::conid, Function.identity(), (a, b) -> a));

        // Track which positions have stop orders
        Set<Long> protectedConids = new HashSet<>();

        BigDecimal totalProtectedLoss = BigDecimal.ZERO;
        BigDecimal totalUnprotectedLoss = BigDecimal.ZERO;
        List<PositionRisk> positionRisks = new ArrayList<>();

        // Process positions WITH stop orders
        for (Order order : stopOrders) {
            Position position = positionsByConid.get(order.conid());

            BigDecimal stopPrice = order.getEffectiveStopPrice();
            if (stopPrice == null) continue;

            BigDecimal avgPrice;
            BigDecimal quantity = order.remainingQuantity() != null
                    ? order.remainingQuantity()
                    : order.totalSize();

            if (position != null) {
                avgPrice = position.avgPrice();
                protectedConids.add(order.conid());
            } else {
                continue;
            }

            BigDecimal lossPerShare = calculateLossPerShare(position.position(), avgPrice, stopPrice);
            BigDecimal potentialLoss = lossPerShare.multiply(quantity);

            totalProtectedLoss = totalProtectedLoss.add(potentialLoss);

            positionRisks.add(new PositionRisk(
                    position.acctId(),   // Add account ID
                    order.ticker() != null ? order.ticker() : position.contractDesc(),
                    position.position(),
                    avgPrice,
                    position.mktPrice(),
                    stopPrice,
                    quantity,
                    potentialLoss,
                    true
            ));
        }

        // Process positions WITHOUT stop orders
        for (Position position : positions) {
            if (protectedConids.contains(position.conid())) {
                continue;
            }

            if (position.position().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            BigDecimal avgPrice = position.avgPrice();
            BigDecimal quantity = position.position().abs();

            BigDecimal assumedStopPrice = calculateAssumedStopPrice(position);
            BigDecimal lossPerShare = calculateLossPerShare(position.position(), avgPrice, assumedStopPrice);
            BigDecimal potentialLoss = lossPerShare.multiply(quantity);

            totalUnprotectedLoss = totalUnprotectedLoss.add(potentialLoss);

            positionRisks.add(new PositionRisk(
                    position.acctId(),   // Add account ID
                    position.contractDesc() != null ? position.contractDesc() : position.contractDesc(),
                    position.position(),
                    avgPrice,
                    position.mktPrice(),
                    assumedStopPrice,
                    quantity,
                    potentialLoss,
                    false
            ));
        }

        BigDecimal totalPotentialLoss = totalProtectedLoss.add(totalUnprotectedLoss);

        return new RiskReport(
                totalPotentialLoss,
                totalProtectedLoss,
                totalUnprotectedLoss,
                unprotectedLossPercentage,
                positionRisks
        );
    }

    private List<Order> fetchStopOrdersForAccount(String accountId) throws Exception {
        // Skip the force refresh here since we do it once in fetchAllStopOrders
        OrdersResponse response = ibClient.getOrders(accountId, null, false);

        if (response.orders() == null) {
            System.out.println("DEBUG: Orders for " + accountId + ": null");
            return List.of();
        }

        System.out.println("DEBUG: Orders for " + accountId + ": " + response.orders().size());

        return response.orders().stream()
                .filter(o -> {
                    String type = o.getEffectiveOrderType();
                    return type != null && (
                            type.toLowerCase().contains("stop") ||
                                    type.equalsIgnoreCase("STP")
                    );
                })
                .filter(o -> {
                    String status = o.getEffectiveStatus();
                    System.out.println("DEBUG Stop Order: ticker=" + o.ticker() +
                            ", acct=" + o.acct() +
                            ", account=" + o.account() +
                            ", conid=" + o.conid() +
                            ", status=" + status);
                    return status == null || (
                            !status.equalsIgnoreCase("Cancelled") &&
                                    !status.equalsIgnoreCase("Filled")
                    );
                })
                .toList();
    }

    private BigDecimal calculateAssumedStopPrice(Position position) {
        BigDecimal lossMultiplier = BigDecimal.ONE.subtract(
                unprotectedLossPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
        );

        if (position.position().compareTo(BigDecimal.ZERO) > 0) {
            return position.avgPrice().multiply(lossMultiplier);
        } else {
            BigDecimal gainMultiplier = BigDecimal.ONE.add(
                    unprotectedLossPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
            );
            return position.avgPrice().multiply(gainMultiplier);
        }
    }



    public List<StopLossResult> createMissingStopLosses(String accountId, BigDecimal lossPercentage) throws Exception {
        List<Position> positions = fetchPositions(accountId);
        List<Order> stopOrders = fetchStopOrders(accountId);

        // Find positions without stop orders
        Set<Long> protectedConids = stopOrders.stream()
                .map(Order::conid)
                .collect(Collectors.toSet());

        List<StopLossResult> results = new ArrayList<>();

        for (Position position : positions) {
            if (protectedConids.contains(position.conid())) {
                continue; // Already has stop loss
            }

            if (position.position().compareTo(BigDecimal.ZERO) == 0) {
                continue; // No position
            }

            try {
                BigDecimal stopPrice = calculateStopPrice(position, lossPercentage);
                boolean isLong = position.position().compareTo(BigDecimal.ZERO) > 0;

                PlaceOrderRequest orderRequest = PlaceOrderRequest.stopLoss(
                        position.conid(),
                        position.position().abs(),
                        stopPrice,
                        isLong
                );


                List<PlaceOrderResponse> responses = ibClient.placeOrder(accountId, SubmitOrderRequest.single(orderRequest));

                // Handle confirmation if needed
                if (responses.size() > 0 && responses.getFirst().needsConfirmation()) {
                    responses = ibClient.confirmOrder(
                            responses.getFirst().id(),
                            new IBClient.ConfirmRequest(true)
                    );
                }

                results.add(new StopLossResult(
                        accountId,
                        position.contractDesc() != null ? position.contractDesc() : position.contractDesc(),
                        position.conid(),
                        stopPrice,
                        position.position().abs(),
                        true,
                        "Stop loss created successfully"
                ));

            } catch (Exception e) {
                results.add(new StopLossResult(
                        accountId,
                        position.contractDesc() != null ? position.contractDesc() : position.contractDesc(),
                        position.conid(),
                        null,
                        position.position().abs(),
                        false,
                        "Failed: " + e.getMessage()
                ));
            }
        }

        return results;
    }

    private BigDecimal calculateStopPrice(Position position, BigDecimal lossPercentage) {
        BigDecimal multiplier = lossPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        if (position.position().compareTo(BigDecimal.ZERO) > 0) {
            // Long: stop below current price
            return position.mktPrice().multiply(BigDecimal.ONE.subtract(multiplier))
                    .setScale(2, RoundingMode.DOWN);
        } else {
            // Short: stop above current price
            return position.mktPrice().multiply(BigDecimal.ONE.add(multiplier))
                    .setScale(2, RoundingMode.UP);
        }
    }

    public record StopLossResult(
            String accountId,
            String ticker,
            Long conid,
            BigDecimal stopPrice,
            BigDecimal quantity,
            boolean success,
            String message
    ) {}

    private BigDecimal calculateLossPerShare(BigDecimal positionSize, BigDecimal avgPrice, BigDecimal stopPrice) {
        if (positionSize.compareTo(BigDecimal.ZERO) > 0) {
            return avgPrice.subtract(stopPrice);
        } else {
            return stopPrice.subtract(avgPrice);
        }
    }

    private List<Position> fetchPositions(String accountId) throws Exception {
        return ibClient.getPositions(accountId);

    }

    private List<Order> fetchStopOrders(String accountId) throws Exception {
        // Force refresh, then get orders for account
        ibClient.getOrders(accountId, null, true);

        // Small delay to let IB process the refresh
        Thread.sleep(200);

        OrdersResponse response = ibClient.getOrders(accountId, null, false);

        if (response.orders() == null || response.orders().isEmpty()) {
            System.out.println("DEBUG: No orders returned for " + accountId);
            return List.of();
        }

        System.out.println("DEBUG: Total orders from API for " + accountId + ": " + response.orders().size());

        // Filter to stop orders only
        List<Order> stopOrders = response.orders().stream()
                .filter(o -> {
                    String type = o.getEffectiveOrderType();
                    return type != null && (
                            type.toLowerCase().contains("stop") ||
                                    type.equalsIgnoreCase("STP")
                    );
                })
                .filter(o -> {
                    String status = o.getEffectiveStatus();
                    return status == null || (
                            !status.equalsIgnoreCase("Cancelled") &&
                                    !status.equalsIgnoreCase("Filled")
                    );
                })
                .toList();

        System.out.println("DEBUG: Active stop orders for " + accountId + ": " + stopOrders.size());
        stopOrders.forEach(o -> System.out.println("  Stop: " + o.ticker() +
                " account=" + o.account() +
                " status=" + o.getEffectiveStatus() +
                " conid=" + o.conid()));

        return stopOrders;
    }

    public StopLossResult createStopLossForPosition(String accountId, Long conid, BigDecimal lossPercentage) throws Exception {
        List<Position> positions = fetchPositions(accountId);

        Position position = positions.stream()
                .filter(p -> p.conid().equals(conid))
                .findFirst()
                .orElse(null);

        if (position == null) {
            return new StopLossResult(
                    accountId,
                    null,
                    conid,
                    null,
                    null,
                    false,
                    "Position not found for conid: " + conid
            );
        }

        return createStopLossForPosition(accountId, position, lossPercentage);
    }

    public StopLossResult createStopLossForPositionByTicker(String accountId, String ticker, BigDecimal lossPercentage) throws Exception {
        List<Position> positions = fetchPositions(accountId);

        Position position = positions.stream()
                .filter(p -> ticker.equalsIgnoreCase(p.contractDesc()) || ticker.equalsIgnoreCase(p.contractDesc()))
                .findFirst()
                .orElse(null);

        if (position == null) {
            return new StopLossResult(
                    accountId,
                    ticker,
                    null,
                    null,
                    null,
                    false,
                    "Position not found for ticker: " + ticker
            );
        }

        return createStopLossForPosition(accountId, position, lossPercentage);
    }

    private StopLossResult createStopLossForPosition(String accountId, Position position, BigDecimal lossPercentage) {
        if (position.position().compareTo(BigDecimal.ZERO) == 0) {
            return new StopLossResult(
                    accountId,
                    position.contractDesc(),
                    position.conid(),
                    null,
                    BigDecimal.ZERO,
                    false,
                    "Position size is zero"
            );
        }

        // Check if a stop loss already exists for this position
        try {
            List<Order> existingStops = fetchStopOrdersForConid(accountId, position.conid());
            if (!existingStops.isEmpty()) {
                Order existing = existingStops.getFirst();
                return new StopLossResult(
                        accountId,
                        position.contractDesc(),
                        position.conid(),
                        existing.getEffectiveStopPrice(),
                        existing.remainingQuantity() != null ? existing.remainingQuantity() : existing.totalSize(),
                        false,
                        "Stop loss already exists at price " + existing.getEffectiveStopPrice()
                );
            }
        } catch (Exception e) {
            System.out.println("WARN: Could not check for existing stop orders: " + e.getMessage());
        }

        try {
            BigDecimal stopPrice = calculateStopPrice(position, lossPercentage);
            boolean isLong = position.position().compareTo(BigDecimal.ZERO) > 0;

            PlaceOrderRequest orderRequest = PlaceOrderRequest.stopLoss(
                    position.conid(),
                    position.position().abs(),
                    stopPrice,
                    isLong
            );

            List<PlaceOrderResponse> responses = ibClient.placeOrder(accountId, SubmitOrderRequest.single(orderRequest));

            // Handle confirmation if needed
            if (responses.size() > 0 && responses.getFirst().needsConfirmation()) {
                responses = ibClient.confirmOrder(
                        responses.getFirst().id(),
                        new IBClient.ConfirmRequest(true)
                );
            }

            return new StopLossResult(
                    accountId,
                    position.contractDesc() != null ? position.contractDesc() : position.contractDesc(),
                    position.conid(),
                    stopPrice,
                    position.position().abs(),
                    true,
                    "Stop loss created successfully"
            );

        } catch (Exception e) {
            return new StopLossResult(
                    accountId,
                    position.contractDesc() != null ? position.contractDesc() : position.contractDesc(),
                    position.conid(),
                    null,
                    position.position().abs(),
                    false,
                    "Failed: " + e.getMessage()
            );
        }
    }

    private List<Order> fetchStopOrdersForConid(String accountId, Long conid) throws Exception {
        // Switch to the account first
        ibClient.switchAccount(new IBClient.SwitchAccountRequest(accountId));
        Thread.sleep(200);

        ibClient.getOrders(accountId, null, true);
        Thread.sleep(200);

        OrdersResponse response = ibClient.getOrders(accountId, null, false);

        if (response.orders() == null) {
            return List.of();
        }

        return response.orders().stream()
                .filter(o -> conid.equals(o.conid()))
                .filter(o -> {
                    String type = o.getEffectiveOrderType();
                    return type != null && (type.toLowerCase().contains("stop") || type.equalsIgnoreCase("STP"));
                })
                .filter(o -> {
                    String status = o.getEffectiveStatus();
                    return status == null || (!status.equalsIgnoreCase("Cancelled") && !status.equalsIgnoreCase("Filled"));
                })
                .toList();
    }

    public String createStopLossForPositionDebug(String accountId, String ticker, BigDecimal lossPercentage) throws Exception {
        List<Position> positions = fetchPositions(accountId);

        Position position = positions.stream()
                .filter(p -> ticker.equalsIgnoreCase(p.contractDesc()) || ticker.equalsIgnoreCase(p.contractDesc()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Position not found: " + ticker));

        BigDecimal stopPrice = calculateStopPrice(position, lossPercentage);
        boolean isLong = position.position().compareTo(BigDecimal.ZERO) > 0;

        PlaceOrderRequest orderRequest = PlaceOrderRequest.stopLoss(
                position.conid(),
                position.position().abs(),
                stopPrice,
                isLong
        );

        // Return raw response for debugging
        StringBuilder debug = new StringBuilder();
        debug.append("=== Request ===\n");
        debug.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(SubmitOrderRequest.single(orderRequest)));
        debug.append("\n\n=== Response 1 (Place Order) ===\n");

        List<PlaceOrderResponse> responses = ibClient.placeOrder(accountId, SubmitOrderRequest.single(orderRequest));
        debug.append(responses);



        if (responses.size() > 0 && responses.getFirst().needsConfirmation()) {
            debug.append("\n\n=== Response 2 (Confirm) ===\n");
            List<PlaceOrderResponse> confirmJson = ibClient.confirmOrder(
                    responses.getFirst().id(),
                    new IBClient.ConfirmRequest(true)
            );
            debug.append(confirmJson);
        }

        return debug.toString();
    }

    // ... existing code ...

    public record PositionRisk(
            String accountId,
            String ticker,
            BigDecimal positionSize,
            BigDecimal avgPrice,
            BigDecimal currentPrice,
            BigDecimal stopPrice,
            BigDecimal orderQuantity,
            BigDecimal potentialLoss,
            boolean hasStopLoss
    ) {}

// ... existing code ...

    public record RiskReport(
            BigDecimal totalPotentialLoss,
            BigDecimal protectedLoss,
            BigDecimal unprotectedLoss,
            BigDecimal unprotectedLossPercentageUsed,
            List<PositionRisk> positionRisks
    ) {}
}