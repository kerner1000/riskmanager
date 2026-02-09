package com.github.riskmanager;

import com.github.riskmanager.broker.BrokerException;
import com.github.riskmanager.broker.BrokerGateway;
import com.github.riskmanager.broker.BrokerGateway.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Path("/api")
public class ApiController {

    private final BrokerGateway brokerGateway;
    private final RiskCalculationService riskService;

    public ApiController(
            BrokerGateway brokerGateway,
            RiskCalculationService riskService
    ) {
        this.brokerGateway = brokerGateway;
        this.riskService = riskService;
    }

    @GET
    @Path("/gateway/status")
    @Produces(MediaType.APPLICATION_JSON)
    public ConnectionStatus getGatewayStatus() {
        return brokerGateway.getConnectionStatus();
    }

    @POST
    @Path("/gateway/keepalive")
    @Produces(MediaType.APPLICATION_JSON)
    public boolean keepAlive() {
        return brokerGateway.keepAlive();
    }

    @GET
    @Path("/positions")
    @Produces(MediaType.APPLICATION_JSON)
    public List<Position> getAllPositions() throws BrokerException {
        return brokerGateway.getAllPositions();
    }

    @GET
    @Path("/risk")
    @Produces(MediaType.APPLICATION_JSON)
    public RiskReport getWorstCaseRisk(
            @QueryParam("unprotectedOnly") @DefaultValue("false") boolean unprotectedOnly
    ) throws Exception {
        List<String> accounts = brokerGateway.getConfiguredAccounts();
        RiskReport report = riskService.calculateWorstCaseScenarioForAccounts(accounts);

        if (unprotectedOnly) {
            List<PositionRisk> filtered = report.positionRisks().stream()
                    .filter(r -> !r.hasStopLoss())
                    .toList();

            BigDecimal filteredAtRiskProfit = filtered.stream()
                    .map(PositionRisk::atRiskProfitBase)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal filteredPositionValue = filtered.stream()
                    .map(PositionRisk::positionValueBase)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            return new RiskReport(
                    report.worstCaseProfitWithoutStopLoss(),
                    BigDecimal.ZERO,
                    report.worstCaseProfitWithoutStopLoss(),
                    filteredAtRiskProfit,
                    filteredPositionValue,
                    report.currency(),
                    report.unprotectedLossPercentageUsed(),
                    filtered
            );
        }
        return report;
    }

    @GET
    @Path("/risk/csv")
    @Produces("text/csv")
    public Response getWorstCaseRiskCsv(
            @QueryParam("unprotectedOnly") @DefaultValue("false") boolean unprotectedOnly
    ) throws Exception {
        RiskReport report = getWorstCaseRisk(unprotectedOnly);

        StringBuilder csv = new StringBuilder();
        // Header row
        csv.append("Account ID,Ticker,Position Size,Avg Price,Current Price,Stop Price,")
                .append("Order Quantity,Locked Profit,At-Risk Profit,Position Value,Currency,")
                .append("Locked Profit (Base),At-Risk Profit (Base),Position Value (Base),Base Currency,Has Stop Loss,Portfolio %\n");

        // Data rows
        for (PositionRisk risk : report.positionRisks()) {
            csv.append(escapeCsv(risk.accountId())).append(",")
                    .append(escapeCsv(risk.ticker())).append(",")
                    .append(risk.positionSize()).append(",")
                    .append(risk.avgPrice()).append(",")
                    .append(risk.currentPrice()).append(",")
                    .append(risk.stopPrice()).append(",")
                    .append(risk.orderQuantity()).append(",")
                    .append(risk.lockedProfit()).append(",")
                    .append(risk.atRiskProfit()).append(",")
                    .append(risk.positionValue()).append(",")
                    .append(escapeCsv(risk.currency())).append(",")
                    .append(risk.lockedProfitBase()).append(",")
                    .append(risk.atRiskProfitBase()).append(",")
                    .append(risk.positionValueBase()).append(",")
                    .append(escapeCsv(risk.baseCurrency())).append(",")
                    .append(risk.hasStopLoss()).append(",")
                    .append(risk.portfolioPercentage()).append("\n");
        }

        // Summary row
//        csv.append(",TOTAL,,,,,,,,,")
//                .append(escapeCsv(report.currency())).append(",")
//                .append(report.worstCaseProfit()).append(",")
//                .append(report.totalAtRiskProfit()).append(",")
//                .append(report.totalPositionValue()).append(",")
//                .append(escapeCsv(report.currency())).append(",,100\n");

        return Response.ok(csv.toString())
                .type("text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=risk-report.csv")
                .build();
    }

    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    @POST
    @Path("/risk/protect")
    @Produces(MediaType.APPLICATION_JSON)
    public List<StopLossResult> createMissingStopLosses(
            @QueryParam("lossPercentage") @DefaultValue("10") BigDecimal lossPercentage
    ) throws Exception {
        List<StopLossResult> allResults = new ArrayList<>();
        for (String accountId : brokerGateway.getConfiguredAccounts()) {
            allResults.addAll(riskService.createMissingStopLosses(accountId, lossPercentage));
        }
        return allResults;
    }

    @POST
    @Path("/risk/protect/{conid}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<StopLossResult> createStopLossForConid(
            @PathParam("conid") Integer conid,
            @QueryParam("lossPercentage") @DefaultValue("10") BigDecimal lossPercentage
    ) throws Exception {
        List<StopLossResult> results = new ArrayList<>();
        for (String accountId : brokerGateway.getConfiguredAccounts()) {
            List<Position> positions = brokerGateway.getPositions(accountId);
            if (positions.stream().anyMatch(p -> conid.equals(p.conid()))) {
                results.add(riskService.createStopLossForPosition(accountId, conid, lossPercentage));
            }
        }
        if (results.isEmpty()) {
            results.add(new StopLossResult(null, null, conid, null, null, false,
                    "Position not found for conid: " + conid + " in any configured account"));
        }
        return results;
    }

    @POST
    @Path("/risk/protect/ticker/{ticker}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<StopLossResult> createStopLossForTicker(
            @PathParam("ticker") String ticker,
            @QueryParam("lossPercentage") @DefaultValue("10") BigDecimal lossPercentage
    ) throws Exception {
        List<StopLossResult> results = new ArrayList<>();
        for (String accountId : brokerGateway.getConfiguredAccounts()) {
            List<Position> positions = brokerGateway.getPositions(accountId);
            if (positions.stream().anyMatch(p -> ticker.equalsIgnoreCase(p.ticker()))) {
                results.add(riskService.createStopLossForPositionByTicker(accountId, ticker, lossPercentage));
            }
        }
        if (results.isEmpty()) {
            results.add(new StopLossResult(null, ticker, null, null, null, false,
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
        for (String accountId : brokerGateway.getConfiguredAccounts()) {
            List<Position> positions = brokerGateway.getPositions(accountId);
            if (positions.stream().anyMatch(p -> ticker.equalsIgnoreCase(p.ticker()))) {
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
    public List<Order> getAllOrders() throws BrokerException {
        return brokerGateway.getAllOrders();
    }
}