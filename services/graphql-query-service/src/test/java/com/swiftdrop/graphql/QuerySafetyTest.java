package com.swiftdrop.graphql;

import com.swiftdrop.graphql.config.GraphqlRuntimeConfig;
import com.swiftdrop.graphql.execution.QueryContextInterceptor;
import com.swiftdrop.graphql.limits.PaginationPolicy;
import com.swiftdrop.graphql.security.GraphqlFieldPolicy;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;

class QuerySafetyTest {
    @Test void canonicalSchemaIsReadOnlyAndHasOnlyApprovedRoots() throws Exception {
        var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(
                Files.readString(Path.of("../../contracts/graphql/schema.graphql"))), RuntimeWiring.newRuntimeWiring().build());
        assertThat(schema.getMutationType()).isNull();
        assertThat(schema.getSubscriptionType()).isNull();
        assertThat(schema.getQueryType().getFieldDefinitions()).extracting(f -> f.getName())
                .containsExactlyInAnyOrderElementsOf(GraphqlRuntimeConfig.ROOTS);
    }
    private GraphQL guarded(AtomicInteger calls) {
        var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse(
                "type Query { root: Node } type Node { next: Node value: Int }"),
                RuntimeWiring.newRuntimeWiring().type("Query", t -> t.dataFetcher("root", env -> {
                    calls.incrementAndGet(); return Map.of("value", 1);
                })).build());
        var config = new GraphqlRuntimeConfig();
        return GraphQL.newGraphQL(schema).instrumentation(new graphql.execution.instrumentation.ChainedInstrumentation(
                List.of(config.depthLimit(), config.complexityLimit()))).build();
    }
    @Test void depthBoundaryAndNamedInlineFragments() {
        var calls = new AtomicInteger(); var graph = guarded(calls);
        assertThat(graph.execute("{root{next{next{next{value}}}}}").getErrors()).isEmpty();
        assertThat(graph.execute("{root{...F}} fragment F on Node {next{... on Node {next{next{next{value}}}}}}").getErrors()).isNotEmpty();
        assertThat(calls).hasValue(1);
    }
    @Test void complexityBoundaryAndAliasAttack() {
        var calls = new AtomicInteger(); var graph = guarded(calls);
        String fields = java.util.stream.IntStream.range(0, 99).mapToObj(i -> "a" + i + ":value").collect(java.util.stream.Collectors.joining(" "));
        assertThat(graph.execute("{root{" + fields + "}}").getErrors()).isEmpty();
        assertThat(graph.execute("{root{" + fields + " extra:value}}").getErrors()).isNotEmpty();
        assertThat(calls).hasValue(1);
    }
    @Test void invalidSyntaxCycleAndOperationSelectionCannotCallProvider() {
        var calls = new AtomicInteger(); var graph = guarded(calls);
        assertThat(graph.execute("{").getErrors()).isNotEmpty();
        assertThat(graph.execute("{root{...F}} fragment F on Node {next{...F}}").getErrors()).isNotEmpty();
        assertThat(graph.execute(ExecutionInput.newExecutionInput("query Small{root{value}} query Large{root{next{next{next{next{value}}}}}}")
                .operationName("Small").build()).getErrors()).isEmpty();
        assertThat(calls).hasValue(1);
    }
    @Test void paginationDefaultsBoundaryAndInvalidValues() {
        assertThat(PaginationPolicy.from(null)).isEqualTo(new PaginationPolicy(0, 20));
        assertThat(new PaginationPolicy(0, 50).size()).isEqualTo(50);
        for (int size : new int[]{-1, 0, 51}) assertThatIllegalArgumentException().isThrownBy(() -> new PaginationPolicy(0, size));
        assertThatIllegalArgumentException().isThrownBy(() -> new PaginationPolicy(-1, 20));
    }
    @Test void correlationIsSanitized() {
        assertThat(QueryContextInterceptor.sanitize("safe_123")).isEqualTo("safe_123");
        for (String text : new String[]{"bad\r\nvalue", "x".repeat(65), ""})
            assertThat(QueryContextInterceptor.sanitize(text)).matches("[A-Za-z0-9_-]{1,64}");
    }
    @Test void rolesAreIndependentlyCheckedAndUnknownFieldsDenied() {
        var policy = new GraphqlFieldPolicy();
        var jwt = Jwt.withTokenValue("test-only").header("alg", "RS256").subject("caller").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var customer = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        policy.check("customerDashboard", customer);
        assertThatThrownBy(() -> policy.check("adminDashboard", customer)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> policy.check("unknown", customer)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> policy.check("customerDashboard", null)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void resultBoundsRejectOversizedListsAndStrings() {
        var wiring = RuntimeWiring.newRuntimeWiring().type("Query", type -> type.dataFetcher("values", env ->
                env.getArgument("largeList").equals(true) ? java.util.Collections.nCopies(51, "item") : List.of("x".repeat(4097)))).build();
        var schema = new SchemaGenerator().makeExecutableSchema(new SchemaParser().parse("type Query {values(largeList:Boolean!):[String!]!}"), wiring);
        var graph = GraphQL.newGraphQL(schema).instrumentation(new com.swiftdrop.graphql.limits.ResultBoundsInstrumentation()).build();
        for (boolean large : List.of(true, false)) {
            var result = graph.execute("{values(largeList:" + large + ")}");
            assertThat(result.getErrors()).isNotEmpty();
            assertThat((Object) result.getData()).isNull();
        }
    }
}
