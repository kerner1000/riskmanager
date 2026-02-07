package com.github.riskmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceOrderResponse(
        String id,
        String orderId,
        String order_status,
        List<String> message,        // Changed from List<MessageInfo> to List<String>
        Boolean replyId,             // Alternative confirmation indicator
        String error
) {
    public boolean needsConfirmation() {
        return id != null && !id.isEmpty();
    }
}