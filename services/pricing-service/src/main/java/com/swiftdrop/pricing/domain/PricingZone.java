package com.swiftdrop.pricing.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Domain representation of an operational delivery zone.
 */
public final class PricingZone {

    private final UUID id;
    private final String zoneCode;
    private final String name;
    private final String description;
    private final int standardTransitHours;
    private final boolean active;

    public PricingZone(UUID id, String zoneCode, String name, String description, int standardTransitHours, boolean active) {
        this.id = id;
        this.zoneCode = Objects.requireNonNull(zoneCode, "zoneCode cannot be null");
        this.name = Objects.requireNonNull(name, "name cannot be null");
        this.description = Objects.requireNonNull(description, "description cannot be null");
        if (standardTransitHours <= 0) {
            throw new IllegalArgumentException("standardTransitHours must be positive: " + standardTransitHours);
        }
        this.standardTransitHours = standardTransitHours;
        this.active = active;
    }

    public UUID getId() {
        return id;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public int getStandardTransitHours() {
        return standardTransitHours;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PricingZone that = (PricingZone) o;
        return Objects.equals(zoneCode, that.zoneCode);
    }

    @Override
    public int hashCode() {
        return Objects.hash(zoneCode);
    }

    @Override
    public String toString() {
        return "PricingZone{" +
                "zoneCode='" + zoneCode + '\'' +
                ", name='" + name + '\'' +
                ", active=" + active +
                ", standardTransitHours=" + standardTransitHours +
                '}';
    }
}
