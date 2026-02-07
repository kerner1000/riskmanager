package com.github.riskmanager;

import java.math.BigDecimal;
import java.util.List;

public record RiskReport(
        BigDecimal totalPotentialLoss,
        BigDecimal protectedLoss,
        BigDecimal unprotectedLoss,
        String currency,
        BigDecimal unprotectedLossPercentageUsed,
        List<PositionRisk> positionRisks
) {
}
