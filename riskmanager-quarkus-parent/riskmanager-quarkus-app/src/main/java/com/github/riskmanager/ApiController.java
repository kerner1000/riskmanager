package com.github.riskmanager;

import com.github.riskmanager.ib.api.AccountApi;
import com.github.riskmanager.ib.api.ApiException;
import com.github.riskmanager.ib.api.OrderApi;
import com.github.riskmanager.ib.api.PortfolioApi;
import com.github.riskmanager.ib.model.IserverAccountOrdersGet200Response;
import com.github.riskmanager.ib.model.IserverAccountOrdersGet200ResponseOrdersInner;
import com.github.riskmanager.ib.model.PositionInner;
import com.github.riskmanager.ib.model.SetAccount;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Path("/api")
public class ApiController {

    private final PortfolioApi portfolioApi;
    private final OrderApi orderApi;
    private final AccountApi accountApi;
    private final RiskCalculationService riskService;
    private final IBGatewayHealthService gatewayHealthService;

    @ConfigProperty(name = "risk.accounts")
    List<String> accounts;

    public ApiController(
            @RestClient PortfolioApi portfolioApi,
            @RestClient OrderApi orderApi,
            @RestClient AccountApi accountApi,
            RiskCalculationService riskService,
            IBGatewayHealthService gatewayHealthService
    ) {
        this.portfolioApi = portfolioApi;
        this.orderApi = orderApi;
        this.accountApi = accountApi;
        this.riskService = riskService;
        this.gatewayHealthService = gatewayHealthService;
    }

    @GET
    @Path("/gateway/status")
    @Produces(MediaType.APPLICATION_JSON)
    public IBGatewayHealthService.GatewayStatus getGatewayStatus() {
        return gatewayHealthService.checkGatewayStatus();
    }

    @POST
    @Path("/gateway/keepalive")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean keepAlive() {
        return gatewayHealthService.keepAlive();
    }


    @GET
    @Path("/positions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<PositionInner> getAllPositions() {
        List<PositionInner> allPositions = new ArrayList<>();
        for (String accountId : accounts) {
            List<PositionInner> positions = null;
            try {
                positions = portfolioApi.portfolioAccountIdPositionsPageIdGet(
                        accountId, "0", null, null, null, null);
            } catch (ApiException e) {
                throw new RuntimeException(e);
            }
            if (positions != null) {
                allPositions.addAll(positions);
            }
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
                    report.unprotectedLoss(),
                    BigDecimal.ZERO,
                    report.unprotectedLoss(),
                    report.currency(),
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
            @PathParam("conid") Integer conid,
            @QueryParam("lossPercentage") @DefaultValue("10") BigDecimal lossPercentage
    ) throws Exception {
        List<RiskCalculationService.StopLossResult> results = new ArrayList<>();
        for (String accountId : accounts) {
            List<PositionInner> positions = portfolioApi.portfolioAccountIdPositionsPageIdGet(
                    accountId, "0", null, null, null, null);
            if (positions != null && positions.stream().anyMatch(p -> conid.equals(p.getConid()))) {
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
            List<PositionInner> positions = portfolioApi.portfolioAccountIdPositionsPageIdGet(
                    accountId, "0", null, null, null, null);
            if (positions != null && positions.stream().anyMatch(p -> ticker.equalsIgnoreCase(p.getContractDesc()))) {
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
            List<PositionInner> positions = portfolioApi.portfolioAccountIdPositionsPageIdGet(
                    accountId, "0", null, null, null, null);
            if (positions != null && positions.stream().anyMatch(p -> ticker.equalsIgnoreCase(p.getContractDesc()))) {
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
    public IserverAccountOrdersGet200Response getAllOrders() throws InterruptedException {
        List<IserverAccountOrdersGet200ResponseOrdersInner> allOrders = new ArrayList<>();

        for (String accountId : accounts) {
            // Switch account
            SetAccount setAccount = new SetAccount();
            setAccount.setAcctId(accountId);
            try {
                accountApi.iserverAccountPost(setAccount);
            } catch (ApiException e) {
                throw new RuntimeException(e);
            }
            Thread.sleep(200);

            // Force refresh
            try {
                orderApi.iserverAccountOrdersGet(null);
            } catch (ApiException e) {
                throw new RuntimeException(e);
            }
            Thread.sleep(200);

            // Get orders
            IserverAccountOrdersGet200Response response = null;
            try {
                response = orderApi.iserverAccountOrdersGet(null);
            } catch (ApiException e) {
                throw new RuntimeException(e);
            }
            if (response != null && response.getOrders() != null) {
                allOrders.addAll(response.getOrders());
            }
        }

        IserverAccountOrdersGet200Response result = new IserverAccountOrdersGet200Response();
        result.setOrders(allOrders);
        return result;
    }
}