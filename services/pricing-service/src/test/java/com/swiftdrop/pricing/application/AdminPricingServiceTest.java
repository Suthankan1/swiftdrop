package com.swiftdrop.pricing.application;

import com.swiftdrop.pricing.api.dto.AdminPricingDtos.*;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.domain.SizeCategory;
import com.swiftdrop.pricing.error.AdminConfigurationException;
import com.swiftdrop.pricing.persistence.*;
import com.swiftdrop.pricing.security.CallerContext;
import com.swiftdrop.pricing.security.SecurityContextCallerResolver;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminPricingServiceTest {
    private final ZoneRepository zones = mock(ZoneRepository.class);
    private final RateVersionRepository versions = mock(RateVersionRepository.class);
    private final RateRuleRepository rules = mock(RateRuleRepository.class);
    private final PricingConfigurationLock lock = mock(PricingConfigurationLock.class);
    private final AdminAuditRepository audit = mock(AdminAuditRepository.class);
    private final SecurityContextCallerResolver caller = mock(SecurityContextCallerResolver.class);
    private final EntityManager em = mock(EntityManager.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Instant now = Instant.parse("2026-10-27T10:00:00Z");
    private AdminPricingService service;

    @BeforeEach
    void setup() {
        service = new AdminPricingService(zones, versions, rules, lock, audit, caller, em, jdbc,
                Validation.buildDefaultValidatorFactory().getValidator(), Clock.fixed(now, ZoneOffset.UTC));
        when(caller.resolveCurrentCaller()).thenReturn(CallerContext.authenticated("admin-123", null, Set.of("ADMIN")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "MERCHANT", "COURIER", "DISPATCHER"})
    void applicationBoundaryDeniesNonAdminsBeforeAccessingDatabase(String role) {
        when(caller.resolveCurrentCaller()).thenReturn(CallerContext.authenticated("actor", null, Set.of(role)));
        assertThatThrownBy(() -> service.createZone(new ZoneCreate("ZONE_4", "Name", "", 24))).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(zones, lock, audit);
    }

    @Test
    void adminAuthorityWithoutActorIdentityIsDenied() {
        when(caller.resolveCurrentCaller()).thenReturn(new CallerContext(" ", null, Set.of("ADMIN"), false));
        assertThatThrownBy(() -> service.listZones(0, 20)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(zones);
    }

    @Test
    void duplicateCodeIsConflictAndDoesNotAudit() {
        when(zones.existsByZoneCode("ZONE_1")).thenReturn(true);
        assertCode(() -> service.createZone(new ZoneCreate("ZONE_1", "Name", "", 24)), 409, "DUPLICATE_ZONE_CODE");
        verifyNoInteractions(audit);
    }

    @Test
    void blankLongOrInvalidZoneInputIsRejectedBeforeLock() {
        assertCode(() -> service.createZone(new ZoneCreate("", "Name", "", 24)), 400, "VALIDATION_FAILED");
        assertCode(() -> service.createZone(new ZoneCreate("ZONE_4", "x".repeat(129), "", 24)), 400, "VALIDATION_FAILED");
        assertCode(() -> service.createZone(new ZoneCreate("ZONE_4", "Name", "", -1)), 400, "VALIDATION_FAILED");
        verifyNoInteractions(lock, zones, audit);
    }

    @Test
    void missingZoneIs404AndStaleUpdateIs409() {
        UUID id = UUID.randomUUID();
        assertCode(() -> service.updateZone(id, new ZoneUpdate("Name", "", 24, 0L)), 404, "ZONE_NOT_FOUND");
        when(zones.findById(id)).thenReturn(Optional.of(new ZoneEntity(id, "ZONE_1", "Old", "", 24, true)));
        assertCode(() -> service.updateZone(id, new ZoneUpdate("Name", "", 24, 2L)), 409, "STALE_REVISION");
        verifyNoInteractions(audit);
    }

    @Test
    void updatesOnlyMetadataAndCapturesActorAndPriorNewValues() {
        UUID id = UUID.randomUUID();
        ZoneEntity zone = new ZoneEntity(id, "ZONE_1", "Old", "Old description", 24, true);
        when(zones.findById(id)).thenReturn(Optional.of(zone));
        ZoneView updated = service.updateZone(id, new ZoneUpdate("New", "New description", 48, 0L));
        assertThat(updated.zoneCode()).isEqualTo("ZONE_1");
        assertThat(updated.name()).isEqualTo("New");
        assertThat(updated.updatedAt()).isEqualTo(now);
        verify(audit).append(eq("admin-123"), eq("ZONE_UPDATED"), eq("ZONE"), eq(id), isNull(), eq("true"), eq("true"),
                eq(0L), eq(0L), any(ZoneView.class), eq(updated), eq(now), any(UUID.class));
        verify(zones, never()).delete(any());
    }

    @ParameterizedTest
    @EnumSource(value = RateVersionStatus.class, names = {"ACTIVE", "SUPERSEDED", "ARCHIVED"})
    void publishedRulesCannotBeChanged(RateVersionStatus status) {
        UUID id = UUID.randomUUID();
        when(versions.findById(id)).thenReturn(Optional.of(card(id, status, now.minusSeconds(60))));
        assertCode(() -> service.writeRule(id, null, rule(0)), 409, "RATE_VERSION_IMMUTABLE");
        verifyNoInteractions(rules, audit);
    }

    @Test
    void missingOrInactiveZonePreventsDraftRule() {
        UUID id = UUID.randomUUID();
        when(versions.findById(id)).thenReturn(Optional.of(card(id, RateVersionStatus.DRAFT, now)));
        assertCode(() -> service.writeRule(id, null, rule(0)), 422, "ZONE_UNAVAILABLE");
        verifyNoInteractions(audit);
    }

    @Test
    void duplicateAndOverlappingBandsHaveDistinctErrors() {
        UUID id = UUID.randomUUID();
        when(versions.findById(id)).thenReturn(Optional.of(card(id, RateVersionStatus.DRAFT, now)));
        when(zones.findByZoneCode(anyString())).thenReturn(Optional.of(new ZoneEntity("ZONE_1", "Zone", "", 24, true)));
        when(rules.findByRateVersionId(id)).thenReturn(List.of(new RateRuleEntity(UUID.randomUUID(), id, "ZONE_1", "ZONE_2",
                SizeCategory.MEDIUM, new BigDecimal("0.01"), new BigDecimal("5.00"), 1, 0, BigDecimal.ZERO)));
        assertCode(() -> service.writeRule(id, null, rule(0)), 409, "DUPLICATE_RATE_RULE");
        RuleWrite overlap = new RuleWrite(0L, "ZONE_1", "ZONE_2", SizeCategory.MEDIUM, new BigDecimal("1.00"),
                new BigDecimal("2.00"), 10L, 0L, BigDecimal.ZERO);
        assertCode(() -> service.writeRule(id, null, overlap), 422, "OVERLAPPING_RATE_RULE");
        verifyNoInteractions(audit);
    }

    @Test
    void activationRejectsStaleActiveCardAndFutureEffectiveDate() {
        UUID id = UUID.randomUUID();
        RateVersionEntity draft = card(id, RateVersionStatus.DRAFT, now.plusSeconds(60));
        when(versions.findById(id)).thenReturn(Optional.of(draft));
        when(versions.findFirstByCurrencyAndStatus("LKR", RateVersionStatus.ACTIVE))
                .thenReturn(Optional.of(card(UUID.randomUUID(), RateVersionStatus.ACTIVE, now.minusSeconds(60))));
        assertCode(() -> service.activate(id, new Activate(0L, null)), 409, "ACTIVE_VERSION_CHANGED");
        when(versions.findFirstByCurrencyAndStatus("LKR", RateVersionStatus.ACTIVE)).thenReturn(Optional.empty());
        assertCode(() -> service.activate(id, new Activate(0L, null)), 422, "RATE_NOT_EFFECTIVE");
        verifyNoInteractions(audit);
    }

    @Test
    void invalidIntervalAndIncompleteCardCannotPublish() {
        assertCode(() -> service.createDraft(new DraftCreate("V2", "LKR", now, now, "")), 422, "INVALID_EFFECTIVE_INTERVAL");
        UUID id = UUID.randomUUID();
        when(versions.findById(id)).thenReturn(Optional.of(card(id, RateVersionStatus.DRAFT, now)));
        assertCode(() -> service.activate(id, new Activate(0L, null)), 422, "INCOMPLETE_RATE_CARD");
        verifyNoInteractions(audit);
    }

    @Test
    void onlySupersededVersionsCanArchive() {
        UUID id = UUID.randomUUID();
        RateVersionEntity version = card(id, RateVersionStatus.ACTIVE, now.minusSeconds(60));
        when(versions.findById(id)).thenReturn(Optional.of(version));
        assertCode(() -> service.archive(id, new ExpectedRevision(0L)), 409, "INVALID_RATE_TRANSITION");
        version.setStatus(RateVersionStatus.SUPERSEDED);
        assertThat(service.archive(id, new ExpectedRevision(0L)).status()).isEqualTo(RateVersionStatus.ARCHIVED);
    }

    @Test
    void zoneDisableRejectsUnexpiredQuotesAndPreservesHistoricalRows() {
        UUID id = UUID.randomUUID();
        ZoneEntity zone = new ZoneEntity(id, "ZONE_2", "Zone", "", 24, true);
        when(zones.findById(id)).thenReturn(Optional.of(zone));
        when(jdbc.queryForObject(contains("pricing_rate_rules"), eq(Long.class), any(), any())).thenReturn(0L);
        when(jdbc.queryForObject(contains("pricing_quotes"), eq(Long.class), any(), any(), any())).thenReturn(1L);
        assertCode(() -> service.disableZone(id, new ExpectedRevision(0L)), 422, "ZONE_REFERENCED");
        assertThat(zone.isActive()).isTrue();
        verifyNoInteractions(audit);
        when(jdbc.queryForObject(contains("pricing_quotes"), eq(Long.class), any(), any(), any())).thenReturn(0L);
        assertThat(service.disableZone(id, new ExpectedRevision(0L)).active()).isFalse();
        verify(zones, never()).delete(any());
        verify(jdbc, never()).update(anyString());
    }

    private RuleWrite rule(long revision) {
        return new RuleWrite(revision, "ZONE_1", "ZONE_2", SizeCategory.MEDIUM, new BigDecimal("0.01"),
                new BigDecimal("5.00"), 35000L, 5000L, BigDecimal.ONE);
    }
    private RateVersionEntity card(UUID id, RateVersionStatus status, Instant from) {
        return new RateVersionEntity(id, "RATE-TEST", "LKR", status, from, "");
    }
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int status, String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(AdminConfigurationException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(status); assertThat(ex.getErrorCode()).isEqualTo(code);
        });
    }
}
