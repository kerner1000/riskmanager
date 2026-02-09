package com.github.riskmanager;

import java.math.BigDecimal;
import java.util.List;




import java.math.BigDecimal;
import java.util.List;

public record RiskReport(
        BigDecimal totalProfit,
        BigDecimal protectedProfit,
        BigDecimal unprotectedProfit,
        String currency,
        BigDecimal unprotectedLossPercentageUsed,
        List<PositionRisk> positionRisks
) {
}