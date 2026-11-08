package com.swiftdrop.graphql.resolver.admin;

import com.google.protobuf.Timestamp;
import com.swiftdrop.delivery.v1.GetDeliveryStatsRequest;
import com.swiftdrop.delivery.v1.GetDeliveryStatsResponse;
import com.swiftdrop.graphql.client.AuthenticatedReadClients;
import com.swiftdrop.graphql.resolver.RootQueryResolver;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Status;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Read-only delivery outcome report for dispatchers and admins: counts and average delivery time for a
 * date range in Asia/Colombo calendar days. Figures come only from Delivery Operations; nothing is invented.
 */
@Component
public class DeliveryStatsResolver implements RootQueryResolver {

    static final int MAX_RANGE_DAYS = 90;
    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    private final AuthenticatedReadClients deliveryClient;
    private final Clock clock;

    @Autowired
    public DeliveryStatsResolver(
            @Autowired(required = false) @Qualifier("deliveryReadClients") AuthenticatedReadClients deliveryClient) {
        this(deliveryClient, Clock.system(COLOMBO));
    }

    DeliveryStatsResolver(AuthenticatedReadClients deliveryClient, Clock clock) {
        this.deliveryClient = deliveryClient;
        this.clock = clock;
    }

    @Override
    public String field() {
        return "deliveryStats";
    }

    @Override
    public Object resolve(DataFetchingEnvironment environment) {
        JwtAuthenticationToken actor = environment.getGraphQlContext().get("actor");
        if (actor == null || !actor.isAuthenticated()) {
            throw Status.UNAUTHENTICATED.withDescription("Unauthenticated caller").asRuntimeException();
        }
        if (deliveryClient == null) {
            throw Status.UNAVAILABLE.withDescription("Delivery provider unavailable").asRuntimeException();
        }

        Map<String, Object> filter = environment.getArgument("filter");
        LocalDate today = LocalDate.now(clock.withZone(COLOMBO));
        LocalDate start = parseDate(filter, "startDate", today);
        LocalDate end = parseDate(filter, "endDate", today);
        if (end.isBefore(start)) {
            throw Status.INVALID_ARGUMENT.withDescription("endDate must not be before startDate").asRuntimeException();
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_RANGE_DAYS) {
            throw Status.INVALID_ARGUMENT.withDescription("Range is limited to " + MAX_RANGE_DAYS + " days")
                    .asRuntimeException();
        }
        Object zone = filter == null ? null : filter.get("zone");

        Instant from = start.atStartOfDay(COLOMBO).toInstant();
        Instant to = end.plusDays(1).atStartOfDay(COLOMBO).toInstant();
        GetDeliveryStatsRequest.Builder request = GetDeliveryStatsRequest.newBuilder()
                .setStartTime(timestamp(from))
                .setEndTime(timestamp(to));
        if (zone instanceof String z && !z.isBlank()) {
            request.setZone(z.trim());
        }
        GetDeliveryStatsRequest built = request.build();

        GetDeliveryStatsResponse response = deliveryClient.delivery(
                environment.getGraphQlContext(), stub -> stub.getDeliveryStatsInternal(built));
        if (response == null) {
            throw Status.UNAVAILABLE.withDescription("Delivery provider returned no data").asRuntimeException();
        }

        return new DeliveryStatsSummary(
                toInt(response.getTotalCount()),
                toInt(response.getDeliveredCount()),
                toInt(response.getFailedCount()),
                toInt(response.getInTransitCount()),
                toInt(response.getCreatedCount()),
                response.hasAverageDeliveryHours() ? response.getAverageDeliveryHours() : null);
    }

    private static LocalDate parseDate(Map<String, Object> filter, String key, LocalDate fallback) {
        Object value = filter == null ? null : filter.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException ex) {
            throw Status.INVALID_ARGUMENT.withDescription(key + " must be YYYY-MM-DD").asRuntimeException();
        }
    }

    private static int toInt(long value) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw Status.INTERNAL.withDescription("Delivery provider returned an out-of-range count").asRuntimeException();
        }
        return (int) value;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    /** Matches the SDL type DeliveryStatsSummary. */
    public record DeliveryStatsSummary(int totalCount, int deliveredCount, int failedCount, int inTransitCount,
                                       int createdCount, Double averageDeliveryHours) {
    }
}
