
package com.github.riskmanager.broker;

import com.ib.client.*;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Alternative;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TWS API implementation using socket connection to IB Gateway or TWS.
 * <p>
 * To activate this implementation, add to application.yaml:
 * <pre>
 * quarkus:
 *   arc:
 *     selected-alternatives: com.github.riskmanager.broker.IBTwsGateway
 * </pre>
 */
@ApplicationScoped
@Alternative
public class IBTwsGateway implements BrokerGateway {

    @ConfigProperty(name = "tws.host", defaultValue = "127.0.0.1")
    String twsHost;

    @ConfigProperty(name = "tws.port", defaultValue = "4001")
    int twsPort;

    @ConfigProperty(name = "tws.client-id", defaultValue = "1")
    int clientId;

    @ConfigProperty(name = "risk.accounts")
    List<String> accounts;

    private EClientSocket client;
    private EReaderSignal signal;
    private TwsCallbackHandler callbackHandler;
    private final AtomicInteger requestIdCounter = new AtomicInteger(1000);

    // ==================== Lifecycle ====================

    void onStart(@Observes StartupEvent ev) {
        Log.info("Initializing TWS Gateway connection...");
        connect();
    }

    void onShutdown(@Observes ShutdownEvent ev) {
        Log.info("Shutting down TWS Gateway connection...");
        disconnect();
    }

    private synchronized void connect() {
        if (client != null && client.isConnected()) {
            return;
        }

        signal = new EJavaSignal();
        callbackHandler = new TwsCallbackHandler();
        client = new EClientSocket(callbackHandler, signal);

        client.eConnect(twsHost, twsPort, clientId);

        if (client.isConnected()) {
            // Start the reader thread
            EReader reader = new EReader(client, signal);
            reader.start();

            // Start message processing thread
            Thread messageThread = new Thread(() -> {
                while (client.isConnected()) {
                    signal.waitForSignal();
                    try {
                        reader.processMsgs();
                    } catch (Exception e) {
                        Log.error("Error processing TWS messages", e);
                    }
                }
            }, "TWS-MessageProcessor");
            messageThread.setDaemon(true);
            messageThread.start();

            Log.infof("✓ Connected to TWS at %s:%d with client ID %d", twsHost, twsPort, clientId);
        } else {
            Log.error("Failed to connect to TWS");
        }
    }

    private synchronized void disconnect() {
        if (client != null && client.isConnected()) {
            client.eDisconnect();
            Log.info("Disconnected from TWS");
        }
    }

    private void ensureConnected() throws BrokerException {
        if (client == null || !client.isConnected()) {
            connect();
            if (client == null || !client.isConnected()) {
                throw new BrokerException("Not connected to TWS");
            }
        }
    }

    private int nextRequestId() {
        return requestIdCounter.incrementAndGet();
    }

    // ==================== BrokerGateway Implementation ====================

    @Override
    public ConnectionStatus getConnectionStatus() {
        boolean connected = client != null && client.isConnected();
        return new ConnectionStatus(
                connected,
                connected, // TWS connection implies authentication
                connected,
                false,
                connected ? "Connected to TWS" : "Not connected to TWS"
        );
    }

    @Override
    public boolean keepAlive() {
        // TWS maintains persistent connection - no keepalive needed
        return client != null && client.isConnected();
    }

    @Override
    public List<String> getConfiguredAccounts() {
        return accounts;
    }

    @Override
    public void switchAccount(String accountId) throws BrokerException {
        // TWS API doesn't require explicit account switching for most operations
        // Account is specified per-request
        ensureConnected();
    }

