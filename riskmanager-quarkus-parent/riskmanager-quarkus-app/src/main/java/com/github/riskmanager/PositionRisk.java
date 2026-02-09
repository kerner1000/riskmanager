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
        BigDecimal securedProfit,
        BigDecimal positionValue,
        String currency,
        BigDecimal securedProfitBase,
        BigDecimal positionValueBase,
        String baseCurrency,
        boolean hasStopLoss,
        BigDecimal portfolioPercentage
) {
}