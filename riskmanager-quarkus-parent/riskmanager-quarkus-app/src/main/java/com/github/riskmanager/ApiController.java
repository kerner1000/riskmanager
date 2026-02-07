package com.github.riskmanager;

import com.github.riskmanager.dto.Order;
import com.github.riskmanager.dto.OrdersResponse;
import com.github.riskmanager.dto.Position;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;


@Path("/api")
public class ApiController {

    private final IBClient ibClient;
    private final RiskCalculationService riskService;

    @ConfigProperty(name = "risk.accounts")
    List<String> accounts;

    public ApiController(@RestClient IBClient ibClient, RiskCalculationService riskService) {
        this.ibClient = ibClient;
        this.riskService = riskService;
    }

    @GET
    @Path("/positions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Position> getAllPositions() {
        List<Position> allPositions = new ArrayList<>();
        for (String accountId : accounts) {
            allPositions.addAll(ibClient.getPositions(accountId));
        }
        return allPositions;
    }

    @GET
    @Path("/risk")
    @Produces(MediaType.APPLICATION_JSON)
    public RiskCalculationService.RiskReport getWorstCaseRisk(
            @QueryParam("unprotectedOnly") @DefaultValue("false") boolean unprotectedOnly
    ) throws Exception {
        RiskCalculationService.RiskReport report = riskService.calculateWorstCaseScenarioForAccounts(accounts);

        if (unprotectedOnly) {
            List<RiskCalculationService.PositionRisk> filtered = report.positionRisks().stream()
                    .filter(r -> !r.hasStopLoss())
                    .toList();
            return new RiskCalculationService.RiskReport(
                    report.totalPotentialLoss(),
                    report.protectedLoss(),
                    report.unprotectedLoss(),
                    report.unprotectedLossPercentageUsed(),
                    filtered
            );
        }
        return report;
    }

    @POST
    @Path("/risk/protect")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RiskCalculationService.StopLossResult> createMissingStopLosses(
            @QueryParam("lossPercentage") @DefaultValue("10") BigDecimal lossPercentage
    ) throws Exception {
        List<RiskCalculationService.StopLossResult> allResults = new ArrayList<>();
        for (String accountId : accounts) {
            allResults.addAll(riskService.createMissingStopLosses(accountId, lossPercentage));
        }
        return allResults;
    }

    @POST
    @Path("/risk/protect/{conid}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RiskCalculationService.StopLossResult> createStopLossForConid(
            @PathParam("conid") Long conid,
            @QueryParam("lossPercentage") @DefaultValue("10") BigDecimal lossPercentage
    ) throws Exception {
        List<RiskCalculationService.StopLossResult> results = new ArrayList<>();
        for (String accountId : accounts) {
            List<Position> positions = ibClient.getPositions(accountId);
            if (positions.stream().anyMatch(p -> p.conid().equals(conid))) {
                results.add(riskService.createStopLossForPosition(accountId, conid, lossPercentage));
            }
        }
        if (results.isEmpty()) {
            results.add(new RiskCalculationService.StopLossResult(null, null, conid, null, null, false,
                    "Position not found for conid: " + conid + " in any configured account"));
        }
        return results;
    }

    @POST
    @Path("/risk/protect/ticker/{ticker}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<RiskCalculationService.StopLossResult> createStopLossForTicker(
            @PathParam("ticker") String ticker,
            @QueryParam("lossPercentage") @DefaultValue("10") BigDecimal lossPercentage
    ) throws Exception {
        List<RiskCalculationService.StopLossResult> results = new ArrayList<>();
        for (String accountId : accounts) {
            List<Position> positions = ibClient.getPositions(accountId);
            if (positions.stream().anyMatch(p -> ticker.equalsIgnoreCase(p.contractDesc()))) {
                results.add(riskService.createStopLossForPositionByTicker(accountId, ticker, lossPercentage));
            }
        }
        if (results.isEmpty()) {
            results.add(new RiskCalculationService.StopLossResult(null, ticker, null, null, null, false,
                    "Position not found for ticker: " + ticker + " in any configured account"));
        }
        return results;
    }


    @POST
    @Path("/risk/protect/ticker/{ticker}/debug")
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> createStopLossForTickerDebug(
            @PathParam("ticker") String ticker,
            @QueryParam("lossPercentage") @DefaultValue("10") BigDecimal lossPercentage
    ) throws Exception {
        List<String> results = new ArrayList<>();
        for (String accountId : accounts) {
            List<Position> positions = ibClient.getPositions(accountId);
            if (positions.stream().anyMatch(p -> ticker.equalsIgnoreCase(p.contractDesc()) || ticker.equalsIgnoreCase(p.contractDesc()))) {
                results.add("[Account: " + accountId + "]\n" +
                        riskService.createStopLossForPositionDebug(accountId, ticker, lossPercentage));
            }
        }
        if (results.isEmpty()) {
            results.add("Position not found for ticker: " + ticker + " in any configured account");
        }
        return results;
    }

    @GET
    @Path("/orders/all")
    @Produces(MediaType.APPLICATION_JSON)
    public OrdersResponse getAllOrders() {
        List<Order> allOrders = new ArrayList<>();
        for (String accountId : accounts) {
            ibClient.getOrders(accountId, null, true); // force refresh
            OrdersResponse response = ibClient.getOrders(accountId, null, true);
            if (response.orders() != null) {
                allOrders.addAll(response.orders());
            }
        }
        return new OrdersResponse(allOrders);
    }



}
