package com.swiftdrop.graphql.limits;

import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import io.grpc.Status;
import java.util.Collection;
import java.util.concurrent.CompletionStage;
import org.springframework.stereotype.Component;

/** Final fail-closed check, not pagination: providers must bound reads before retrieval. */
@Component
public class ResultBoundsInstrumentation extends SimplePerformantInstrumentation {
    @Override
    public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> fetcher, InstrumentationFieldFetchParameters params,
                                                InstrumentationState state) {
        return env -> {
            Object value = fetcher.get(env);
            return value instanceof CompletionStage<?> future ? future.thenApply(this::check) : check(value);
        };
    }
    private Object check(Object value) {
        if ((value instanceof Collection<?> items && items.size() > 50)
                || (value instanceof String text && text.length() > 4096)
                || (value != null && value.getClass().isArray() && java.lang.reflect.Array.getLength(value) > 50)) {
            throw Status.INTERNAL.asRuntimeException();
        }
        if (value instanceof Collection<?> items) items.forEach(this::check);
        else if (value instanceof Iterable<?>) throw Status.INTERNAL.asRuntimeException();
        else if (value != null && value.getClass().isArray()) {
            for (int index = 0; index < java.lang.reflect.Array.getLength(value); index++)
                check(java.lang.reflect.Array.get(value, index));
        }
        return value;
    }
}
