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
        BigDecimal profit,
        BigDecimal positionValue,
        String currency,
        BigDecimal profitBase,
        BigDecimal positionValueBase,
        String baseCurrency,
        boolean hasStopLoss,
        BigDecimal portfolioPercentage
) {
}