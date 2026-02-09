
package com.github.riskmanager;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Objects;

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
 * // ... rest of existing javadoc ...
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
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns whether the position is currently in profit.
     */
    @JsonProperty
    public boolean inProfit() {
        if (currentPrice == null || avgPrice == null || positionSize == null) {
            return false;
        }
        if (positionSize.compareTo(BigDecimal.ZERO) > 0) {
            return currentPrice.compareTo(avgPrice) > 0;
        } else {
            return currentPrice.compareTo(avgPrice) < 0;
        }
    }

    public static class Builder {
        private String accountId;
        private String ticker;
        private BigDecimal positionSize;
        private BigDecimal avgPrice;
        private BigDecimal currentPrice;
        private BigDecimal stopPrice;
        private BigDecimal orderQuantity;
        private BigDecimal lockedProfit;
        private BigDecimal atRiskProfit;
        private BigDecimal positionValue;
        private String currency;
        private BigDecimal lockedProfitBase;
        private BigDecimal atRiskProfitBase;
        private BigDecimal positionValueBase;
        private String baseCurrency;
        private boolean hasStopLoss;
        private BigDecimal portfolioPercentage;

        public Builder accountId(String value) { this.accountId = value; return this; }
        public Builder ticker(String value) { this.ticker = value; return this; }
        public Builder positionSize(BigDecimal value) { this.positionSize = value; return this; }
        public Builder avgPrice(BigDecimal value) { this.avgPrice = value; return this; }
        public Builder currentPrice(BigDecimal value) { this.currentPrice = value; return this; }
        public Builder stopPrice(BigDecimal value) { this.stopPrice = value; return this; }
        public Builder orderQuantity(BigDecimal value) { this.orderQuantity = value; return this; }
        public Builder lockedProfit(BigDecimal value) { this.lockedProfit = value; return this; }
        public Builder atRiskProfit(BigDecimal value) { this.atRiskProfit = value; return this; }
        public Builder positionValue(BigDecimal value) { this.positionValue = value; return this; }
        public Builder currency(String value) { this.currency = value; return this; }
        public Builder lockedProfitBase(BigDecimal value) { this.lockedProfitBase = value; return this; }
        public Builder atRiskProfitBase(BigDecimal value) { this.atRiskProfitBase = value; return this; }
        public Builder positionValueBase(BigDecimal value) { this.positionValueBase = value; return this; }
        public Builder baseCurrency(String value) { this.baseCurrency = value; return this; }
        public Builder hasStopLoss(boolean value) { this.hasStopLoss = value; return this; }
        public Builder portfolioPercentage(BigDecimal value) { this.portfolioPercentage = value; return this; }

        public PositionRisk build() {
            Objects.requireNonNull(accountId, "accountId is required");
            Objects.requireNonNull(ticker, "ticker is required");
            Objects.requireNonNull(positionSize, "positionSize is required");
            Objects.requireNonNull(avgPrice, "avgPrice is required");
            Objects.requireNonNull(currentPrice, "currentPrice is required");
            Objects.requireNonNull(stopPrice, "stopPrice is required");
            Objects.requireNonNull(orderQuantity, "orderQuantity is required");
            Objects.requireNonNull(lockedProfit, "lockedProfit is required");
            Objects.requireNonNull(atRiskProfit, "atRiskProfit is required");
            Objects.requireNonNull(positionValue, "positionValue is required");
            Objects.requireNonNull(currency, "currency is required");
            Objects.requireNonNull(lockedProfitBase, "lockedProfitBase is required");
            Objects.requireNonNull(atRiskProfitBase, "atRiskProfitBase is required");
            Objects.requireNonNull(positionValueBase, "positionValueBase is required");
            Objects.requireNonNull(baseCurrency, "baseCurrency is required");
            return new PositionRisk(
                    accountId, ticker, positionSize, avgPrice, currentPrice,
                    stopPrice, orderQuantity, lockedProfit, atRiskProfit,
                    positionValue, currency, lockedProfitBase, atRiskProfitBase,
                    positionValueBase, baseCurrency, hasStopLoss, portfolioPercentage
            );
        }
    }
}