package com.github.riskmanager.broker;

import java.math.BigDecimal;
import java.util.List;

/**
 * Abstraction for broker API communication.
 * Implementations: IBClientPortalGateway (REST), IBTwsGateway (Socket - future)
 */
public interface BrokerGateway {

    // ==================== Connection / Health ====================

    ConnectionStatus getConnectionStatus();

    boolean keepAlive();

    // ==================== Account ====================

    List<String> getConfiguredAccounts();

    void switchAccount(String accountId) throws BrokerException;

    // ==================== Positions ====================

    List<Position> getPositions(String accountId) throws BrokerException;

    List<Position> getAllPositions() throws BrokerException;

    // ==================== Orders ====================

    List<Order> getOrders(String accountId) throws BrokerException;

    List<Order> getAllOrders() throws BrokerException;

    List<Order> getStopOrders(String accountId) throws BrokerException;

    List<Order> getAllStopOrders() throws BrokerException;

    List<Order> getStopOrdersForConid(String accountId, Integer conid) throws BrokerException;

    OrderResult placeStopLossOrder(StopLossOrderRequest request) throws BrokerException;

    // ==================== DTOs ====================

    record ConnectionStatus(
            boolean reachable,
            boolean authenticated,
            boolean connected,
            boolean competing,
            String message
    ) {}

    record Position(
            String accountId,
            Integer conid,
            String ticker,
            BigDecimal quantity,
            BigDecimal avgPrice,
            BigDecimal marketPrice,
            String currency
    ) {
        public boolean isLong() {
            return quantity != null && quantity.compareTo(BigDecimal.ZERO) > 0;
        }

        public boolean isZero() {
            return quantity == null || quantity.compareTo(BigDecimal.ZERO) == 0;
        }
    }

    record Order(
            String orderId,
            String accountId,
            Integer conid,
            String ticker,
            String orderType,
            String side,
            BigDecimal price,
            BigDecimal stopPrice,
            BigDecimal quantity,
            BigDecimal remainingQuantity,
            String status
    ) {}

    record StopLossOrderRequest(
            String accountId,
            Integer conid,
            BigDecimal stopPrice,
            BigDecimal quantity,
            boolean isLong
    ) {}

    record OrderResult(
            boolean success,
            String orderId,
            String message
    ) {}
}