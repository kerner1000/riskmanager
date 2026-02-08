package com.github.riskmanager.broker;

import com.github.riskmanager.StopPriceExtractor;
import com.github.riskmanager.ib.api.*;
import com.github.riskmanager.ib.model.*;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class IBClientPortalGateway implements BrokerGateway {

    @RestClient
    @Inject
    PortfolioApi portfolioApi;

    @RestClient
    @Inject
    OrderApi orderApi;

    @RestClient
    @Inject
    AccountApi accountApi;

    @RestClient
    @Inject
    SessionApi sessionApi;

    @ConfigProperty(name = "risk.accounts")
    List<String> accounts;

    private final StopPriceExtractor stopPriceExtractor = new StopPriceExtractor();

    // ==================== Connection ====================

    @Override
    public ConnectionStatus getConnectionStatus() {
        try {
            AuthStatus authStatus = sessionApi.iserverAuthStatusPost();
            boolean authenticated = Boolean.TRUE.equals(authStatus.getAuthenticated());
            boolean connected = Boolean.TRUE.equals(authStatus.getConnected());
            boolean competing = Boolean.TRUE.equals(authStatus.getCompeting());
            String message = authenticated
                    ? "Session is authenticated and ready"
                    : (authStatus.getFail() != null ? authStatus.getFail() : "Not authenticated");

            return new ConnectionStatus(true, authenticated, connected, competing, message);
        } catch (Exception e) {
            return new ConnectionStatus(false, false, false, false,
                    "Cannot reach gateway: " + e.getMessage());
        }
    }

    @Override
    public boolean keepAlive() {
        try {
            sessionApi.ticklePost();
            return true;
        } catch (Exception e) {
            Log.warn("Failed to keep session alive: " + e.getMessage());
            return false;
        }
    }

    // ==================== Account ====================

    @Override
    public List<String> getConfiguredAccounts() {
        return accounts;
    }

    @Override
    public void switchAccount(String accountId) throws BrokerException {
        try {
            SetAccount setAccount = new SetAccount();
            setAccount.setAcctId(accountId);
            var response = accountApi.iserverAccountPost(setAccount);
            Log.debugf("Switched to account %s, success=%s", accountId, response.getSet());
            Thread.sleep(200); // API requires delay
        } catch (Exception e) {
            throw new BrokerException("Failed to switch account: " + e.getMessage(), e);
        }
    }

    // ==================== Positions ====================

    @Override
    public List<Position> getPositions(String accountId) throws BrokerException {
        try {
            List<PositionInner> rawPositions = portfolioApi.portfolioAccountIdPositionsPageIdGet(
                    accountId, "0", null, null, null, null);

            if (rawPositions == null) {
                return List.of();
            }

            return rawPositions.stream()
                    .map(this::mapPosition)
                    .toList();
        } catch (ApiException e) {
            throw new BrokerException("Failed to fetch positions: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Position> getAllPositions() throws BrokerException {
        List<Position> allPositions = new ArrayList<>();
        for (String accountId : accounts) {
            allPositions.addAll(getPositions(accountId));
        }
        return allPositions;
    }

    private Position mapPosition(PositionInner p) {
        return new Position(
                p.getAcctId(),
                p.getConid(),
                p.getContractDesc(),
                p.getPosition(),
                p.getAvgPrice(),
                p.getMktPrice(),
                p.getCurrency()
        );
    }

    // ==================== Orders ====================

    @Override
    public List<Order> getOrders(String accountId) throws BrokerException {
        try {
            switchAccount(accountId);
            orderApi.iserverAccountOrdersGet(null); // Force refresh
            Thread.sleep(200);

            IserverAccountOrdersGet200Response response = orderApi.iserverAccountOrdersGet(null);
            if (response == null || response.getOrders() == null) {
                return List.of();
            }

            return response.getOrders().stream()
                    .map(this::mapOrder)
                    .toList();
        } catch (BrokerException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokerException("Failed to fetch orders: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Order> getAllOrders() throws BrokerException {
        List<Order> allOrders = new ArrayList<>();
        for (String accountId : accounts) {
            allOrders.addAll(getOrders(accountId));
        }
        return allOrders;
    }

    @Override
    public List<Order> getStopOrders(String accountId) throws BrokerException {
        return getOrders(accountId).stream()
                .filter(this::isStopOrder)
                .filter(this::isActiveOrder)
                .toList();
    }

    @Override
    public List<Order> getAllStopOrders() throws BrokerException {
        Set<String> seenOrderIds = new HashSet<>();
        List<Order> stopOrders = new ArrayList<>();

        for (String accountId : accounts) {
            for (Order order : getStopOrders(accountId)) {
                if (order.orderId() != null && seenOrderIds.add(order.orderId())) {
                    stopOrders.add(order);
                }
            }
        }
        Log.debugf("Unique stop orders: %d", stopOrders.size());
        return stopOrders;
    }

    @Override
    public List<Order> getStopOrdersForConid(String accountId, Integer conid) throws BrokerException {
        return getStopOrders(accountId).stream()
                .filter(o -> conid.equals(o.conid()))
                .toList();
    }

    private Order mapOrder(IserverAccountOrdersGet200ResponseOrdersInner o) {
        return new Order(
                o.getOrderId(),
                o.getAcct(),
                o.getConid() != null ? o.getConid().intValue() : null,
                o.getTicker(),
                o.getOrderType(),
                o.getSide() != null ? o.getSide().value() : null,  // Convert enum to String
                o.getPrice(),
                stopPriceExtractor.extract(o),
                o.getFilledQuantity(),
                o.getRemainingQuantity(),
                o.getStatus()
        );
    }

    private boolean isStopOrder(Order order) {
        String type = order.orderType();
        return type != null && (type.toLowerCase().contains("stop") || type.equalsIgnoreCase("STP"));
    }

    private boolean isActiveOrder(Order order) {
        String status = order.status();
        Log.debugf("Stop Order: ticker=%s, acct=%s, conid=%s, status=%s",
                order.ticker(), order.accountId(), order.conid(), status);
        return status == null || (!status.equalsIgnoreCase("Cancelled") && !status.equalsIgnoreCase("Filled"));
    }

    // ==================== Order Placement ====================

    @Override
    public OrderResult placeStopLossOrder(StopLossOrderRequest request) throws BrokerException {
        try {
            OrderRequest orderRequest = new OrderRequest();
            orderRequest.setConid(request.conid());
            orderRequest.setOrderType("STP");
            orderRequest.setPrice(request.stopPrice());
            orderRequest.setQuantity(request.quantity());
            orderRequest.setSide(request.isLong() ? "SELL" : "BUY");
            orderRequest.setTif("GTC");

            IserverAccountAccountIdOrdersPostRequest apiRequest = new IserverAccountAccountIdOrdersPostRequest();
            apiRequest.setOrders(List.of(orderRequest));

            List<IserverAccountAccountIdOrderPost200ResponseInner> responses =
                    orderApi.iserverAccountAccountIdOrdersPost(request.accountId(), apiRequest);

            if (responses != null && !responses.isEmpty()) {
                var first = responses.getFirst();

                // Handle confirmation if needed
                if (first.getId() != null && first.getMessage() != null && !first.getMessage().isEmpty()) {
                    IserverReplyReplyidPostRequest confirmRequest = new IserverReplyReplyidPostRequest();
                    confirmRequest.setConfirmed(true);
                    orderApi.iserverReplyReplyidPost(first.getId(), confirmRequest);
                }

                // Note: This response only contains 'id' (reply ID), not the actual order ID
                // The order ID is returned after confirmation
                return new OrderResult(true, first.getId(), "Order placed successfully");
            }

            return new OrderResult(false, null, "No response from broker");
        } catch (ApiException e) {
            throw new BrokerException("Failed to place stop loss order: " + e.getMessage(), e);
        }
    }
}