package com.github.riskmanager.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Position(
        Long conid,
        String acctId,            // Account ID
        String contractDesc,
        BigDecimal position,
        BigDecimal mktPrice,
        BigDecimal mktValue,
        BigDecimal avgCost,
        BigDecimal avgPrice,
        BigDecimal unrealizedPnl
) {}