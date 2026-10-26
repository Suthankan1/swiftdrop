package com.swiftdrop.pricing.persistence;

import com.swiftdrop.pricing.domain.PricingZone;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "pricing_zones")
public class ZoneEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "zone_code", nullable = false, unique = true, length = 32)
    private String zoneCode;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "description", nullable = false, length = 512)
    private String description;

    @Column(name = "standard_transit_hours", nullable = false)
    private int standardTransitHours;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected ZoneEntity() {
    }

    public ZoneEntity(String zoneCode, String name, String description, int standardTransitHours, boolean active) {
        this(null, zoneCode, name, description, standardTransitHours, active);
    }

    public ZoneEntity(UUID id, String zoneCode, String name, String description, int standardTransitHours, boolean active) {
        this.id = id;
        this.zoneCode = zoneCode;
        this.name = name;
        this.description = description;
        this.standardTransitHours = standardTransitHours;
        this.active = active;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getZoneCode() {
        return zoneCode;
    }

    public void setZoneCode(String zoneCode) {
        this.zoneCode = zoneCode;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getStandardTransitHours() {
        return standardTransitHours;
    }

    public void setStandardTransitHours(int standardTransitHours) {
        this.standardTransitHours = standardTransitHours;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public PricingZone toDomain() {
        return new PricingZone(id, zoneCode, name, description, standardTransitHours, active);
    }
}
