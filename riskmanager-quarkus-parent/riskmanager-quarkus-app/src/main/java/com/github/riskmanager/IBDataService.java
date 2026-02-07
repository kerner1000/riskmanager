package com.github.riskmanager;

import com.github.riskmanager.ib.api.AccountApi;
import com.github.riskmanager.ib.api.ApiException;
import com.github.riskmanager.ib.api.OrderApi;
import com.github.riskmanager.ib.api.PortfolioApi;
import com.github.riskmanager.ib.model.*;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@ApplicationScoped
public class IBDataService {

    @RestClient
    @Inject
    PortfolioApi portfolioApi;

    @RestClient
    @Inject
    OrderApi orderApi;

    @RestClient
    @Inject
    AccountApi accountApi;

    @ConfigProperty(name = "risk.accounts")
    List<String> accounts;

    public List<String> getConfiguredAccounts() {
        return accounts;
    }

    public List<PositionInner> fetchAllPositions(List<String> accountIds) throws ApiException {
        List<PositionInner> allPositions = new ArrayList<>();
        for (String accountId : accountIds) {
            allPositions.addAll(fetchPositionsForAccount(accountId));
        }
        return allPositions;
    }

    public List<PositionInner> fetchPositionsForAccount(String accountId) throws ApiException {
        List<PositionInner> positions = portfolioApi.portfolioAccountIdPositionsPageIdGet(
                accountId, "0", null, null, null, null);
        return positions != null ? positions : List.of();
    }

    public List<IserverAccountOrdersGet200ResponseOrdersInner> fetchAllStopOrders() throws Exception {
        Set<String> seenOrderIds = new HashSet<>();
        List<IserverAccountOrdersGet200ResponseOrdersInner> stopOrders = new ArrayList<>();

        for (String acct : accounts) {
            switchToAccount(acct);

            // Force refresh for this account
            orderApi.iserverAccountOrdersGet(null);
            Thread.sleep(300);

            for (IserverAccountOrdersGet200ResponseOrdersInner order : fetchStopOrdersForAccount(acct)) {
                if (order.getOrderId() != null && seenOrderIds.add(order.getOrderId())) {
                    stopOrders.add(order);
                }
            }
        }
        Log.debugf("Unique stop orders: %d", stopOrders.size());
        return stopOrders;
    }

    public List<IserverAccountOrdersGet200ResponseOrdersInner> fetchStopOrdersForAccountWithRefresh(String accountId) throws Exception {
        switchToAccount(accountId);

        // Force refresh
        orderApi.iserverAccountOrdersGet(null);
        Thread.sleep(200);

        return fetchStopOrdersForAccount(accountId);
    }

    public List<IserverAccountOrdersGet200ResponseOrdersInner> fetchStopOrdersForConid(String accountId, Integer conid) throws Exception {
        return fetchStopOrdersForAccountWithRefresh(accountId).stream()
                .filter(o -> conid.equals(o.getConid() != null ? o.getConid().intValue() : null))
                .toList();
    }

    private void switchToAccount(String accountId) throws InterruptedException {
        try {
            SetAccount setAccount = new SetAccount();
            setAccount.setAcctId(accountId);
            var response = accountApi.iserverAccountPost(setAccount);
            Log.debugf("Switched to account %s, success=%s", accountId, response.getSet());
            Thread.sleep(200);
        } catch (ApiException e) {
            Log.debugf("Failed to switch to account %s: %s", accountId, e.getMessage());
        }
    }

    private List<IserverAccountOrdersGet200ResponseOrdersInner> fetchStopOrdersForAccount(String accountId) throws ApiException {
        IserverAccountOrdersGet200Response response = orderApi.iserverAccountOrdersGet(null);

        if (response == null || response.getOrders() == null) {
            Log.debugf("Orders for %s: null", accountId);
            return List.of();
        }

        Log.debugf("Orders for %s: %d", accountId, response.getOrders().size());

        return response.getOrders().stream()
                .filter(this::isStopOrder)
                .filter(this::isActiveOrder)
                .toList();
    }

    private boolean isStopOrder(IserverAccountOrdersGet200ResponseOrdersInner order) {
        String type = order.getOrderType();
        return type != null && (type.toLowerCase().contains("stop") || type.equalsIgnoreCase("STP"));
    }

    private boolean isActiveOrder(IserverAccountOrdersGet200ResponseOrdersInner order) {
        String status = order.getStatus();
        Log.debugf("Stop Order: ticker=%s, acct=%s, conid=%s, status=%s",
                order.getTicker(), order.getAcct(), order.getConid(), status);
        return status == null || (!status.equalsIgnoreCase("Cancelled") && !status.equalsIgnoreCase("Filled"));
    }

    // ==================== Order Placement ====================

    public List<IserverAccountAccountIdOrderPost200ResponseInner> placeOrder(
            String accountId,
            IserverAccountAccountIdOrdersPostRequest request) throws ApiException {
        return orderApi.iserverAccountAccountIdOrdersPost(accountId, request);
    }

    public List<IserverReplyReplyidPost200ResponseInner> confirmOrder(String replyId) throws ApiException {
        IserverReplyReplyidPostRequest confirmRequest = new IserverReplyReplyidPostRequest();
        confirmRequest.setConfirmed(true);
        return orderApi.iserverReplyReplyidPost(replyId, confirmRequest);
    }
}