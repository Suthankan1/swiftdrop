package com.swiftdrop.graphql.capacity;

import com.google.protobuf.Timestamp;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.swiftdrop.accounts.v1.*;
import com.swiftdrop.delivery.v1.*;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.graphql.client.TestClients;
import com.swiftdrop.security.jwt.AudienceValidator;
import com.swiftdrop.security.jwt.SubjectValidator;
import io.grpc.Context;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Issue #165 (N10-QA-08): gRPC fan-out, N+1 and concurrent tenant-isolation regression for the
 * customer, merchant and dispatcher dashboards.
 *
 * <p>Runs the real resolvers, security chain and GraphQL instrumentation against in-process gRPC
 * providers serving a deterministic 120-parcel, three-tenant synthetic dataset. A counting server
 * interceptor records every provider call and its propagated deadline. Latencies recorded here are
 * in-process (MockMvc + in-process transport) and are NOT deployment capacity evidence.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "logging.level.root=WARN",
        "swiftdrop.graphql.dispatcher.courier-max-capacity=5"
})
@AutoConfigureMockMvc
@Import(DashboardGrpcFanoutCapacityTest.Providers.class)
class DashboardGrpcFanoutCapacityTest {

    static final RSAKey KEY = generateKey();
    static final String ISSUER = "https://issuer.test";
    static final String AUDIENCE = "swiftdrop-api";
    static final String ZONE = "CMB-01";
    static final int PARCELS = 120;
    static final List<String> CUSTOMERS = List.of("cap-customer-01", "cap-customer-02", "cap-customer-03", "cap-customer-04");
    static final List<String> MERCHANTS = List.of("cap-merchant-a", "cap-merchant-b", "cap-merchant-c");
    static final String EMPTY_CUSTOMER = "cap-customer-empty";
    static final String DISPATCHER = "cap-dispatcher-cmb01";
    static final String[] STATUSES = {"CREATED", "PICKED_UP", "IN_TRANSIT", "OUT_FOR_DELIVERY", "DELIVERED", "FAILED"};
    static final List<Fixture> DATASET = buildDataset();
    static final Pattern TRACKING = Pattern.compile("\"trackingNumber\":\"(SWD-[^\"]+)\"");

    static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    static final List<Long> DEADLINE_REMAINING_MS = new CopyOnWriteArrayList<>();
    static final AtomicInteger COURIER_COUNT = new AtomicInteger(3);
    static final List<Integer> WORKLOAD_BATCH_SIZES = new CopyOnWriteArrayList<>();
    private static final List<Server> SERVERS = new CopyOnWriteArrayList<>();

    record Fixture(String parcelId, String trackingNumber, String owner, boolean merchantOwned, String status, String zone) {
        boolean terminal() { return status.equals("DELIVERED") || status.equals("FAILED"); }
    }

    @Autowired
    private MockMvc mvc;

    // ------------------------------------------------------------------ fixture

    private static List<Fixture> buildDataset() {
        List<String> owners = new ArrayList<>(CUSTOMERS);
        owners.addAll(MERCHANTS);
        List<Fixture> parcels = new ArrayList<>();
        for (int i = 0; i < PARCELS; i++) {
            String owner = owners.get(i % owners.size());
            parcels.add(new Fixture(
                    UUID.nameUUIDFromBytes(("capacity-parcel-" + i).getBytes(StandardCharsets.UTF_8)).toString(),
                    String.format("SWD-20261106-C%05d", i),
                    owner, owner.startsWith("cap-merchant"),
                    STATUSES[(i / owners.size() + i) % STATUSES.length],
                    i % 2 == 0 ? ZONE : "GMP-01"));
        }
        return List.copyOf(parcels);
    }

    static List<Fixture> ownedBy(String owner) {
        return DATASET.stream().filter(p -> p.owner().equals(owner)).toList();
    }

