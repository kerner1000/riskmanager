package com.github.riskmanager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class RiskAccumulator {
    private final CurrencyConversionService currencyService;
    private BigDecimal protectedLossBase = BigDecimal.ZERO;
    private BigDecimal unprotectedLossBase = BigDecimal.ZERO;
    private final List<PositionRisk> risks = new ArrayList<>();

    RiskAccumulator(CurrencyConversionService currencyService) {
        this.currencyService = currencyService;
    }

    void addProtectedLoss(BigDecimal loss, String currency) {
        protectedLossBase = protectedLossBase.add(currencyService.convertToBase(loss, currency));
    }

    void addUnprotectedLoss(BigDecimal loss, String currency) {
        unprotectedLossBase = unprotectedLossBase.add(currencyService.convertToBase(loss, currency));
    }

    void addRisk(PositionRisk risk) {
        risks.add(risk);
    }

    RiskReport toReport(BigDecimal unprotectedLossPercentage) {
        List<PositionRisk> sortedRisks = risks.stream()
                .sorted(Comparator.comparing(PositionRisk::potentialLossBase).reversed())
                .toList();

        return new RiskReport(
                protectedLossBase.add(unprotectedLossBase),
                protectedLossBase,
                unprotectedLossBase,
                currencyService.getBaseCurrency(),
                unprotectedLossPercentage,
                sortedRisks
        );
    }
}