    @Override
    public List<Position> getAllPositions() throws BrokerException {
        ensureConnected();

        CompletableFuture<List<Position>> future = new CompletableFuture<>();
        List<Position> positions = Collections.synchronizedList(new ArrayList<>());

        callbackHandler.registerPositionCallback(null, positions, future);

        client.reqPositions();

        try {
            List<Position> allPositions = future.get(30, TimeUnit.SECONDS);
            // Filter out zero/closed positions
            return allPositions.stream()
                    .filter(p -> !p.isZero())
                    .toList();
        } catch (TimeoutException e) {
            throw new BrokerException("Timeout waiting for positions", e);
        } catch (Exception e) {
            throw new BrokerException("Failed to fetch positions: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Position> getPositions(String accountId) throws BrokerException {
        ensureConnected();

        CompletableFuture<List<Position>> future = new CompletableFuture<>();
        List<Position> positions = Collections.synchronizedList(new ArrayList<>());

        callbackHandler.registerPositionCallback(accountId, positions, future);

        client.reqPositions();

        try {
            List<Position> allPositions = future.get(30, TimeUnit.SECONDS);
            // Filter by account and exclude zero positions
            return allPositions.stream()
                    .filter(p -> accountId.equals(p.accountId()))
                    .filter(p -> !p.isZero())
                    .toList();
        } catch (TimeoutException e) {
            throw new BrokerException("Timeout waiting for positions", e);
        } catch (Exception e) {
            throw new BrokerException("Failed to fetch positions: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Order> getOrders(String accountId) throws BrokerException {
        return getAllOrders().stream()
                .filter(o -> accountId.equals(o.accountId()))
                .toList();
    }

    @Override
    public List<Order> getAllOrders() throws BrokerException {
        ensureConnected();

        CompletableFuture<List<Order>> future = new CompletableFuture<>();
        List<Order> orders = Collections.synchronizedList(new ArrayList<>());

        callbackHandler.registerOrderCallback(orders, future);

        Log.info("Requesting all open orders from TWS...");
        client.reqAllOpenOrders();

        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // If we timeout, return whatever orders we've collected so far
            Log.warn("Timeout waiting for openOrderEnd - returning collected orders");
            callbackHandler.orderFuture = null;
            List<Order> collected = new ArrayList<>(orders);
            callbackHandler.orderList = null;
            return collected;
        } catch (Exception e) {
            throw new BrokerException("Failed to fetch orders: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Order> getStopOrders(String accountId) throws BrokerException {
        return getOrders(accountId).stream()
                .filter(this::isStopOrder)
                .toList();
    }

    @Override
    public List<Order> getAllStopOrders() throws BrokerException {
        return getAllOrders().stream()
                .filter(this::isStopOrder)
                .toList();
    }

    @Override
    public List<Order> getStopOrdersForConid(String accountId, Integer conid) throws BrokerException {
        return getStopOrders(accountId).stream()
                .filter(o -> conid.equals(o.conid()))
                .toList();
    }

    private boolean isStopOrder(Order order) {
        String type = order.orderType();
        return type != null && (type.equalsIgnoreCase("STP") || type.equalsIgnoreCase("STOP"));
    }

    @Override
    public OrderResult placeStopLossOrder(StopLossOrderRequest request) throws BrokerException {
        ensureConnected();

        int orderId = nextRequestId();

        // Create contract
        Contract contract = new Contract();
        contract.conid(request.conid());

        // Create order
        com.ib.client.Order order = new com.ib.client.Order();
        order.orderId(orderId);
        order.action(request.isLong() ? "SELL" : "BUY");
        order.orderType("STP");
        order.auxPrice(request.stopPrice().doubleValue());
        order.totalQuantity(Decimal.get(request.quantity().doubleValue()));
        order.tif("GTC");
        order.account(request.accountId());

        CompletableFuture<OrderResult> future = new CompletableFuture<>();
        callbackHandler.registerOrderStatusCallback(orderId, future);

        client.placeOrder(orderId, contract, order);

        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Order might still be submitted, return partial success
            return new OrderResult(true, String.valueOf(orderId), "Order submitted (confirmation pending)");
        } catch (Exception e) {
            throw new BrokerException("Failed to place order: " + e.getMessage(), e);
        }
    }

    // ==================== TWS Callback Handler ====================


// ... existing code ...

    // ==================== TWS Callback Handler ====================

    /**
     * Extends DefaultEWrapper to get default implementations for all callbacks,
     * then override only the ones we need.
     */
    private class TwsCallbackHandler extends DefaultEWrapper {

        // Position tracking
        private volatile List<Position> positionList;
        private volatile CompletableFuture<List<Position>> positionFuture;
        private volatile String positionAccountFilter;

        // Order tracking
        private volatile List<Order> orderList;
        private volatile CompletableFuture<List<Order>> orderFuture;

        // Order status tracking
        private final Map<Integer, CompletableFuture<OrderResult>> orderStatusFutures = new ConcurrentHashMap<>();

        void registerPositionCallback(String accountFilter, List<Position> list, CompletableFuture<List<Position>> future) {
            this.positionAccountFilter = accountFilter;
            this.positionList = list;
            this.positionFuture = future;
        }

        void registerOrderCallback(List<Order> list, CompletableFuture<List<Order>> future) {
            this.orderList = list;
            this.orderFuture = future;
        }

        void registerOrderStatusCallback(int orderId, CompletableFuture<OrderResult> future) {
            orderStatusFutures.put(orderId, future);
        }

        // ==================== Position Callbacks ====================

        @Override
        public void position(String account, Contract contract, Decimal pos, double avgCost) {
            Log.infof(">>> position callback: account=%s, symbol=%s, conid=%d, pos=%s, avgCost=%f, currency=%s",
                    account, contract.symbol(), contract.conid(), pos, avgCost, contract.currency());

            if (positionList != null) {
                BigDecimal qty = (pos != null && pos.isValid()) ? pos.value() : BigDecimal.ZERO;

                Position position = new Position(
                        account,
                        contract.conid(),
                        contract.symbol(),
                        qty,
                        BigDecimal.valueOf(avgCost),
                        BigDecimal.ZERO,
                        contract.currency()
                );
                positionList.add(position);
            }
        }

        @Override
        public void positionEnd() {
            Log.info(">>> positionEnd callback received");
            if (positionFuture != null && positionList != null) {
                positionFuture.complete(new ArrayList<>(positionList));
                positionList = null;
                positionFuture = null;
            }
        }

        // ==================== Order Callbacks ====================

        @Override
        public void openOrder(int orderId, Contract contract, com.ib.client.Order order, OrderState orderState) {
            Log.infof(">>> openOrder callback: orderId=%d, symbol=%s, type=%s, status=%s",
                    orderId, contract.symbol(), order.orderType(), orderState.status());
            if (orderList != null) {
                Order brokerOrder = new Order(
                        String.valueOf(orderId),
                        order.account(),
                        contract.conid(),
                        contract.symbol(),
                        order.orderType().getApiString(),
                        order.action().getApiString(),
                        BigDecimal.valueOf(order.lmtPrice()),
                        BigDecimal.valueOf(order.auxPrice()),
                        decimalToBigDecimal(order.totalQuantity()),
                        decimalToBigDecimal(order.totalQuantity()).subtract(decimalToBigDecimal(order.filledQuantity())),
                        orderState.status().name()
                );
                orderList.add(brokerOrder);
            }
        }

        @Override
        public void openOrderEnd() {
            Log.info(">>> openOrderEnd callback received");
            if (orderFuture != null && orderList != null) {
                orderFuture.complete(new ArrayList<>(orderList));
                orderList = null;
                orderFuture = null;
            }
        }

        @Override
        public void orderStatus(
                int orderId,
                String status,
                Decimal filled,
                Decimal remaining,
                double avgFillPrice,
                long permId,
                int parentId,
                double lastFillPrice,
                int clientId,
                String whyHeld,
                double mktCapPrice
        ) {
            Log.infof(">>> orderStatus callback: orderId=%d, status=%s, filled=%s, remaining=%s",
                    orderId, status, filled, remaining);

            CompletableFuture<OrderResult> future = orderStatusFutures.remove(orderId);
            if (future != null) {
                boolean success = !status.equalsIgnoreCase("Cancelled") && !status.equalsIgnoreCase("ApiCancelled");
                future.complete(new OrderResult(success, String.valueOf(orderId), "Order status: " + status));
            }
        }

        // ==================== Connection Callbacks ====================

        @Override
        public void connectAck() {
            Log.info(">>> connectAck callback received");
            if (client.isAsyncEConnect()) {
                client.startAPI();
            }
        }

        @Override
        public void connectionClosed() {
            Log.warn(">>> connectionClosed callback received");
            if (positionFuture != null) {
                positionFuture.completeExceptionally(new BrokerException("Connection closed"));
                positionFuture = null;
                positionList = null;
            }
            if (orderFuture != null) {
                orderFuture.completeExceptionally(new BrokerException("Connection closed"));
                orderFuture = null;
                orderList = null;
            }
        }

        @Override
        public void error(
                int reqId,
                long errorTime,
                int errorCode,
                String errorMsg,
                String advancedOrderRejectJson
        ) {
            Log.warnf(">>> error callback: reqId=%d, code=%d, msg=%s", reqId, errorCode, errorMsg);

            if (errorCode == 504 || errorCode == 502) {
                if (positionFuture != null) {
                    positionFuture.completeExceptionally(new BrokerException("Connection error: " + errorMsg));
                    positionFuture = null;
                    positionList = null;
                }
                if (orderFuture != null) {
                    orderFuture.completeExceptionally(new BrokerException("Connection error: " + errorMsg));
                    orderFuture = null;
                    orderList = null;
                }
            }

            CompletableFuture<OrderResult> future = orderStatusFutures.remove(reqId);
            if (future != null) {
                future.complete(new OrderResult(false, null, "Error " + errorCode + ": " + errorMsg));
            }
        }

        @Override
        public void nextValidId(int orderId) {
            Log.infof(">>> nextValidId callback: %d", orderId);
            requestIdCounter.set(orderId);
        }

        @Override
        public void managedAccounts(String accountsList) {
            Log.infof(">>> managedAccounts callback: %s", accountsList);
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Converts IB's Decimal type to BigDecimal properly.
     * Decimal.longValue() truncates decimals, so we use the string representation.
     */
    private BigDecimal decimalToBigDecimal(Decimal decimal) {
        if (decimal == null || !decimal.isValid()) {
            return BigDecimal.ZERO;
        }
        // Use string conversion to preserve precision
        return new BigDecimal(decimal.toString());
    }
}