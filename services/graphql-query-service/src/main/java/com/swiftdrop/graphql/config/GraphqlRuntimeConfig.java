package com.swiftdrop.graphql.config;

import com.swiftdrop.graphql.limits.PaginationPolicy;
import com.swiftdrop.graphql.resolver.RootQueryResolver;
import com.swiftdrop.graphql.security.GraphqlFieldPolicy;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLTypeUtil;
import io.grpc.Status;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@Configuration(proxyBeanMethods = false)
public class GraphqlRuntimeConfig {
    public static final List<String> ROOTS = List.of("customerDashboard", "merchantDashboard", "courierDashboard",
            "dispatcherDashboard", "adminDashboard", "deliveryStats");
    @Bean
    org.springframework.beans.factory.SmartInitializingSingleton schemaBoundary(org.springframework.graphql.execution.GraphQlSource source) {
        return () -> {
            var schema = source.schema();
            var actual = schema.getQueryType().getFieldDefinitions().stream().map(f -> f.getName()).collect(Collectors.toSet());
            if (schema.getMutationType() != null || schema.getSubscriptionType() != null || !actual.equals(java.util.Set.copyOf(ROOTS)))
                throw new IllegalStateException("Unapproved executable schema");
        };
    }
    @Bean
    public MaxQueryDepthInstrumentation depthLimit() { return new MaxQueryDepthInstrumentation(5); }

    @Bean
    public MaxQueryComplexityInstrumentation complexityLimit() {
        // Each scalar costs 1; list child work is conservatively multiplied by the approved cap 50.
        return new MaxQueryComplexityInstrumentation(100, (env, child) -> {
            int multiplier = GraphQLTypeUtil.unwrapNonNull(env.getFieldDefinition().getType()) instanceof GraphQLList ? 50 : 1;
            return 1 + multiplier * child;
        });
    }

    @Bean
    RuntimeWiringConfigurer rootWiring(List<RootQueryResolver> resolvers) {
        Map<String, RootQueryResolver> registered = resolvers.stream()
                .collect(Collectors.toUnmodifiableMap(RootQueryResolver::field, Function.identity()));
        if (!ROOTS.containsAll(registered.keySet())) throw new IllegalArgumentException("Unapproved query resolver");
        GraphqlFieldPolicy policy = new GraphqlFieldPolicy();
        return wiring -> wiring.type("Query", type -> {
            for (String field : ROOTS) {
                type.dataFetcher(field, env -> {
                    JwtAuthenticationToken actor = env.getGraphQlContext().get("actor");
                    policy.check(field, actor);
                    if (field.equals("dispatcherDashboard")) PaginationPolicy.from(env.getArgument("pagination"));
                    RootQueryResolver resolver = registered.get(field);
                    if (resolver == null) throw Status.UNAVAILABLE.asRuntimeException();
                    return resolver.resolve(env);
                });
            }
            return type;
        });
    }
}
