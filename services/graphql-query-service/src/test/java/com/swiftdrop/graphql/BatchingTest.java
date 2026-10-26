package com.swiftdrop.graphql;

import com.swiftdrop.graphql.batching.BoundedBatchSupport;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class BatchingTest {
    @Test void batchesAreBoundedAndMaintainKeyOrderWithoutCrossRequestCache() {
        AtomicInteger max = new AtomicInteger();
        var first = BoundedBatchSupport.<String, String>create(keys -> {
            max.accumulateAndGet(keys.size(), Math::max);
            return CompletableFuture.completedFuture(keys.stream().collect(java.util.stream.Collectors.toMap(k -> k, k -> "first-" + k)));
        });
        for (int i = 0; i < 101; i++) first.load("k" + i);
        first.dispatchAndJoin();
        assertThat(max).hasValue(50);
        var second = BoundedBatchSupport.<String, String>create(keys -> CompletableFuture.completedFuture(Map.of("k0", "second")));
        var value = second.load("k0"); second.dispatchAndJoin();
        assertThat(value.join()).isEqualTo("second");
    }
    @Test void missingItemsRetainPositionsAndUnexpectedKeysFailClosed() {
        var loader = BoundedBatchSupport.<String, String>create(keys -> CompletableFuture.completedFuture(Map.of("b", "B")));
        var values = loader.loadMany(java.util.List.of("a", "b")); loader.dispatchAndJoin();
        assertThat(values.join()).containsExactly(null, "B");
        var corrupt = BoundedBatchSupport.<String, String>create(keys -> CompletableFuture.completedFuture(Map.of("other", "secret")));
        var failed = corrupt.load("a"); corrupt.dispatchAndJoin();
        assertThatThrownBy(failed::join).hasCauseInstanceOf(IllegalStateException.class);
    }
}
