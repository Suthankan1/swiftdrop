package com.swiftdrop.graphql.execution;

import java.util.UUID;
import java.util.List;
import com.swiftdrop.graphql.batching.RequestBatchLoader;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class QueryContextInterceptor implements WebGraphQlInterceptor {
    private final List<RequestBatchLoader> loaders;
    public QueryContextInterceptor(List<RequestBatchLoader> loaders) {
        this.loaders = List.copyOf(loaders);
        if (loaders.stream().map(RequestBatchLoader::name).distinct().count() != loaders.size())
            throw new IllegalArgumentException("Duplicate loader name");
    }
    public static String sanitize(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}") ? value : UUID.randomUUID().toString();
    }
    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        String correlation = sanitize(request.getHeaders().getFirst("x-swiftdrop-correlation-id"));
        var principal = SecurityContextHolder.getContext().getAuthentication();
        if (!(principal instanceof JwtAuthenticationToken actor)) return Mono.error(new AccessDeniedException("Query access denied"));
        request.configureExecutionInput((input, builder) -> {
            var context = graphql.GraphQLContext.newContext().of("actor", actor, "correlationId", correlation).build();
            var registry = new org.dataloader.DataLoaderRegistry();
            loaders.forEach(loader -> registry.register(loader.name(), loader.create(context)));
            return builder.graphQLContext(ctx -> ctx.put("actor", actor).put("correlationId", correlation))
                    .dataLoaderRegistry(registry).build();
        });
        return chain.next(request).doOnNext(response -> response.getResponseHeaders().set("x-swiftdrop-correlation-id", correlation));
    }
}
