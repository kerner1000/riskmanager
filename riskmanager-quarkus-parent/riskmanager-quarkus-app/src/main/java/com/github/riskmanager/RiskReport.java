package com.github.riskmanager;

import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregated risk report for a portfolio of positions.
 *
 * <h2>Summary Fields</h2>
 * <ul>
 *   <li><b>worstCaseProfit</b>: Total profit/loss if ALL stop losses trigger.
 *       Sum of all {@link PositionRisk#lockedProfit()} values.</li>
 *   <li><b>worstCaseProfitWithStopLoss</b>: Portion of worst case from positions WITH actual stop orders.</li>
 *   <li><b>worstCaseProfitWithoutStopLoss</b>: Portion from positions WITHOUT stop orders
 *       (uses assumed stop at configured loss percentage).</li>
 *   <li><b>totalAtRiskProfit</b>: Sum of all {@link PositionRisk#atRiskProfit()} values.
 *       Positive contributions = unrealized profit that could be locked in.
 *       Negative contributions = additional loss exposure before stops trigger.</li>
 * </ul>
 *
 * <h2>Example Portfolio</h2>
 * <pre>
 * Position A: Entry $100, Market $150, Stop $120 (profit-protecting stop)
 *             lockedProfit = +$2,000,  atRiskProfit = +$3,000
 *
 * Position B: Entry $100, Market $150, Stop $90 (stop below entry!)
 *             lockedProfit = -$1,000,  atRiskProfit = +$6,000
 *
 * Position C: Entry $100, Market $90, Stop $80 (underwater)
 *             lockedProfit = -$2,000,  atRiskProfit = -$1,000
 *
 * Results:
 *   worstCaseProfit    = $2,000 - $1,000 - $2,000 = -$1,000
 *   totalAtRiskProfit  = $3,000 + $6,000 - $1,000 = +$8,000
 *
 * Interpretation:
 *   - If all stops trigger, net result is -$1,000 loss
 *   - $8,000 net profit could be locked in by adjusting stops
 *   - Position B is priority: $6,000 at risk with stop below entry!
 *   - Position C: underwater, could lose $1,000 more before stop
 * </pre>
 *
 * @param worstCaseProfit                Total locked profit if all stops trigger (base currency)
 * @param worstCaseProfitWithStopLoss    Locked profit from positions with actual stop orders (base currency)
 * @param worstCaseProfitWithoutStopLoss Locked profit from positions without stop orders (base currency)
 * @param totalAtRiskProfit              Net at-risk profit across all positions (base currency)
 * @param totalPositionValue             Total market value of all positions (base currency)
 * @param currency                       Base currency used for all aggregated values
 * @param unprotectedLossPercentageUsed  The assumed loss % used for positions without stops
 * @param positionRisks                  Individual position risk details
 */
public record RiskReport(
        BigDecimal worstCaseProfit,
        BigDecimal worstCaseProfitWithStopLoss,
        BigDecimal worstCaseProfitWithoutStopLoss,
        BigDecimal totalAtRiskProfit,
        BigDecimal totalPositionValue,
        String currency,
        BigDecimal unprotectedLossPercentageUsed,
        List<PositionRisk> positionRisks
) {
}