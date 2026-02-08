package com.github.riskmanager;

import com.github.riskmanager.broker.BrokerGateway;
import com.github.riskmanager.broker.BrokerGateway.ConnectionStatus;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * Health check service that validates broker connectivity on startup.
 * Now uses the BrokerGateway abstraction.
 */
@ApplicationScoped
public class IBGatewayHealthService {

    private static final Logger LOG = Logger.getLogger(IBGatewayHealthService.class);

    private final BrokerGateway brokerGateway;

    public IBGatewayHealthService(BrokerGateway brokerGateway) {
        this.brokerGateway = brokerGateway;
    }

    void onStart(@Observes StartupEvent ev) {
        LOG.info("Checking broker gateway connection status on startup...");
        ConnectionStatus status = checkGatewayStatus();

        if (status.authenticated()) {
            LOG.info("✓ Broker gateway is authenticated and ready");
        } else {
            LOG.warn("⚠ Broker gateway is NOT authenticated.");
            LOG.warn("  Status: " + status.message());
        }
    }

    public ConnectionStatus checkGatewayStatus() {
        return brokerGateway.getConnectionStatus();
    }

    public boolean keepAlive() {
        return brokerGateway.keepAlive();
    }

    /**
     * @deprecated Use ConnectionStatus from BrokerGateway instead.
     * Kept for backward compatibility with existing API responses.
     */
    @Deprecated
    public record GatewayStatus(
            boolean gatewayReachable,
            boolean authenticated,
            boolean connected,
            boolean competing,
            String message
    ) {
        public static GatewayStatus from(ConnectionStatus status) {
            return new GatewayStatus(
                    status.reachable(),
                    status.authenticated(),
                    status.connected(),
                    status.competing(),
                    status.message()
            );
        }
    }
}