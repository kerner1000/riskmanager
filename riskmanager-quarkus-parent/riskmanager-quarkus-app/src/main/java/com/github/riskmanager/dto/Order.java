
package com.github.riskmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Order(
        Long orderId,
        Long conid,
        String acct,
        String account,
        String ticker,
        String orderType,
        @JsonProperty("order_type") String orderTypeAlt,  // Alternative field name
        String side,
        String price,
        String auxPrice,
        @JsonProperty("stop_price") String stopPrice,
        BigDecimal totalSize,
        BigDecimal remainingQuantity,
        String status,
        @JsonProperty("order_status") String orderStatus  // Alternative field name
) {
    public BigDecimal getEffectiveStopPrice() {
        String priceStr = stopPrice != null && !stopPrice.isEmpty() ? stopPrice : auxPrice;
        if (priceStr == null || priceStr.isEmpty()) {
            return null;
        }
        return new BigDecimal(priceStr);
    }

    public String getEffectiveOrderType() {
        return orderType != null ? orderType : orderTypeAlt;
    }

    public String getEffectiveStatus() {
        return status != null ? status : orderStatus;
    }

    public String getEffectiveAccount() {
        // Prefer 'acct' as it's typically the actual owning account in IB's API
        return acct != null ? acct : account;
    }
}