
package com.github.riskmanager;

import com.github.riskmanager.ib.api.ApiException;
import com.github.riskmanager.ib.api.SessionApi;
import com.github.riskmanager.ib.model.AuthStatus;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

@ApplicationScoped
public class IBGatewayHealthService {

    private static final Logger LOG = Logger.getLogger(IBGatewayHealthService.class);

    private final SessionApi sessionApi;

    public IBGatewayHealthService(@RestClient SessionApi sessionApi) {
        this.sessionApi = sessionApi;
    }

    void onStart(@Observes StartupEvent ev) {
        LOG.info("Checking IB Gateway authentication status on startup...");
        GatewayStatus status = checkGatewayStatus();

        if (status.authenticated()) {
            LOG.info("✓ IB Gateway is authenticated and ready");
        } else {
            LOG.warn("⚠ IB Gateway is NOT authenticated. Please login at https://localhost:5500");
            LOG.warn("  Status: " + status.message());
        }
    }

    public GatewayStatus checkGatewayStatus() {
        try {
            // Use auth status endpoint - this returns proper JSON even when not authenticated
            AuthStatus authStatus = sessionApi.iserverAuthStatusPost();

            boolean authenticated = authStatus.getAuthenticated() != null && authStatus.getAuthenticated();
            boolean connected = authStatus.getConnected() != null && authStatus.getConnected();
            boolean competing = authStatus.getCompeting() != null && authStatus.getCompeting();

            String message = authenticated
                    ? "Session is authenticated and ready"
                    : (authStatus.getFail() != null ? authStatus.getFail() : "Not authenticated");

            return new GatewayStatus(
                    true,
                    authenticated,
                    connected,
                    competing,
                    message
            );
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            if (status == 302) {
                return new GatewayStatus(true, false, false, false,
                        "Session not authenticated (HTTP 302). Please login at https://localhost:5500");
            }
            return new GatewayStatus(true, false, false, false, "Gateway returned HTTP " + status);
        } catch (ApiException e) {
            return new GatewayStatus(true, false, false, false, "API error: " + e.getMessage());
        } catch (Exception e) {
            return new GatewayStatus(false, false, false, false,
                    "Cannot reach IB Gateway: " + e.getMessage());
        }
    }

    public boolean keepAlive() {
        try {
            sessionApi.ticklePost();
            return true;
        } catch (Exception e) {
            LOG.warn("Failed to keep session alive: " + e.getMessage());
            return false;
        }
    }

    public record GatewayStatus(
            boolean gatewayReachable,
            boolean authenticated,
            boolean connected,
            boolean competing,
            String message
    ) {}
}