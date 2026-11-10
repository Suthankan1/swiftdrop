package com.swiftdrop.deliveryservice.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ParcelRouteTest {

    private static final String SNAPSHOT = """
            {"pickupZone":"ZONE_3","dropoffZone":"ZONE_1","pickupZoneName":"National / Island-wide",
             "dropoffZoneName":"Colombo Municipal District","weightKg":"2.50","sizeCategory":"MEDIUM",
             "dimensionsCm":"30 × 20 × 15"}
            """;

    @Test
    void readsBookedRouteFromQuoteSnapshot() {
        ParcelRoute route = ParcelRoute.fromSnapshot(SNAPSHOT);

        assertThat(route.pickupZoneName()).isEqualTo("National / Island-wide");
        assertThat(route.dropoffZoneName()).isEqualTo("Colombo Municipal District");
        assertThat(route.weightKg()).isEqualByComparingTo(new BigDecimal("2.5"));
        assertThat(route.dimensionsCm()).isEqualTo("30 × 20 × 15");
    }

    @Test
    void reportsOriginBeforeTransitAndDestinationAfter() {
        ParcelRoute route = ParcelRoute.fromSnapshot(SNAPSHOT);

        assertThat(route.locationFor(ParcelStatus.CREATED)).isEqualTo("National / Island-wide");
        assertThat(route.locationFor(ParcelStatus.PICKED_UP)).isEqualTo("National / Island-wide");
        assertThat(route.locationFor(ParcelStatus.IN_TRANSIT)).isEqualTo("En route to Colombo Municipal District");
        assertThat(route.locationFor(ParcelStatus.DELIVERED)).isEqualTo("Colombo Municipal District");
        assertThat(route.locationFor("FAILED")).isEqualTo("Colombo Municipal District");
    }

    @Test
    void unknownForOlderParcelsAndBadJsonInsteadOfInventingPlaces() {
        assertThat(ParcelRoute.fromSnapshot(null)).isEqualTo(ParcelRoute.UNKNOWN);
        assertThat(ParcelRoute.fromSnapshot("not json")).isEqualTo(ParcelRoute.UNKNOWN);
        assertThat(ParcelRoute.UNKNOWN.locationFor(ParcelStatus.CREATED)).isNull();
        assertThat(ParcelRoute.UNKNOWN.locationFor("NOT_A_STATUS")).isNull();
    }

    @Test
    void weightOfPrefersStoredWeightThenSnapshotThenZero() {
        Parcel stored = new Parcel();
        stored.setWeightKg(new BigDecimal("2.250"));
        stored.setQuoteSnapshot("{\"weightKg\": 9}");
        assertThat(ParcelRoute.weightOf(stored)).isEqualTo(2.25);

        Parcel snapshotOnly = new Parcel();
        snapshotOnly.setQuoteSnapshot("{\"weightKg\": 1.5}");
        assertThat(ParcelRoute.weightOf(snapshotOnly)).isEqualTo(1.5);

        assertThat(ParcelRoute.weightOf(new Parcel())).isZero();
        assertThat(ParcelRoute.weightOf(null)).isZero();
    }
}
