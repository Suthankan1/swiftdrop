package com.swiftdrop.graphql.client;

import com.swiftdrop.accounts.v1.BatchProfileSummariesRequest;
import com.swiftdrop.accounts.v1.ScopedProfileSummaryResponse;
import com.swiftdrop.graphql.batching.BoundedBatchSupport;
import graphql.GraphQLContext;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.dataloader.DataLoader;

/** Opt-in loader for DISPATCHER/ADMIN only, matching the approved Accounts batch RPC policy. */
public final class AccountsProfileBatch {
    private AccountsProfileBatch() {}
    public static DataLoader<String, ScopedProfileSummaryResponse> create(AuthenticatedReadClients client,
                                                                          GraphQLContext context, String approvedScope) {
        return BoundedBatchSupport.create(subjects -> {
            var response = client.accounts(context, stub -> stub.getBatchProfileSummaries(
                    BatchProfileSummariesRequest.newBuilder().addAllSubjects(subjects).setScope(approvedScope).build()));
            var values = response.getItemsList().stream().collect(Collectors.toMap(
                    ScopedProfileSummaryResponse::getSubject, Function.identity()));
            return CompletableFuture.completedFuture(values);
        });
    }
}
