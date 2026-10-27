package com.swiftdrop.pricing.application;

import com.swiftdrop.pricing.api.dto.AdminPricingDtos.*;
import com.swiftdrop.pricing.domain.*;
import com.swiftdrop.pricing.error.AdminConfigurationException;
import com.swiftdrop.pricing.error.AmbiguousRateRuleException;
import com.swiftdrop.pricing.persistence.*;
import com.swiftdrop.pricing.security.CallerContext;
import com.swiftdrop.pricing.security.SecurityContextCallerResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
@Transactional
public class AdminPricingService {
    private final ZoneRepository zones;
    private final RateVersionRepository versions;
    private final RateRuleRepository rules;
    private final PricingConfigurationLock lock;
    private final AdminAuditRepository audit;
    private final SecurityContextCallerResolver caller;
    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final Validator validator;
    private final Clock clock;
    private final DraftRateRuleValidator ruleValidator = new DraftRateRuleValidator();

    public AdminPricingService(ZoneRepository zones, RateVersionRepository versions, RateRuleRepository rules,
                               PricingConfigurationLock lock, AdminAuditRepository audit,
                               SecurityContextCallerResolver caller, EntityManager em, JdbcTemplate jdbc,
                               Validator validator, Clock clock) {
        this.zones = zones; this.versions = versions; this.rules = rules; this.lock = lock; this.audit = audit;
        this.caller = caller; this.em = em; this.jdbc = jdbc; this.validator = validator; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageView<ZoneView> listZones(int page, int size) {
        admin();
        Page<ZoneEntity> result = zones.findAll(page(page, size, "zoneCode"));
        return new PageView<>(result.map(ZoneView::from).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ZoneView getZone(UUID id) { admin(); return ZoneView.from(zone(id)); }

    public ZoneView createZone(ZoneCreate input) {
        String actor = command(input);
        if (zones.existsByZoneCode(input.zoneCode())) throw conflict("DUPLICATE_ZONE_CODE", "Zone code already exists");
        ZoneEntity zone = new ZoneEntity(input.zoneCode(), input.name().trim(), input.description(), input.standardTransitHours(), true);
        zone.setUpdatedAt(clock.instant());
        zones.saveAndFlush(zone);
        ZoneView view = ZoneView.from(zone);
        auditZone(actor, "ZONE_CREATED", null, view);
        return view;
    }

    public ZoneView updateZone(UUID id, ZoneUpdate input) {
        String actor = command(input);
        ZoneEntity zone = zone(id);
        checkRevision(zone.getRevision(), input.expectedRevision());
        ZoneView before = ZoneView.from(zone);
        zone.setName(input.name().trim()); zone.setDescription(input.description());
        zone.setStandardTransitHours(input.standardTransitHours()); zone.setUpdatedAt(clock.instant());
        zones.flush();
        ZoneView after = ZoneView.from(zone);
        auditZone(actor, "ZONE_UPDATED", before, after);
        return after;
    }

    public ZoneView disableZone(UUID id, ExpectedRevision input) {
        String actor = command(input);
        ZoneEntity zone = zone(id);
        checkRevision(zone.getRevision(), input.expectedRevision());
        if (!zone.isActive()) throw conflict("ZONE_ALREADY_DISABLED", "Zone is already disabled");
        Long activeRules = jdbc.queryForObject("""
                SELECT count(*) FROM pricing_rate_rules r JOIN pricing_rate_versions v ON v.id = r.rate_version_id
                WHERE v.status = 'ACTIVE' AND (r.pickup_zone_code = ? OR r.dropoff_zone_code = ?)
                """, Long.class, zone.getZoneCode(), zone.getZoneCode());
        Long unexpiredQuotes = jdbc.queryForObject("""
                SELECT count(*) FROM pricing_quotes WHERE expires_at > ?
                AND (pickup_zone_code = ? OR dropoff_zone_code = ?)
                """, Long.class, java.sql.Timestamp.from(clock.instant()), zone.getZoneCode(), zone.getZoneCode());
        if (activeRules > 0 || unexpiredQuotes > 0) throw domain("ZONE_REFERENCED", "Zone is referenced by active rules or unexpired quotes");
        ZoneView before = ZoneView.from(zone);
        zone.setActive(false); zone.setUpdatedAt(clock.instant()); zones.flush();
        ZoneView after = ZoneView.from(zone);
        auditZone(actor, "ZONE_DISABLED", before, after);
        return after;
    }

    @Transactional(readOnly = true)
    public PageView<VersionView> listVersions(int page, int size) {
        admin();
        Page<RateVersionEntity> result = versions.findAll(page(page, size, "createdAt"));
        return new PageView<>(result.map(VersionView::from).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageView<RuleView> listRules(UUID versionId, int page, int size) {
        admin();
        RateVersionEntity version = version(versionId);
        Page<RateRuleEntity> result = rules.findByRateVersionId(versionId,
                page(page, size, "pickupZoneCode", "dropoffZoneCode", "sizeCategory", "minWeightKg"));
        return new PageView<>(result.map(r -> RuleView.from(r, version.getRevision())).getContent(), page, size, result.getTotalElements());
    }

    public VersionView createDraft(DraftCreate input) {
        String actor = command(input);
        if (input.effectiveTo() != null && !input.effectiveTo().isAfter(input.effectiveFrom()))
            throw domain("INVALID_EFFECTIVE_INTERVAL", "effectiveTo must be after effectiveFrom");
        String code = input.versionCode().trim();
        if (versions.existsByVersionCode(code)) throw conflict("DUPLICATE_RATE_VERSION", "Version code already exists");
        RateVersionEntity version = new RateVersionEntity(code, input.currency(), RateVersionStatus.DRAFT,
                input.effectiveFrom(), input.description());
        version.setEffectiveTo(input.effectiveTo()); versions.saveAndFlush(version);
        VersionView after = VersionView.from(version);
        auditVersion(actor, "RATE_DRAFT_CREATED", null, after, null, null, after, requestId());
        return after;
    }

    public RuleView writeRule(UUID versionId, UUID ruleId, RuleWrite input) {
        String actor = command(input);
        RateVersionEntity version = version(versionId);
        checkRevision(version.getRevision(), input.expectedRevision());
        if (version.getStatus() != RateVersionStatus.DRAFT) throw conflict("RATE_VERSION_IMMUTABLE", "Only draft rules can be changed");
        RuleView before = null;
        RateRuleEntity entity;
        if (ruleId == null) {
            entity = new RateRuleEntity(versionId, input.pickupZoneCode(), input.dropoffZoneCode(), input.sizeCategory(),
                    input.minWeightKg(), input.maxWeightKg(), input.baseFeeMinorUnits(), input.perKgSurchargeMinorUnits(), input.weightThresholdKg());
        } else {
            entity = rules.findById(ruleId).filter(r -> versionId.equals(r.getRateVersionId()))
                    .orElseThrow(() -> missing("RATE_RULE_NOT_FOUND"));
            before = RuleView.from(entity, version.getRevision());
        }
        requireActiveZone(input.pickupZoneCode()); requireActiveZone(input.dropoffZoneCode());
        RateRule candidate;
        try {
            candidate = new RateRule(ruleId, versionId, input.pickupZoneCode(), input.dropoffZoneCode(), input.sizeCategory(),
                    input.minWeightKg(), input.maxWeightKg(), Money.ofMinorUnits(input.baseFeeMinorUnits(), version.getCurrency()),
                    Money.ofMinorUnits(input.perKgSurchargeMinorUnits(), version.getCurrency()), input.weightThresholdKg());
            ruleValidator.validate(version.toDomain(), candidate,
                    rules.findByRateVersionId(versionId).stream().map(r -> r.toDomain(version.getCurrency())).toList());
        } catch (AmbiguousRateRuleException ex) {
            // Exact duplicates are conflicts; all other inclusive overlaps are domain failures.
            boolean duplicate = rules.findByRateVersionId(versionId).stream().anyMatch(r -> !Objects.equals(r.getId(), ruleId)
                    && r.getPickupZoneCode().equals(input.pickupZoneCode()) && r.getDropoffZoneCode().equals(input.dropoffZoneCode())
                    && r.getSizeCategory() == input.sizeCategory() && r.getMinWeightKg().compareTo(input.minWeightKg()) == 0
                    && r.getMaxWeightKg().compareTo(input.maxWeightKg()) == 0);
            if (duplicate) throw conflict("DUPLICATE_RATE_RULE", "Rule band already exists");
            throw domain("OVERLAPPING_RATE_RULE", "Rule band overlaps an existing rule");
        } catch (IllegalArgumentException ex) {
            throw domain("INVALID_RATE_RULE", "Invalid rate rule bounds or currency");
        } catch (ArithmeticException ex) {
            throw domain("ARITHMETIC_OVERFLOW", "Rule fees overflow signed integer minor units");
        }
        VersionView priorVersion = VersionView.from(version);
        em.lock(version, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        entity.replaceDraftRule(input.pickupZoneCode(), input.dropoffZoneCode(), input.sizeCategory(), input.minWeightKg(),
                input.maxWeightKg(), input.baseFeeMinorUnits(), input.perKgSurchargeMinorUnits(), input.weightThresholdKg());
        rules.saveAndFlush(entity);
        RuleView after = RuleView.from(entity, version.getRevision());
        auditVersion(actor, ruleId == null ? "RATE_RULE_CREATED" : "RATE_RULE_UPDATED", priorVersion,
                VersionView.from(version), entity.getId(), before, after, requestId());
        return after;
    }

    public VersionView activate(UUID id, Activate input) {
        String actor = command(input);
        RateVersionEntity target = version(id);
        checkRevision(target.getRevision(), input.expectedRevision());
        if (target.getStatus() != RateVersionStatus.DRAFT) throw conflict("INVALID_RATE_TRANSITION", "Only a draft can be activated");
        RateVersionEntity active = versions.findFirstByCurrencyAndStatus("LKR", RateVersionStatus.ACTIVE).orElse(null);
        if (!Objects.equals(active == null ? null : active.getId(), input.expectedActiveVersionId()))
            throw conflict("ACTIVE_VERSION_CHANGED", "The active card changed; refresh before publishing");
        Instant now = clock.instant();
        if (target.getEffectiveFrom().isAfter(now) || (target.getEffectiveTo() != null && !now.isBefore(target.getEffectiveTo())))
            throw domain("RATE_NOT_EFFECTIVE", "Draft must be effective at publication time");
        List<RateRule> allRules = rules.findByRateVersionId(id).stream().map(r -> r.toDomain(target.getCurrency())).toList();
        for (RateRule rule : allRules) {
            requireActiveZone(rule.getPickupZoneCode()); requireActiveZone(rule.getDropoffZoneCode());
            try {
                ruleValidator.validate(target.toDomain(), rule, allRules);
            } catch (IllegalArgumentException ex) {
                throw domain("INVALID_RATE_CARD", "A stored rule violates pricing bounds or currency");
            }
        }
        validateCoverage(allRules);
        UUID requestId = requestId();
        if (active != null) {
            if (!now.isAfter(active.getEffectiveFrom())) throw domain("INVALID_EFFECTIVE_INTERVAL", "Previous card cannot end before or at its start");
            VersionView before = VersionView.from(active);
            active.setStatus(RateVersionStatus.SUPERSEDED); active.setEffectiveTo(now);
            versions.flush(); // Release unique ACTIVE index before activating the target.
            auditVersion(actor, "RATE_SUPERSEDED", before, VersionView.from(active), null, before, VersionView.from(active), requestId);
        }
        VersionView before = VersionView.from(target);
        target.setStatus(RateVersionStatus.ACTIVE); target.setActivatedAt(now); versions.flush();
        VersionView after = VersionView.from(target);
        auditVersion(actor, "RATE_ACTIVATED", before, after, null, before, after, requestId);
        return after;
    }

    public VersionView archive(UUID id, ExpectedRevision input) {
        String actor = command(input);
        RateVersionEntity version = version(id); checkRevision(version.getRevision(), input.expectedRevision());
        if (version.getStatus() != RateVersionStatus.SUPERSEDED) throw conflict("INVALID_RATE_TRANSITION", "Only superseded cards can be archived");
        VersionView before = VersionView.from(version);
        version.setStatus(RateVersionStatus.ARCHIVED); version.setArchivedAt(clock.instant()); versions.flush();
        VersionView after = VersionView.from(version);
        auditVersion(actor, "RATE_ARCHIVED", before, after, null, before, after, requestId());
        return after;
    }

    private void validateCoverage(List<RateRule> allRules) {
        List<ZoneEntity> activeZones = zones.findByActiveTrueOrderByZoneCodeAsc();
        if (activeZones.isEmpty() || allRules.isEmpty()) throw domain("INCOMPLETE_RATE_CARD", "Rate card must cover active routes");
        for (ZoneEntity origin : activeZones) for (ZoneEntity destination : activeZones) for (SizeCategory size : SizeCategory.values()) {
            List<RateRule> band = allRules.stream().filter(r -> r.getPickupZoneCode().equals(origin.getZoneCode())
                    && r.getDropoffZoneCode().equals(destination.getZoneCode()) && r.getSizeCategory() == size)
                    .sorted(Comparator.comparing(RateRule::getMinWeightKg)).toList();
            BigDecimal next = new BigDecimal("0.01");
            for (RateRule r : band) {
                if (r.getMinWeightKg().compareTo(next) != 0) throw domain("INCOMPLETE_RATE_CARD", "Rate card has a gap in route/category coverage");
                next = r.getMaxWeightKg().add(new BigDecimal("0.01"));
            }
            if (next.compareTo(size.getMaxWeightKg().add(new BigDecimal("0.01"))) < 0)
                throw domain("INCOMPLETE_RATE_CARD", "Rate card does not cover category maximum weight");
        }
    }

    private String command(Object input) {
        String actor = admin();
        if (input == null || !validator.validate(input).isEmpty()) throw new AdminConfigurationException(400, "VALIDATION_FAILED", "Invalid admin configuration input");
        lock.forCommand();
        return actor;
    }

    private String admin() {
        CallerContext context = caller.resolveCurrentCaller();
        if (context.isAnonymous() || context.subject() == null || context.subject().isBlank()
                || context.subject().length() > 255 || !context.roles().contains("ADMIN"))
            throw new org.springframework.security.access.AccessDeniedException("Verified administrator identity is required");
        return context.subject();
    }

    private PageRequest page(int page, int size, String... sort) {
        if (page < 0 || size < 1 || size > 50) throw new AdminConfigurationException(400, "INVALID_PAGE", "Page must be nonnegative and size 1..50");
        return PageRequest.of(page, size, Sort.by(sort).and(Sort.by("id")));
    }

    private ZoneEntity zone(UUID id) { return zones.findById(id).orElseThrow(() -> missing("ZONE_NOT_FOUND")); }
    private RateVersionEntity version(UUID id) { return versions.findById(id).orElseThrow(() -> missing("RATE_VERSION_NOT_FOUND")); }
    private void requireActiveZone(String code) {
        if (!zones.findByZoneCode(code).map(ZoneEntity::isActive).orElse(false)) throw domain("ZONE_UNAVAILABLE", "Rate rule references a missing or inactive zone");
    }
    private void checkRevision(long actual, long expected) { if (actual != expected) throw conflict("STALE_REVISION", "Resource changed; refresh before retrying"); }
    private void auditZone(String actor, String operation, ZoneView before, ZoneView after) {
        audit.append(actor, operation, "ZONE", after.id(), null, before == null ? null : Boolean.toString(before.active()),
                Boolean.toString(after.active()), before == null ? null : before.revision(), after.revision(), before, after, clock.instant(), requestId());
    }
    private void auditVersion(String actor, String operation, VersionView before, VersionView after, UUID ruleId,
                              Object priorState, Object newState, UUID requestId) {
        audit.append(actor, operation, "RATE_VERSION", after.id(), ruleId, before == null ? null : before.status().name(),
                after.status().name(), before == null ? null : before.revision(), after.revision(), priorState, newState, clock.instant(), requestId);
    }
    private AdminConfigurationException missing(String code) { return new AdminConfigurationException(404, code, "Pricing resource was not found"); }
    private AdminConfigurationException conflict(String code, String message) { return new AdminConfigurationException(409, code, message); }
    private AdminConfigurationException domain(String code, String message) { return new AdminConfigurationException(422, code, message); }

    private UUID requestId() {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
            Object value = servlet.getRequest().getAttribute(com.swiftdrop.pricing.api.AdminRequestIdFilter.ATTRIBUTE);
            if (value instanceof UUID id) return id;
        }
        return UUID.randomUUID();
    }
}
