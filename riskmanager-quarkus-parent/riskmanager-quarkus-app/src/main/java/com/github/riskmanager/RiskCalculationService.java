
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

    private static final int CURRENCY_SCALE = 2;
    private static final int SIZE_SCALE = 4;

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
        // Fetch all positions once (not per account) to avoid duplicate market data requests
        List<Position> allPositions = brokerGateway.getAllPositions().stream()
                .filter(p -> accountIds.contains(p.accountId()))
                .toList();

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

        // Group stop orders by position key
        Map<PositionKey, List<Order>> ordersByPosition = stopOrders.stream()
                .filter(o -> o.conid() != null)
                .collect(Collectors.groupingBy(o -> new PositionKey(o.conid(), o.accountId())));

        for (Map.Entry<PositionKey, List<Order>> entry : ordersByPosition.entrySet()) {
            PositionKey key = entry.getKey();
            List<Order> orders = entry.getValue();
            Position position = positionsByKey.get(key);

            if (position == null) continue;

            // Sum up quantities from all stop orders
            BigDecimal totalStopQuantity = BigDecimal.ZERO;
            BigDecimal weightedStopPriceSum = BigDecimal.ZERO;

            for (Order order : orders) {
                BigDecimal stopPrice = order.stopPrice();
                if (stopPrice == null) continue;

                BigDecimal quantity = order.remainingQuantity() != null
                        ? order.remainingQuantity()
                        : (order.quantity() != null ? order.quantity() : BigDecimal.ZERO);
                quantity = quantity.abs();

                totalStopQuantity = totalStopQuantity.add(quantity);
                weightedStopPriceSum = weightedStopPriceSum.add(stopPrice.multiply(quantity));
            }

            if (totalStopQuantity.compareTo(BigDecimal.ZERO) == 0) continue;

            protectedPositions.add(key);

            // Calculate weighted average stop price
            BigDecimal avgStopPrice = weightedStopPriceSum.divide(totalStopQuantity, CURRENCY_SCALE, RoundingMode.HALF_UP);
            String ticker = orders.getFirst().ticker() != null ? orders.getFirst().ticker() : position.ticker();

            addPositionRisk(accumulator, position, avgStopPrice, totalStopQuantity, ticker, true);
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

    /**
     * Calculate unsecured profit per share based on current price vs stop price.
     * This is the profit between current price and stop loss that hasn't been locked in yet.
     * Positive = additional unrealized profit, Negative = position is underwater vs stop.
     */
    private BigDecimal calculateUnsecuredProfitPrice(BigDecimal positionSize, BigDecimal currentPrice, BigDecimal stopPrice) {
        if (positionSize.compareTo(BigDecimal.ZERO) > 0) {
            // Long position: unsecured profit = currentPrice - stopPrice
            return currentPrice.subtract(stopPrice);
        } else {
            // Short position: unsecured profit = stopPrice - currentPrice
            return stopPrice.subtract(currentPrice);
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
        // Calculate lockedProfit (profit/loss if stop triggers)
        BigDecimal lockedProfitPrice = calculateLockedProfitPrice(position.quantity(), position.avgPrice(), stopPrice);
        BigDecimal lockedProfitAmount = lockedProfitPrice.multiply(quantity).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);

        // Calculate atRiskProfit (profit to lock in or additional loss exposure)
        BigDecimal atRiskProfitPrice = calculateAtRiskProfitPrice(position.quantity(), position.avgPrice(), position.marketPrice(), stopPrice);
        BigDecimal atRiskProfitAmount = atRiskProfitPrice.multiply(quantity).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);

        BigDecimal positionValue = position.quantity().abs().multiply(position.marketPrice()).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);
        String currency = position.currency();

        if (hasStopLoss) {
            accumulator.addProfitWithStopLoss(lockedProfitAmount, currency);
        } else {
            accumulator.addProfitWithoutStopLoss(lockedProfitAmount, currency);
        }

        accumulator.addRisk(new PositionRisk(
                position.accountId(),
                ticker,
                position.quantity().setScale(SIZE_SCALE, RoundingMode.HALF_UP),
                position.avgPrice().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                position.marketPrice().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                stopPrice.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                quantity.setScale(SIZE_SCALE, RoundingMode.HALF_UP),
                lockedProfitAmount,
                atRiskProfitAmount,
                positionValue,
                currency,
                currencyService.convertToBase(lockedProfitAmount, currency).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                currencyService.convertToBase(atRiskProfitAmount, currency).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                currencyService.convertToBase(positionValue, currency).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                currencyService.getBaseCurrency(),
                hasStopLoss,
                null  // portfolioPercentage calculated in RiskAccumulator.toReport()
        ));
    }

    /**
     * Calculate locked profit per share based on stop price vs average price.
     * Positive = profit locked in, Negative = loss locked in.
     */
    private BigDecimal calculateLockedProfitPrice(BigDecimal positionSize, BigDecimal avgPrice, BigDecimal stopPrice) {
        if (positionSize.compareTo(BigDecimal.ZERO) > 0) {
            // Long position: profit = stopPrice - avgPrice
            return stopPrice.subtract(avgPrice);
        } else {
            // Short position: profit = avgPrice - stopPrice
            return avgPrice.subtract(stopPrice);
        }
    }

    /**
     * Calculate at-risk profit per share based on current price vs stop price.
     * <p>
     * When position is in profit (currentPrice > avgPrice for longs):
     *   Returns {@code currentPrice - stopPrice} (positive = profit that could be locked in).
     * <p>
     * When position is underwater (currentPrice <= avgPrice for longs):
     *   Returns {@code -(currentPrice - stopPrice)} (negative = additional loss before stop triggers).
     * <p>
     * This makes the sign intuitive: positive = good (profit to capture), negative = bad (more loss exposure).
     */
    private BigDecimal calculateAtRiskProfitPrice(BigDecimal positionSize, BigDecimal avgPrice, BigDecimal currentPrice, BigDecimal stopPrice) {
        if (positionSize.compareTo(BigDecimal.ZERO) > 0) {
            // Long position
            BigDecimal distanceToStop = currentPrice.subtract(stopPrice);
            if (currentPrice.compareTo(avgPrice) > 0) {
                // In profit: positive = profit that could be locked in
                return distanceToStop;
            } else {
                // Underwater: negate to show as negative (additional loss exposure)
                return distanceToStop.negate();
            }
        } else {
            // Short position
            BigDecimal distanceToStop = stopPrice.subtract(currentPrice);
            if (currentPrice.compareTo(avgPrice) < 0) {
                // In profit: positive = profit that could be locked in
                return distanceToStop;
            } else {
                // Underwater: negate to show as negative (additional loss exposure)
                return distanceToStop.negate();
            }
        }
    }

    /**
     * Calculate profit per share based on stop price vs average price.
     * Positive = profit locked in, Negative = potential loss exposure.
     */
    private BigDecimal calculateSecuredProfitPrice(BigDecimal positionSize, BigDecimal avgPrice, BigDecimal stopPrice) {
        if (positionSize.compareTo(BigDecimal.ZERO) > 0) {
            // Long position: profit = stopPrice - avgPrice
            return stopPrice.subtract(avgPrice);
        } else {
            // Short position: profit = avgPrice - stopPrice
            return avgPrice.subtract(stopPrice);
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