package com.github.riskmanager;

import java.math.BigDecimal;

public record StopLossResult(
        String accountId,
        String ticker,
        Integer conid,
        BigDecimal stopPrice,
        BigDecimal quantity,
        boolean success,
        String message
) {
}
