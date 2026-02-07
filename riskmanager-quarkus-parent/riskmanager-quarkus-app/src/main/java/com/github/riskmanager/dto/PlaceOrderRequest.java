package com.github.riskmanager.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record PlaceOrderRequest(
        @JsonProperty("conid") Long conid,
        @JsonProperty("orderType") String orderType,
        @JsonProperty("side") String side,
        @JsonProperty("quantity") BigDecimal quantity,
        @JsonProperty("price") BigDecimal price,
        @JsonProperty("tif") String timeInForce
) {
    public static PlaceOrderRequest stopLoss(Long conid, BigDecimal quantity, BigDecimal stopPrice, boolean isLong) {
        return new PlaceOrderRequest(
                conid,
                "STP",                           // Stop order
                isLong ? "SELL" : "BUY",         // Sell to close long, buy to close short
                quantity,
                stopPrice,
                "GTC"                            // Good 'til cancelled
        );
    }
}