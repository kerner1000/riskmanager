package com.github.riskmanager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class RiskAccumulator {
    private final CurrencyConversionService currencyService;
    private BigDecimal protectedProfitBase = BigDecimal.ZERO;
    private BigDecimal unprotectedProfitBase = BigDecimal.ZERO;
    private final List<PositionRisk> risks = new ArrayList<>();

    RiskAccumulator(CurrencyConversionService currencyService) {
        this.currencyService = currencyService;
    }

    void addProtectedProfit(BigDecimal profit, String currency) {
        protectedProfitBase = protectedProfitBase.add(currencyService.convertToBase(profit, currency));
    }

    void addUnprotectedProfit(BigDecimal profit, String currency) {
        unprotectedProfitBase = unprotectedProfitBase.add(currencyService.convertToBase(profit, currency));
    }

    void addRisk(PositionRisk risk) {
        risks.add(risk);
    }

    RiskReport toReport(BigDecimal unprotectedLossPercentage) {
        // Calculate total portfolio value in base currency
        BigDecimal totalPortfolioValue = risks.stream()
                .map(PositionRisk::positionValueBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // Create new risks with portfolio percentage
        List<PositionRisk> risksWithPercentage = risks.stream()
                .map(r -> new PositionRisk(
                        r.accountId(),
                        r.ticker(),
                        r.positionSize(),
                        r.avgPrice(),
                        r.currentPrice(),
                        r.stopPrice(),
                        r.orderQuantity(),
                        r.securedProfit(),
                        r.positionValue(),
                        r.currency(),
                        r.securedProfitBase(),
                        r.positionValueBase(),
                        r.baseCurrency(),
                        r.hasStopLoss(),
                        totalPortfolioValue.compareTo(BigDecimal.ZERO) > 0
                                ? r.positionValueBase()
                                .divide(totalPortfolioValue, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                : BigDecimal.ZERO
                ))
                .sorted(Comparator.comparing(PositionRisk::securedProfit).reversed())
                .toList();

        return new RiskReport(
                protectedProfitBase.add(unprotectedProfitBase),
                protectedProfitBase,
                unprotectedProfitBase,
                currencyService.getBaseCurrency(),
                unprotectedLossPercentage,
                risksWithPercentage
        );
    }
}