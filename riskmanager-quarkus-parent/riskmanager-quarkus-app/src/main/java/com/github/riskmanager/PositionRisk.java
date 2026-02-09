package com.github.riskmanager;

import java.math.BigDecimal;

/**
 * Represents the risk analysis for a single position.
 *
 * <h2>Key Concepts</h2>
 * <ul>
 *   <li><b>Locked Profit</b>: The profit/loss that will be realized if the stop loss triggers.
 *       Calculated as: {@code stopPrice - avgPrice} (for long positions).
 *       Positive = profit locked in, Negative = loss locked in.</li>
 *   <li><b>At-Risk Profit</b>: The distance between current price and stop price.
 *       Calculated as: {@code currentPrice - stopPrice} (for long positions).
 *       Positive = unrealized profit above stop (could be locked in by moving stop up).
 *       Negative = additional loss exposure before stop triggers (position underwater).</li>
 * </ul>
 *
 * <h2>Examples (Long Position, 100 shares)</h2>
 *
 * <h3>Example 1: Profitable position with profit-protecting stop (entry < stop < market)</h3>
 * <pre>
 *   Entry (avgPrice):   $100
 *   Current (market):   $150
 *   Stop:               $120
 *
 *   lockedProfit  = ($120 - $100) × 100 = +$2,000  (profit guaranteed if stop triggers)
 *   atRiskProfit  = ($150 - $120) × 100 = +$3,000  (profit above stop that could be locked in)
 *
 *   Interpretation: $2,000 is locked in. Consider moving stop up to lock in more of that $3,000.
 * </pre>
 *
 * <h3>Example 2: Profitable position with stop below entry (stop < entry < market)</h3>
 * <pre>
 *   Entry (avgPrice):   $100
 *   Current (market):   $150
 *   Stop:               $90
 *
 *   lockedProfit  = ($90 - $100)  × 100 = -$1,000  (loss if stop triggers!)
 *   atRiskProfit  = ($150 - $90)  × 100 = +$6,000  (huge gap - move stop up!)
 *
 *   Interpretation: Despite being in profit, stop doesn't protect it. Move stop above entry!
 * </pre>
 *
 * <h3>Example 3: Underwater position (entry > market > stop)</h3>
 * <pre>
 *   Entry (avgPrice):   $100
 *   Current (market):   $90
 *   Stop:               $80
 *
 *   lockedProfit  = ($80 - $100) × 100 = -$2,000  (loss if stop triggers)
 *   atRiskProfit  = ($90 - $80)  × 100 = -$1,000  (could lose $1,000 more before stop)
 *
 *   Interpretation: Position is underwater. You could lose another $1,000 before stop triggers.
 * </pre>
 *
 * @param accountId          The broker account ID
 * @param ticker             The stock/instrument ticker symbol
 * @param positionSize       Total position size (negative for short positions)
 * @param avgPrice           Average entry price
 * @param currentPrice       Current market price
 * @param stopPrice          Stop loss price (actual or assumed)
 * @param orderQuantity      Quantity covered by stop order
 * @param lockedProfit       Profit/loss if stop triggers (native currency)
 * @param atRiskProfit       Distance to stop: positive = profit to lock in, negative = additional loss exposure (native currency)
 * @param positionValue      Current market value of position (native currency)
 * @param currency           Native currency of the position
 * @param lockedProfitBase   Locked profit converted to base currency
 * @param atRiskProfitBase   At-risk profit converted to base currency
 * @param positionValueBase  Position value converted to base currency
 * @param baseCurrency       The base currency for consolidated reporting
 * @param hasStopLoss        True if position has an actual stop loss order
 * @param portfolioPercentage Percentage of total portfolio value
 */
public record PositionRisk(
        String accountId,
        String ticker,
        BigDecimal positionSize,
        BigDecimal avgPrice,
        BigDecimal currentPrice,
        BigDecimal stopPrice,
        BigDecimal orderQuantity,
        BigDecimal lockedProfit,
        BigDecimal atRiskProfit,
        BigDecimal positionValue,
        String currency,
        BigDecimal lockedProfitBase,
        BigDecimal atRiskProfitBase,
        BigDecimal positionValueBase,
        String baseCurrency,
        boolean hasStopLoss,
        BigDecimal portfolioPercentage
) {
}