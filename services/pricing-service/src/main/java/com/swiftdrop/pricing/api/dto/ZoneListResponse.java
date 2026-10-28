package com.swiftdrop.pricing.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ZoneListResponse(
        @JsonProperty("activeRateVersion")
        String activeRateVersion,

        @JsonProperty("zones")
        List<ZoneItem> zones
) {
    public record ZoneItem(
            @JsonProperty("zoneCode")
            String zoneCode,

            @JsonProperty("name")
            String name,

            @JsonProperty("description")
            String description,

            @JsonProperty("standardTransitHours")
            int standardTransitHours
    ) {}
}
