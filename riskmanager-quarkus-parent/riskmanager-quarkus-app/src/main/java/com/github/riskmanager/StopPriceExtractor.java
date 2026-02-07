package com.github.riskmanager;

import com.github.riskmanager.ib.model.IserverAccountOrdersGet200ResponseOrdersInner;
import io.quarkus.logging.Log;

import java.math.BigDecimal;
import java.util.regex.Pattern;


public class StopPriceExtractor {
    private static final Pattern STOP_PRICE_PATTERN =
            Pattern.compile("(?i)stop\\s+([\\d,]+\\.?\\d*)");

    public BigDecimal extract(IserverAccountOrdersGet200ResponseOrdersInner order) {
        if (order.getPrice() != null) {
            return order.getPrice();
        }
        return parseFromDescription(order.getOrderDesc());
    }

    private BigDecimal parseFromDescription(String orderDesc) {
        if (orderDesc != null && orderDesc.toLowerCase().contains("stop")) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    "(?i)stop\\s+([\\d,]+\\.?\\d*)"
            );
            java.util.regex.Matcher matcher = pattern.matcher(orderDesc);
            if (matcher.find()) {
                try {
                    String priceStr = matcher.group(1).replace(",", "");
                    return
                            new BigDecimal(priceStr);
                } catch (NumberFormatException e) {
                    Log.warnf("Could not parse stop price from orderDesc: %s", orderDesc);
                }
            }
        }
        return null;
    }
}
