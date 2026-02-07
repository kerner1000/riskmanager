package com.github.riskmanager.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record SubmitOrderRequest(
        @JsonProperty("orders") List<PlaceOrderRequest> orders
) {
    public static SubmitOrderRequest single(PlaceOrderRequest order) {
        return new SubmitOrderRequest(List.of(order));
    }
}