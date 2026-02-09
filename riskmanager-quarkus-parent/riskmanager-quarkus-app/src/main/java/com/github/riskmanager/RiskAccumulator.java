package com.github.riskmanager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class RiskAccumulator {

    private static final int CURRENCY_SCALE = 2;

    private final CurrencyConversionService currencyService;
    private BigDecimal worstCaseProfitWithStopLossBase = BigDecimal.ZERO;
    private BigDecimal worstCaseProfitWithoutStopLossBase = BigDecimal.ZERO;
    private final List<PositionRisk> risks = new ArrayList<>();

    RiskAccumulator(CurrencyConversionService currencyService) {
        this.currencyService = currencyService;
    }

    void addProfitWithStopLoss(BigDecimal profit, String currency) {
        worstCaseProfitWithStopLossBase = worstCaseProfitWithStopLossBase.add(currencyService.convertToBase(profit, currency));
    }

    void addProfitWithoutStopLoss(BigDecimal profit, String currency) {
        worstCaseProfitWithoutStopLossBase = worstCaseProfitWithoutStopLossBase.add(currencyService.convertToBase(profit, currency));
    }

    void addRisk(PositionRisk risk) {
        risks.add(risk);
    }

    RiskReport toReport(BigDecimal unprotectedLossPercentage) {
        // Calculate total portfolio value in base currency
        BigDecimal totalPortfolioValue = risks.stream()
                .map(PositionRisk::positionValueBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);

        // Calculate total at-risk profit in base currency
        BigDecimal totalAtRiskProfitBase = risks.stream()
                .map(PositionRisk::atRiskProfitBase)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP);

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
                        r.lockedProfit().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                        r.atRiskProfit().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                        r.positionValue().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                        r.currency(),
                        r.lockedProfitBase().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                        r.atRiskProfitBase().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                        r.positionValueBase().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                        r.baseCurrency(),
                        r.hasStopLoss(),
                        totalPortfolioValue.compareTo(BigDecimal.ZERO) > 0
                                ? r.positionValueBase()
                                .divide(totalPortfolioValue, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO
                ))
                .sorted(Comparator.comparing(PositionRisk::lockedProfit).reversed())
                .toList();

        return new RiskReport(
                worstCaseProfitWithStopLossBase.add(worstCaseProfitWithoutStopLossBase).setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                worstCaseProfitWithStopLossBase.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                worstCaseProfitWithoutStopLossBase.setScale(CURRENCY_SCALE, RoundingMode.HALF_UP),
                totalAtRiskProfitBase,
                totalPortfolioValue,
                currencyService.getBaseCurrency(),
                unprotectedLossPercentage,
                risksWithPercentage
        );
    }
}