    static List<Fixture> unassignedIn(String zone) {
        return DATASET.stream().filter(p -> p.status().equals("CREATED") && (zone.isBlank() || p.zone().equals(zone))).toList();
    }

    static Timestamp ts(long offset) {
        return Timestamp.newBuilder().setSeconds(1_793_000_000L + offset).build();
    }

    static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Counts every provider call by bare method name and records the propagated deadline. */
    static final ServerInterceptor COUNTING = new ServerInterceptor() {
        @Override
        public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
            String method = MethodDescriptor.extractBareMethodName(call.getMethodDescriptor().getFullMethodName());
            CALLS.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();
            Deadline deadline = Context.current().getDeadline();
            DEADLINE_REMAINING_MS.add(deadline == null ? -1L : deadline.timeRemaining(TimeUnit.MILLISECONDS));
            return next.startCall(call, headers);
        }
    };

    @TestConfiguration(proxyBeanMethods = false)
    static class Providers {

        @Bean(destroyMethod = "close")
        AuthenticatedReadClients accountsReadClients() throws Exception {
            var service = new AccountsInternalServiceGrpc.AccountsInternalServiceImplBase() {
                @Override
                public void checkAccountEligibility(AccountEligibilityRequest req, StreamObserver<AccountEligibilityResponse> out) {
                    var res = AccountEligibilityResponse.newBuilder().setSubject(req.getSubject()).setIsEligible(true).setIsActive(true);
                    if (req.getRequiredRole().equals("MERCHANT")) {
                        res.setStatus("APPROVED").setMerchantId("tenant-" + req.getSubject()).setBusinessName("Synthetic " + req.getSubject());
                    } else {
                        res.setStatus("ACTIVE");
                    }
                    out.onNext(res.build());
                    out.onCompleted();
                }

                @Override
                public void getScopedProfileSummary(ScopedProfileSummaryRequest req, StreamObserver<ScopedProfileSummaryResponse> out) {
                    out.onNext(ScopedProfileSummaryResponse.newBuilder().setSubject(req.getSubject()).setRole("DISPATCHER")
                            .setAssignedZone(ZONE).setStatus("ACTIVE").build());
                    out.onCompleted();
                }

                @Override
                public void getAvailableCouriersSummary(AvailableCouriersSummaryRequest req, StreamObserver<AvailableCouriersSummaryResponse> out) {
                    int count = Math.min(COURIER_COUNT.get(), req.getPageSize());
                    var res = AvailableCouriersSummaryResponse.newBuilder().setTotalCount(count).setPage(0).setPageSize(req.getPageSize());
                    for (int i = 0; i < count; i++) {
                        res.addItems(CourierSummaryItem.newBuilder().setCourierSub("cap-courier-" + i).setFullName("Synthetic Courier " + i)
                                .setStaffId("STF-" + i).setAssignedZone(ZONE).setIsActive(true).setIsEligible(true));
                    }
                    out.onNext(res.build());
                    out.onCompleted();
                }
            };
            return start(ServerInterceptors.intercept(service, COUNTING));
        }

        @Bean(destroyMethod = "close")
        AuthenticatedReadClients deliveryReadClients() throws Exception {
            var service = new DeliveryInternalServiceGrpc.DeliveryInternalServiceImplBase() {
                @Override
                public void listCustomerParcels(ListCustomerParcelsRequest req, StreamObserver<ListCustomerParcelsResponse> out) {
                    List<Fixture> owned = ownedBy(req.getCustomerSub());
                    var res = ListCustomerParcelsResponse.newBuilder().setPage(req.getPage()).setPageSize(req.getPageSize())
                            .setTotalCount(owned.size())
                            .setActiveCount((int) owned.stream().filter(p -> !p.terminal()).count())
                            .setCompletedCount((int) owned.stream().filter(Fixture::terminal).count());
                    owned.stream().limit(req.getPageSize()).forEach(p -> res.addParcels(CustomerParcelSummary.newBuilder()
                            .setParcelId(p.parcelId()).setTrackingNumber(p.trackingNumber()).setCurrentStatus(p.status())
                            .setAmountMinorUnits(45000).setCurrency("LKR").setRecipientCity("Colombo")
                            .setCreatedAt(ts(0)).setUpdatedAt(ts(60)).setVersion(1)));
                    out.onNext(res.build());
                    out.onCompleted();
                }

                @Override
                public void listMerchantOrders(ListMerchantOrdersRequest req, StreamObserver<ListMerchantOrdersResponse> out) {
                    List<Fixture> owned = ownedBy(req.getMerchantId().replaceFirst("^tenant-", ""));
                    var res = ListMerchantOrdersResponse.newBuilder().setPage(req.getPage()).setPageSize(req.getPageSize())
                            .setTotalCount(owned.size())
                            .setActiveCount((int) owned.stream().filter(p -> !p.terminal()).count())
                            .setDeliveredCount((int) owned.stream().filter(p -> p.status().equals("DELIVERED")).count())
                            .setFailedCount((int) owned.stream().filter(p -> p.status().equals("FAILED")).count());
                    owned.stream().limit(req.getPageSize()).forEach(p -> res.addOrders(MerchantOrderSummary.newBuilder()
                            .setParcelId(p.parcelId()).setTrackingNumber(p.trackingNumber()).setCurrentStatus(p.status())
                            .setWeightKg(1.5).setFeeMinorUnits(45000).setCurrency("LKR").setCreatedAt(ts(0)).setLastStatusUpdate(ts(60))));
                    out.onNext(res.build());
                    out.onCompleted();
                }

                @Override
                public void getCourierWorkloadSummary(CourierWorkloadSummaryRequest req, StreamObserver<CourierWorkloadSummaryResponse> out) {
                    WORKLOAD_BATCH_SIZES.add(req.getCourierSubsCount());
                    var res = CourierWorkloadSummaryResponse.newBuilder();
                    req.getCourierSubsList().forEach(sub -> res.addWorkloads(CourierWorkloadResponse.newBuilder()
                            .setCourierSub(sub).setActiveAssignmentsCount(1)));
                    out.onNext(res.build());
                    out.onCompleted();
                }

                @Override
                public void listUnassignedParcels(ListUnassignedParcelsRequest req, StreamObserver<ListUnassignedParcelsResponse> out) {
                    List<Fixture> queue = unassignedIn(req.getZone());
                    var res = ListUnassignedParcelsResponse.newBuilder().setTotalCount(queue.size())
                            .setPage(req.getPage()).setPageSize(req.getPageSize());
                    queue.stream().skip((long) req.getPage() * req.getPageSize()).limit(req.getPageSize())
                            .forEach(p -> res.addItems(UnassignedParcelSummary.newBuilder().setParcelId(p.parcelId())
                                    .setTrackingNumber(p.trackingNumber()).setStatus("CREATED").setVersion(1).setWeightKg(2.0)
                                    .setPickupZone(p.zone()).setDropoffZone(p.zone()).setSizeCategory("MEDIUM").setCreatedAt(ts(0))));
                    out.onNext(res.build());
                    out.onCompleted();
                }
            };
            return start(ServerInterceptors.intercept(service, COUNTING));
        }

        private static AuthenticatedReadClients start(io.grpc.ServerServiceDefinition service) throws Exception {
            String name = InProcessServerBuilder.generateName();
            SERVERS.add(InProcessServerBuilder.forName(name).executor(Executors.newFixedThreadPool(8)).addService(service).build().start());
            return TestClients.inProcess(InProcessChannelBuilder.forName(name).build(), () -> "synthetic-service-token");
        }

        @Bean
        JwtDecoder jwtDecoder() throws Exception {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER),
                    new AudienceValidator(AUDIENCE), new SubjectValidator()));
            return decoder;
        }
    }

    @AfterAll
    static void stopServers() {
        SERVERS.forEach(Server::shutdownNow);
    }

    @BeforeEach
    void reset() {
        CALLS.clear();
        DEADLINE_REMAINING_MS.clear();
        WORKLOAD_BATCH_SIZES.clear();
        COURIER_COUNT.set(3);
    }

    // ------------------------------------------------------------------ helpers

    static String token(String sub, String role) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder().subject(sub).issuer(ISSUER).audience(AUDIENCE)
                .issueTime(Date.from(Instant.now().minusSeconds(60))).expirationTime(Date.from(Instant.now().plusSeconds(600)))
                .claim("realm_access", Map.of("roles", List.of(role))).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(KEY));
        return jwt.serialize();
    }

    String query(String token, String query) throws Exception {
        MvcResult result = mvc.perform(post("/graphql").contentType("application/json")
                .header("Authorization", "Bearer " + token)
                .content("{\"query\":\"" + query + "\"}")).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString();
    }

    static Set<String> tracking(String body) {
        Set<String> found = new HashSet<>();
        Matcher m = TRACKING.matcher(body);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    static int totalCalls() {
        return CALLS.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    static Map<String, Integer> callsSnapshot() {
        Map<String, Integer> snapshot = new TreeMap<>();
        CALLS.forEach((k, v) -> snapshot.put(k, v.get()));
        return snapshot;
    }

    static final String CUSTOMER_Q = "{ customerDashboard { userId activeDeliveriesCount completedDeliveriesCount recentParcels { parcelId trackingNumber currentStatus } } }";
    static final String MERCHANT_Q = "{ merchantDashboard { merchantId totalOrdersCount recentOrders { parcelId trackingNumber currentStatus } } }";

    static String dispatcherQ(int page, int size) {
        return "{ dispatcherDashboard(pagination: {page: " + page + ", size: " + size + "}) { unassignedParcels { parcelId trackingNumber pickupZone } "
                + "pageInfo { currentPage pageSize totalElements totalPages hasNext } availableCouriers { courierSub activeAssignmentsCount } } }";
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("Synthetic dataset meets issue #165 minimums: >=100 parcels, multiple tenants and all statuses")
    void datasetMeetsIssueMinimums() {
        assertThat(DATASET).hasSizeGreaterThanOrEqualTo(100);
        assertThat(MERCHANTS.stream().filter(m -> !ownedBy(m).isEmpty()).count()).isGreaterThanOrEqualTo(2);
        assertThat(DATASET.stream().map(Fixture::status).collect(Collectors.toSet())).containsExactlyInAnyOrder(STATUSES);
        assertThat(DATASET.stream().map(Fixture::trackingNumber).distinct()).hasSize(DATASET.size());
    }

    @Test
    @DisplayName("customerDashboard issues exactly one Delivery call regardless of the customer's dataset size")
    void customerDashboardFanoutIsConstant() throws Exception {
        for (String sub : List.of(CUSTOMERS.get(0), EMPTY_CUSTOMER)) {
            CALLS.clear();
            String body = query(token(sub, "CUSTOMER"), CUSTOMER_Q);
            assertThat(body).doesNotContain("\"errors\"");
            assertThat(callsSnapshot()).isEqualTo(Map.of("ListCustomerParcels", 1));
            assertThat(tracking(body)).isSubsetOf(ownedBy(sub).stream().map(Fixture::trackingNumber).toList());
        }
    }

    @Test
    @DisplayName("merchantDashboard issues one Accounts and one Delivery call per query for every tenant")
    void merchantDashboardFanoutIsConstant() throws Exception {
        for (String merchant : MERCHANTS) {
            CALLS.clear();
            String body = query(token(merchant, "MERCHANT"), MERCHANT_Q);
            assertThat(body).doesNotContain("\"errors\"");
            assertThat(callsSnapshot()).isEqualTo(Map.of("CheckAccountEligibility", 1, "ListMerchantOrders", 1));
        }
    }

    @Test
    @DisplayName("dispatcherDashboard fan-out does not scale with page size or courier count (no N+1)")
    void dispatcherDashboardHasNoNPlusOne() throws Exception {
        String dispatcherToken = token(DISPATCHER, "DISPATCHER");
        Map<String, Integer> baseline = null;
        for (int couriers : new int[]{1, 3, 40}) {
            for (int size : new int[]{1, 20, 50}) {
                COURIER_COUNT.set(couriers);
                CALLS.clear();
                WORKLOAD_BATCH_SIZES.clear();
                String body = query(dispatcherToken, dispatcherQ(0, size));
                assertThat(body).doesNotContain("\"errors\"");
                Map<String, Integer> calls = callsSnapshot();
                if (baseline == null) baseline = calls;
                assertThat(calls).as("couriers=%d size=%d", couriers, size).isEqualTo(baseline);
                assertThat(WORKLOAD_BATCH_SIZES).as("single batched workload read").containsExactly(couriers);
            }
        }
        assertThat(baseline).isEqualTo(Map.of("CheckAccountEligibility", 1, "GetScopedProfileSummary", 1,
                "GetAvailableCouriersSummary", 1, "GetCourierWorkloadSummary", 1, "ListUnassignedParcels", 1));
    }

    @Test
    @DisplayName("Paging through the full dispatcher queue costs a constant number of calls per page")
    void dispatcherPaginationIsBoundedPerPage() throws Exception {
        String dispatcherToken = token(DISPATCHER, "DISPATCHER");
        int size = 7;
        int expectedPages = (int) Math.ceil(unassignedIn(ZONE).size() / (double) size);
        Set<String> seen = new HashSet<>();
        for (int page = 0; page <= expectedPages; page++) {
            CALLS.clear();
            String body = query(dispatcherToken, dispatcherQ(page, size));
            assertThat(totalCalls()).isEqualTo(5);
            Set<String> pageItems = tracking(body);
            assertThat(pageItems.size()).isLessThanOrEqualTo(size);
            assertThat(Collections.disjoint(seen, pageItems)).as("page %d overlaps", page).isTrue();
            seen.addAll(pageItems);
            assertThat(body).contains("\"currentPage\":" + page).contains("\"totalPages\":" + expectedPages);
            if (page == expectedPages) assertThat(pageItems).as("page past the end").isEmpty();
        }
        // Exactly the zone-scoped queue: nothing from GMP-01, nothing duplicated, nothing missing.
        assertThat(seen).isEqualTo(unassignedIn(ZONE).stream().map(Fixture::trackingNumber).collect(Collectors.toSet()));
    }

    @Test
    @DisplayName("Oversized pages and complexity attacks are rejected before any provider call")
    void limitsRejectBeforeProviderCalls() throws Exception {
        String body = query(token(DISPATCHER, "DISPATCHER"), dispatcherQ(0, 51));
        assertThat(body).contains("\"errors\"");
        String aliases = IntStream.range(0, 101).mapToObj(i -> "a" + i + ": activeDeliveriesCount").collect(Collectors.joining(" "));
        body = query(token(CUSTOMERS.get(0), "CUSTOMER"), "{ customerDashboard { " + aliases + " } }");
        assertThat(body).contains("\"errors\"");
        assertThat(totalCalls()).isZero();
    }

    @Test
    @DisplayName("Every provider call carries the approved 2000ms gRPC deadline")
    void everyProviderCallHasBoundedDeadline() throws Exception {
        query(token(CUSTOMERS.get(0), "CUSTOMER"), CUSTOMER_Q);
        query(token(MERCHANTS.get(0), "MERCHANT"), MERCHANT_Q);
        query(token(DISPATCHER, "DISPATCHER"), dispatcherQ(0, 20));
        assertThat(DEADLINE_REMAINING_MS).hasSize(1 + 2 + 5).allSatisfy(ms -> assertThat(ms).isBetween(1L, 2000L));
    }

    @Test
    @DisplayName("Concurrent customers and tenants never see each other's parcels and fan-out stays constant")
    void concurrentTenantIsolationAndFanout() throws Exception {
        record Call(String sub, String role, String query) {}
        List<Call> calls = new ArrayList<>();
        for (int round = 0; round < 8; round++) {
            CUSTOMERS.forEach(c -> calls.add(new Call(c, "CUSTOMER", CUSTOMER_Q)));
            MERCHANTS.forEach(m -> calls.add(new Call(m, "MERCHANT", MERCHANT_Q)));
            calls.add(new Call(DISPATCHER, "DISPATCHER", dispatcherQ(round % 3, 20)));
        }
        Map<String, String> tokens = new ConcurrentHashMap<>();
        for (Call c : calls) tokens.computeIfAbsent(c.sub(), s -> {
            try { return token(s, c.role()); } catch (Exception e) { throw new IllegalStateException(e); }
        });
        List<Double> latenciesMs = new CopyOnWriteArrayList<>();
        AtomicInteger violations = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Call c : calls) {
                futures.add(pool.submit(() -> {
                    long start = System.nanoTime();
                    String body = query(tokens.get(c.sub()), c.query());
                    latenciesMs.add((System.nanoTime() - start) / 1_000_000.0);
                    assertThat(body).doesNotContain("\"errors\"");
                    Set<String> allowed = c.role().equals("DISPATCHER")
                            ? unassignedIn(ZONE).stream().map(Fixture::trackingNumber).collect(Collectors.toSet())
                            : ownedBy(c.sub()).stream().map(Fixture::trackingNumber).collect(Collectors.toSet());
                    if (!allowed.containsAll(tracking(body))) violations.incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        int expectedCalls = 8 * (CUSTOMERS.size() * 1 + MERCHANTS.size() * 2 + 5);
        assertThat(violations.get()).as("cross-tenant records observed").isZero();
        assertThat(totalCalls()).as("no extra fan-out under concurrency").isEqualTo(expectedCalls);
        writeEvidence(calls.size(), expectedCalls, latenciesMs, violations.get());
    }

    private static void writeEvidence(int queries, int grpcCalls, List<Double> latencies, int violations) throws Exception {
        List<Double> sorted = latencies.stream().sorted().toList();
        String json = "{\n"
                + "  \"issue\": \"#165\",\n"
                + "  \"evidence_class\": \"MEASURED_IN_PROCESS\",\n"
                + "  \"note\": \"MockMvc + in-process gRPC; validates fan-out and isolation, not deployment capacity\",\n"
                + "  \"dataset\": {\"parcels\": " + DATASET.size() + ", \"merchant_tenants\": " + MERCHANTS.size()
                + ", \"customers\": " + CUSTOMERS.size() + "},\n"
                + "  \"grpc_calls_per_query\": {\"customerDashboard\": 1, \"merchantDashboard\": 2, \"dispatcherDashboard\": 5},\n"
                + "  \"concurrent\": {\"queries\": " + queries + ", \"threads\": 16, \"grpc_calls\": " + grpcCalls
                + ", \"isolation_violations\": " + violations + ",\n"
                + "    \"latency_ms\": {\"p50\": " + pct(sorted, 50) + ", \"p95\": " + pct(sorted, 95) + ", \"p99\": " + pct(sorted, 99) + "}}\n"
                + "}\n";
        Path out = Path.of("target", "capacity", "graphql-grpc-fanout.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);
    }

    private static String pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return "null";
        int rank = Math.max(1, (int) Math.ceil(p / 100.0 * sorted.size()));
        return String.format(java.util.Locale.ROOT, "%.3f", sorted.get(rank - 1));
    }
}
