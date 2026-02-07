package com.github.riskmanager;

import java.math.BigDecimal;

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
) {
}
