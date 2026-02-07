package com.github.riskmanager;



import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.util.Optional;

@Provider
@ApplicationScoped
@Priority(Priorities.HEADER_DECORATOR)
public class AcceptHeaderFilter implements ClientRequestFilter {

    @ConfigProperty(name = "ib.gateway.session-cookie")
    Optional<String> sessionCookie;

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        System.out.println("Adding Accept header to request");
        requestContext.getHeaders().putSingle("Accept", "*/*");

        // Add session cookies if configured
        sessionCookie.filter(s -> !s.isBlank())
                .ifPresent(cookie -> {
                    System.out.println("Adding Cookie header to request");
                    requestContext.getHeaders().putSingle("Cookie", cookie);
                });
    }
}