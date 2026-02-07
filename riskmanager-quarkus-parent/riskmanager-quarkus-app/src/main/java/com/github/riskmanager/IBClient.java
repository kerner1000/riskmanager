package com.github.riskmanager;

import com.github.riskmanager.dto.OrdersResponse;
import com.github.riskmanager.dto.PlaceOrderResponse;
import com.github.riskmanager.dto.Position;
import com.github.riskmanager.dto.SubmitOrderRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.List;

@Path("/v1/api")
@RegisterRestClient(configKey = "ib-client")
@RegisterClientHeaders(IBClientHeadersFactory.class)
public interface IBClient {

    @GET
    @Path("/portfolio/{accountId}/positions/0")
    @Produces(MediaType.APPLICATION_JSON)
    List<Position> getPositions(@PathParam("accountId") String accountId);

    @POST
    @Path("/iserver/account/{accountId}/orders")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    List<PlaceOrderResponse> placeOrder(
            @PathParam("accountId") String accountId,
            SubmitOrderRequest orderRequest
    );

    @POST
    @Path("/iserver/reply/{replyId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    List<PlaceOrderResponse> confirmOrder(
            @PathParam("replyId") String replyId,
            ConfirmRequest confirmRequest
    );

    @GET
    @Path("/iserver/account/orders")
    @Produces(MediaType.APPLICATION_JSON)
    OrdersResponse getOrders(
            @QueryParam("accountId") String accountId,
            @QueryParam("filters") String filters,
            @QueryParam("force") boolean force
    );

    // Switch the selected account (required for multi-account setups)
    @POST
    @Path("/iserver/account")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    SwitchAccountResponse switchAccount(SwitchAccountRequest request);

    record ConfirmRequest(boolean confirmed) {}

    record SwitchAccountRequest(String acctId) {}

    record SwitchAccountResponse(boolean set, String acctId) {}
}
