package com.swiftdrop.pricing.application;

import com.swiftdrop.pricing.domain.Money;
import com.swiftdrop.pricing.domain.PricingZone;
import com.swiftdrop.pricing.domain.RateRule;
import com.swiftdrop.pricing.domain.RateVersion;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.error.RateVersionNotFoundException;
import com.swiftdrop.pricing.persistence.RateRuleRepository;
import com.swiftdrop.pricing.persistence.RateVersionEntity;
import com.swiftdrop.pricing.persistence.RateVersionRepository;
import com.swiftdrop.pricing.persistence.ZoneEntity;
import com.swiftdrop.pricing.persistence.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Reusable query service providing clean domain read boundaries for subsequent tasks:
 * - Issue #108: GET /api/v1/zones and Quote generation
 * - Issue #114: Pricing gRPC quote verification
 * - Issue #123: Admin versioned rate inspection
 */
@Service
@Transactional(readOnly = true)
public class RateVersionQueryService {

    private final ZoneRepository zoneRepository;
    private final RateVersionRepository rateVersionRepository;
    private final RateRuleRepository rateRuleRepository;

    public RateVersionQueryService(ZoneRepository zoneRepository,
                                   RateVersionRepository rateVersionRepository,
                                   RateRuleRepository rateRuleRepository) {
        this.zoneRepository = zoneRepository;
        this.rateVersionRepository = rateVersionRepository;
        this.rateRuleRepository = rateRuleRepository;
    }

    public List<PricingZone> getActiveZones() {
        return zoneRepository.findByActiveTrueOrderByZoneCodeAsc()
                .stream()
                .map(ZoneEntity::toDomain)
                .toList();
    }

    public RateVersion getActiveRateVersion(String currency) {
        String curr = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : Money.DEFAULT_CURRENCY;
        return rateVersionRepository.findFirstByCurrencyAndStatus(curr, RateVersionStatus.ACTIVE)
                .map(RateVersionEntity::toDomain)
                .orElseThrow(() -> new RateVersionNotFoundException("No active rate version found for currency " + curr));
    }

    public Optional<RateVersion> findRateVersionByCode(String versionCode) {
        return rateVersionRepository.findByVersionCode(versionCode)
                .map(RateVersionEntity::toDomain);
    }

    public List<RateRule> getRateRulesForVersion(String versionCode) {
        RateVersionEntity versionEntity = rateVersionRepository.findByVersionCode(versionCode)
                .orElseThrow(() -> new RateVersionNotFoundException(versionCode));
        return rateRuleRepository.findByRateVersionId(versionEntity.getId())
                .stream()
                .map(r -> r.toDomain(versionEntity.getCurrency()))
                .toList();
    }
}
