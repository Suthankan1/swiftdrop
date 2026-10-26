package com.swiftdrop.graphql.batching;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderFactory;
import org.dataloader.DataLoaderOptions;

public final class BoundedBatchSupport {
    private BoundedBatchSupport() {}
    /** Call once per request; provider must return stable keys and authorize every requested item. */
    public static <K, V> DataLoader<K, V> create(Function<List<K>, CompletionStage<Map<K, V>>> provider) {
        return DataLoaderFactory.newDataLoader(keys -> {
            if (keys.size() > 50) throw new IllegalArgumentException("Batch too large");
            return provider.apply(List.copyOf(keys)).thenApply(values -> {
                if (values.size() > keys.size() || !keys.containsAll(values.keySet())) {
                    throw new IllegalStateException("Invalid provider response");
                }
                return keys.stream().map(values::get).toList();
            });
        }, DataLoaderOptions.newOptions().setMaxBatchSize(50).setCachingEnabled(false).build());
    }
}
