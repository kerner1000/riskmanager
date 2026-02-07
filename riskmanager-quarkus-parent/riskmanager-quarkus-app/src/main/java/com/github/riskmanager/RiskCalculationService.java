
package com.github.riskmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.riskmanager.ib.model.*;
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

    @Inject
    IBDataService dataService;

    @Inject
    CurrencyConversionService currencyService;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "risk.unprotected-loss-percentage", defaultValue = "50")
    BigDecimal unprotectedLossPercentage;

    // Composite key for position uniqueness across accounts
    private record PositionKey(Integer conid, String accountId) {}

    // ==================== Risk Calculation ====================

    public RiskReport calculateWorstCaseScenarioForAccounts(List<String> accountIds) throws Exception {
        List<PositionInner> allPositions = dataService.fetchAllPositions(accountIds);
        List<IserverAccountOrdersGet200ResponseOrdersInner> stopOrders = dataService.fetchAllStopOrders();

        Map<PositionKey, PositionInner> positionsByKey = buildPositionMap(allPositions);

        RiskAccumulator accumulator = new RiskAccumulator(currencyService);
        Set<PositionKey> protectedPositions = processProtectedPositions(stopOrders, positionsByKey, accumulator);
        processUnprotectedPositions(allPositions, protectedPositions, accumulator);

        return accumulator.toReport(unprotectedLossPercentage);
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

            String ticker = order.getTicker() != null ? order.getTicker() : position.getContractDesc();
            addPositionRisk(accumulator, position, stopPrice, quantity.abs(), ticker, true);
        }
        return protectedPositions;
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
            addPositionRisk(accumulator, position, assumedStopPrice, quantity, position.getContractDesc(), false);
        }
    }

    private void addPositionRisk(
            RiskAccumulator accumulator,
            PositionInner position,
            BigDecimal stopPrice,
            BigDecimal quantity,
            String ticker,
            boolean hasStopLoss
    ) {
        BigDecimal potentialLoss = calculateLossPerShare(position.getPosition(), position.getAvgPrice(), stopPrice)
                .multiply(quantity);
        BigDecimal positionValue = position.getPosition().abs().multiply(position.getMktPrice());
        String currency = position.getCurrency();

        if (hasStopLoss) {
            accumulator.addProtectedLoss(potentialLoss, currency);
        } else {
            accumulator.addUnprotectedLoss(potentialLoss, currency);
        }

        accumulator.addRisk(new PositionRisk(
                position.getAcctId(),
                ticker,
                position.getPosition(),
                position.getAvgPrice(),
                position.getMktPrice(),
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

    private BigDecimal extractStopPrice(IserverAccountOrdersGet200ResponseOrdersInner order) {
        if (order.getPrice() != null) {
            return order.getPrice();
        }

        String orderDesc = order.getOrderDesc();
        if (orderDesc != null && orderDesc.toLowerCase().contains("stop")) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?i)stop\\s+([\\d,]+\\.?\\d*)"
            );
            java.util.regex.Matcher matcher = pattern.matcher(orderDesc);
            if (matcher.find()) {
                try {
                    String priceStr = matcher.group(1).replace(",", "");
                    return new BigDecimal(priceStr);
                } catch (NumberFormatException e) {
                    Log.warnf("Could not parse stop price from orderDesc: %s", orderDesc);
                }
            }
        }
        return null;
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

    // ==================== Stop Loss Creation ====================

    public List<StopLossResult> createMissingStopLosses(String accountId, BigDecimal lossPercentage) throws Exception {
        List<PositionInner> positions = dataService.fetchPositionsForAccount(accountId);
        List<IserverAccountOrdersGet200ResponseOrdersInner> stopOrders = dataService.fetchStopOrdersForAccountWithRefresh(accountId);

        Set<Integer> protectedConids = stopOrders.stream()
                .map(o -> o.getConid() != null ? o.getConid().intValue() : null)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<StopLossResult> results = new ArrayList<>();

        for (PositionInner position : positions) {
            if (protectedConids.contains(position.getConid()) || position.getPosition().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            results.add(createStopLossOrder(accountId, position, lossPercentage));
        }

        return results;
    }

    public StopLossResult createStopLossForPosition(String accountId, Integer conid, BigDecimal lossPercentage) throws Exception {
        List<PositionInner> positions = dataService.fetchPositionsForAccount(accountId);

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
        List<PositionInner> positions = dataService.fetchPositionsForAccount(accountId);

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

        try {
            List<IserverAccountOrdersGet200ResponseOrdersInner> existingStops =
                    dataService.fetchStopOrdersForConid(accountId, position.getConid());
            if (!existingStops.isEmpty()) {
                IserverAccountOrdersGet200ResponseOrdersInner existing = existingStops.getFirst();
                return new StopLossResult(accountId, position.getContractDesc(), position.getConid(),
                        existing.getPrice(),
                        existing.getRemainingQuantity() != null ? existing.getRemainingQuantity() : BigDecimal.ZERO,
                        false, "Stop loss already exists at price " + existing.getPrice());
            }
        } catch (Exception e) {
            Log.warnf("Could not check for existing stop orders: %s", e.getMessage());
        }

        try {
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

            List<IserverAccountAccountIdOrderPost200ResponseInner> responses = dataService.placeOrder(accountId, request);

            if (responses != null && !responses.isEmpty()) {
                IserverAccountAccountIdOrderPost200ResponseInner first = responses.getFirst();
                if (first.getId() != null && first.getMessage() != null && !first.getMessage().isEmpty()) {
                    dataService.confirmOrder(first.getId());
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
        List<PositionInner> positions = dataService.fetchPositionsForAccount(accountId);

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

        List<IserverAccountAccountIdOrderPost200ResponseInner> responses = dataService.placeOrder(accountId, request);
        debug.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(responses));

        if (responses != null && !responses.isEmpty()) {
            IserverAccountAccountIdOrderPost200ResponseInner first = responses.getFirst();
            if (first.getId() != null && first.getMessage() != null && !first.getMessage().isEmpty()) {
                debug.append("\n\n=== Response 2 (Confirm) ===\n");
                var confirmResponse = dataService.confirmOrder(first.getId());
                debug.append(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(confirmResponse));
            }
        }

        return debug.toString();
    }

    private BigDecimal calculateStopPrice(PositionInner position, BigDecimal lossPercentage) {
        BigDecimal multiplier = lossPercentage.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);

        if (position.getPosition().compareTo(BigDecimal.ZERO) > 0) {
            return position.getMktPrice().multiply(BigDecimal.ONE.subtract(multiplier))
                    .setScale(2, RoundingMode.DOWN);
        } else {
            return position.getMktPrice().multiply(BigDecimal.ONE.add(multiplier))
                    .setScale(2, RoundingMode.UP);
        }
    }

}