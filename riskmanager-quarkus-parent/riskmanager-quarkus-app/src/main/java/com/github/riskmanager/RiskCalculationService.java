
package com.github.riskmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.riskmanager.broker.BrokerException;
import com.github.riskmanager.broker.BrokerGateway;
import com.github.riskmanager.broker.BrokerGateway.*;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class RiskCalculationService {

    public static final String ORDER_TYPE_STOP_LOSS = "STP";
    public static final String ORDER_SIDE_SELL = "SELL";
    public static final String ORDER_SIDE_BUY = "BUY";
    public static final String TIME_IN_FORCE_GTC = "GTC";

    @Inject
    BrokerGateway brokerGateway;

    @Inject
    CurrencyConversionService currencyService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "risk.unprotected-loss-percentage", defaultValue = "50")
    BigDecimal unprotectedLossPercentage;

    // Composite key for position uniqueness across accounts
    private record PositionKey(Integer conid, String accountId) {}

    // ==================== Risk Calculation ====================

    public RiskReport calculateWorstCaseScenarioForAccounts(List<String> accountIds) throws BrokerException {
        List<Position> allPositions = new ArrayList<>();
        for (String accountId : accountIds) {
            allPositions.addAll(brokerGateway.getPositions(accountId));
        }
        List<Order> stopOrders = brokerGateway.getAllStopOrders();

        Map<PositionKey, Position> positionsByKey = buildPositionMap(allPositions);

        RiskAccumulator accumulator = new RiskAccumulator(currencyService);
        Set<PositionKey> protectedPositions = processProtectedPositions(stopOrders, positionsByKey, accumulator);
        processUnprotectedPositions(allPositions, protectedPositions, accumulator);

        return accumulator.toReport(unprotectedLossPercentage);
    }

    private Map<PositionKey, Position> buildPositionMap(List<Position> positions) {
        return positions.stream()
                .collect(Collectors.toMap(
                        p -> new PositionKey(p.conid(), p.accountId()),
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
            Integer conid = order.conid();
            if (conid == null) continue;

            PositionKey key = new PositionKey(conid, order.accountId());
            Position position = positionsByKey.get(key);

            BigDecimal stopPrice = order.stopPrice();
            if (stopPrice == null || position == null) continue;

            protectedPositions.add(key);

            BigDecimal quantity = order.remainingQuantity() != null
                    ? order.remainingQuantity()
                    : (order.quantity() != null ? order.quantity() : BigDecimal.ZERO);

            String ticker = order.ticker() != null ? order.ticker() : position.ticker();
            addPositionRisk(accumulator, position, stopPrice, quantity.abs(), ticker, true);
        }
        return protectedPositions;
    }

    private void processUnprotectedPositions(
            List<Position> allPositions,
            Set<PositionKey> protectedPositions,
            RiskAccumulator accumulator
    ) {
        for (Position position : allPositions) {
            PositionKey key = new PositionKey(position.conid(), position.accountId());
            if (protectedPositions.contains(key) || position.isZero()) {
                continue;
            }

            BigDecimal quantity = position.quantity().abs();
            BigDecimal assumedStopPrice = calculateAssumedStopPrice(position);
            addPositionRisk(accumulator, position, assumedStopPrice, quantity, position.ticker(), false);
        }
    }

    private void addPositionRisk(
            RiskAccumulator accumulator,
            Position position,
            BigDecimal stopPrice,
            BigDecimal quantity,
            String ticker,
            boolean hasStopLoss
    ) {
        BigDecimal potentialLoss = calculateLossPerShare(position.quantity(), position.avgPrice(), stopPrice)
                .multiply(quantity);
        BigDecimal positionValue = position.quantity().abs().multiply(position.marketPrice());
        String currency = position.currency();

        if (hasStopLoss) {
            accumulator.addProtectedLoss(potentialLoss, currency);
        } else {
            accumulator.addUnprotectedLoss(potentialLoss, currency);
        }

        accumulator.addRisk(new PositionRisk(
                position.accountId(),
                ticker,
                position.quantity(),
                position.avgPrice(),
                position.marketPrice(),
                stopPrice,
                quantity,
                potentialLoss,
                positionValue,
                currency,
                currencyService.convertToBase(potentialLoss, currency),
                currencyService.convertToBase(positionValue, currency),
                currencyService.getBaseCurrency(),
                hasStopLoss
        ));
    }

    private BigDecimal calculateLossPerShare(BigDecimal positionSize, BigDecimal avgPrice, BigDecimal stopPrice) {
        if (positionSize.compareTo(BigDecimal.ZERO) > 0) {
            return avgPrice.subtract(stopPrice);
        } else {
            return stopPrice.subtract(avgPrice);
        }
    }

    private BigDecimal calculateAssumedStopPrice(Position position) {
        BigDecimal lossMultiplier = BigDecimal.ONE.subtract(
                unprotectedLossPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
        );

        if (position.isLong()) {
            return position.avgPrice().multiply(lossMultiplier);
        } else {
            BigDecimal gainMultiplier = BigDecimal.ONE.add(
                    unprotectedLossPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
            );
            return position.avgPrice().multiply(gainMultiplier);
        }
    }

    // ==================== Stop Loss Creation ====================

    public List<StopLossResult> createMissingStopLosses(String accountId, BigDecimal lossPercentage) throws BrokerException {
        List<Position> positions = brokerGateway.getPositions(accountId);
        List<Order> stopOrders = brokerGateway.getStopOrders(accountId);

        Set<Integer> protectedConids = stopOrders.stream()
                .map(Order::conid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<StopLossResult> results = new ArrayList<>();

        for (Position position : positions) {
            if (protectedConids.contains(position.conid()) || position.isZero()) {
                continue;
            }
            results.add(createStopLossOrder(accountId, position, lossPercentage));
        }

        return results;
    }

    public StopLossResult createStopLossForPosition(String accountId, Integer conid, BigDecimal lossPercentage) throws BrokerException {
        List<Position> positions = brokerGateway.getPositions(accountId);

        Position position = positions.stream()
                .filter(p -> conid.equals(p.conid()))
                .findFirst()
                .orElse(null);

        if (position == null) {
            return new StopLossResult(accountId, null, conid, null, null, false,
                    "Position not found for conid: " + conid);
        }

        return createStopLossOrder(accountId, position, lossPercentage);
    }

    public StopLossResult createStopLossForPositionByTicker(String accountId, String ticker, BigDecimal lossPercentage) throws BrokerException {
        List<Position> positions = brokerGateway.getPositions(accountId);

        Position position = positions.stream()
                .filter(p -> ticker.equalsIgnoreCase(p.ticker()))
                .findFirst()
                .orElse(null);

        if (position == null) {
            return new StopLossResult(accountId, ticker, null, null, null, false,
                    "Position not found for ticker: " + ticker);
        }

        return createStopLossOrder(accountId, position, lossPercentage);
    }

    private StopLossResult createStopLossOrder(String accountId, Position position, BigDecimal lossPercentage) {
        if (position.isZero()) {
            return zeroPositionResult(accountId, position);
        }

        Optional<StopLossResult> existing = checkExistingStopLoss(accountId, position);
        if (existing.isPresent()) {
            return existing.get();
        }

        return placeNewStopLoss(accountId, position, lossPercentage);
    }

    private StopLossResult placeNewStopLoss(String accountId, Position position, BigDecimal lossPercentage) {
        try {
            BigDecimal stopPrice = calculateStopPrice(position, lossPercentage);

            StopLossOrderRequest request = new StopLossOrderRequest(
                    accountId,
                    position.conid(),
                    stopPrice,
                    position.quantity().abs(),
                    position.isLong()
            );

            OrderResult result = brokerGateway.placeStopLossOrder(request);

            return new StopLossResult(
                    accountId,
                    position.ticker(),
                    position.conid(),
                    stopPrice,
                    position.quantity().abs(),
                    result.success(),
                    result.message()
            );

        } catch (Exception e) {
            return new StopLossResult(
                    accountId,
                    position.ticker(),
                    position.conid(),
                    null,
                    position.quantity().abs(),
                    false,
                    "Failed: " + e.getMessage()
            );
        }
    }

    private StopLossResult zeroPositionResult(String accountId, Position position) {
        return new StopLossResult(
                accountId,
                position.ticker(),
                position.conid(),
                null,
                BigDecimal.ZERO,
                false,
                "Position size is zero"
        );
    }

    private Optional<StopLossResult> checkExistingStopLoss(String accountId, Position position) {
        try {
            List<Order> existingStops = brokerGateway.getStopOrdersForConid(accountId, position.conid());

            if (!existingStops.isEmpty()) {
                Order existing = existingStops.getFirst();
                return Optional.of(new StopLossResult(
                        accountId,
                        position.ticker(),
                        position.conid(),
                        existing.price(),
                        existing.remainingQuantity() != null ? existing.remainingQuantity() : BigDecimal.ZERO,
                        false,
                        "Stop loss already exists at price " + existing.price()
                ));
            }
        } catch (Exception e) {
            Log.warnf("Could not check for existing stop orders: %s", e.getMessage());
        }
        return Optional.empty();
    }

    public String createStopLossForPositionDebug(String accountId, String ticker, BigDecimal lossPercentage) throws Exception {
        List<Position> positions = brokerGateway.getPositions(accountId);

        Position position = positions.stream()
                .filter(p -> ticker.equalsIgnoreCase(p.ticker()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Position not found: " + ticker));

        BigDecimal stopPrice = calculateStopPrice(position, lossPercentage);

        StopLossOrderRequest request = new StopLossOrderRequest(
                accountId,
                position.conid(),
                stopPrice,
                position.quantity().abs(),
                position.isLong()
        );

        StringBuilder debug = new StringBuilder();
        debug.append("=== Request ===\n");
        debug.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request));
        debug.append("\n\n=== Response ===\n");

        OrderResult result = brokerGateway.placeStopLossOrder(request);
        debug.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        return debug.toString();
    }

    private BigDecimal calculateStopPrice(Position position, BigDecimal lossPercentage) {
        BigDecimal multiplier = lossPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        if (position.isLong()) {
            return position.marketPrice().multiply(BigDecimal.ONE.subtract(multiplier))
                    .setScale(2, RoundingMode.DOWN);
        } else {
            return position.marketPrice().multiply(BigDecimal.ONE.add(multiplier))
                    .setScale(2, RoundingMode.UP);
        }
    }
}