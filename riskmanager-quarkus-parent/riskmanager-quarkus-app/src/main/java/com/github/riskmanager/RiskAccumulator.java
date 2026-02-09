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
                .map(r -> PositionRisk.builder()
                        .accountId(r.accountId())
                        .ticker(r.ticker())
                        .positionSize(r.positionSize())
                        .avgPrice(r.avgPrice())
                        .currentPrice(r.currentPrice())
                        .stopPrice(r.stopPrice())
                        .orderQuantity(r.orderQuantity())
                        .lockedProfit(r.lockedProfit().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP))
                        .atRiskProfit(r.atRiskProfit().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP))
                        .positionValue(r.positionValue().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP))
                        .currency(r.currency())
                        .lockedProfitBase(r.lockedProfitBase().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP))
                        .atRiskProfitBase(r.atRiskProfitBase().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP))
                        .positionValueBase(r.positionValueBase().setScale(CURRENCY_SCALE, RoundingMode.HALF_UP))
                        .baseCurrency(r.baseCurrency())
                        .hasStopLoss(r.hasStopLoss())
                        .portfolioPercentage(totalPortfolioValue.compareTo(BigDecimal.ZERO) > 0
                                ? r.positionValueBase()
                                .divide(totalPortfolioValue, 4, RoundingMode.HALF_UP)
                                .multiply(new BigDecimal("100"))
                                .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO)
                        .build())
                .sorted(Comparator.comparing(PositionRisk::lockedProfitBase))
                .toList();

        return RiskReport.builder()
                .totalPositionValue(totalPortfolioValue)
                .worstCaseProfit(worstCaseProfitWithStopLossBase.add(worstCaseProfitWithoutStopLossBase)
                        .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP))
                .worstCaseProfitWithStopLoss(worstCaseProfitWithStopLossBase
                        .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP))
                .worstCaseProfitWithoutStopLoss(worstCaseProfitWithoutStopLossBase
                        .setScale(CURRENCY_SCALE, RoundingMode.HALF_UP))
                .totalAtRiskProfit(totalAtRiskProfitBase)
                .currency(currencyService.getBaseCurrency())
                .unprotectedLossPercentageUsed(unprotectedLossPercentage)
                .positionRisks(risksWithPercentage)
                .build();
    }
}