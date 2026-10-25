package com.swiftdrop.pricing.domain;

import com.swiftdrop.pricing.error.RateVersionImmutableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateVersionTest {

    @Test
    @DisplayName("Should allow mutation when rate version is in DRAFT status")
    void shouldAllowMutationInDraftStatus() {
        RateVersion draft = new RateVersion(
                UUID.randomUUID(),
                "RATE-2026-DRAFT",
                "LKR",
                RateVersionStatus.DRAFT,
                Instant.now(),
                null,
                "Draft test card",
                Instant.now(),
                null,
                null
        );

        // Should not throw
        draft.ensureMutable();
        assertThat(draft.getStatus()).isEqualTo(RateVersionStatus.DRAFT);
    }

    @Test
    @DisplayName("Should prevent mutation when rate version is ACTIVE")
    void shouldPreventMutationWhenActive() {
        RateVersion active = new RateVersion(
                UUID.randomUUID(),
                "RATE-2026-V1",
                "LKR",
                RateVersionStatus.ACTIVE,
                Instant.now().minus(10, ChronoUnit.DAYS),
                null,
                "Active card",
                Instant.now(),
                Instant.now().minus(10, ChronoUnit.DAYS),
                null
        );

        assertThatThrownBy(active::ensureMutable)
                .isInstanceOf(RateVersionImmutableException.class)
                .hasMessageContaining("cannot be mutated");
    }

    @Test
    @DisplayName("Should prevent mutation when rate version is SUPERSEDED")
    void shouldPreventMutationWhenSuperseded() {
        RateVersion superseded = new RateVersion(
                UUID.randomUUID(),
                "RATE-2025-V1",
                "LKR",
                RateVersionStatus.SUPERSEDED,
                Instant.now().minus(30, ChronoUnit.DAYS),
                Instant.now().minus(1, ChronoUnit.DAYS),
                "Superseded card",
                Instant.now(),
                Instant.now().minus(30, ChronoUnit.DAYS),
                null
        );

        assertThatThrownBy(superseded::ensureMutable)
                .isInstanceOf(RateVersionImmutableException.class)
                .hasMessageContaining("cannot be mutated");
    }

    @Test
    @DisplayName("Should correctly transition from DRAFT to ACTIVE to SUPERSEDED")
    void shouldTransitionLifecycleCorrectly() {
        Instant now = Instant.now();
        RateVersion version = new RateVersion(
                UUID.randomUUID(),
                "RATE-2026-V2",
                "LKR",
                RateVersionStatus.DRAFT,
                now,
                null,
                "New version",
                now,
                null,
                null
        );

        version.activate(now);
        assertThat(version.getStatus()).isEqualTo(RateVersionStatus.ACTIVE);
        assertThat(version.isActive()).isTrue();

        Instant future = now.plus(30, ChronoUnit.DAYS);
        version.supersede(future);
        assertThat(version.getStatus()).isEqualTo(RateVersionStatus.SUPERSEDED);
        assertThat(version.isActive()).isFalse();
        assertThat(version.getEffectiveTo()).isEqualTo(future);
    }
}
