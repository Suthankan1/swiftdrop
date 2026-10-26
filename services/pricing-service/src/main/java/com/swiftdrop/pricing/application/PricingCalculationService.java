package com.swiftdrop.pricing.application;

import com.swiftdrop.pricing.domain.ParcelMeasurements;
import com.swiftdrop.pricing.domain.PricingCalculation;
import com.swiftdrop.pricing.domain.PricingStrategy;
import com.swiftdrop.pricing.domain.PricingZone;
import com.swiftdrop.pricing.domain.RateRule;
import com.swiftdrop.pricing.domain.RateVersion;
import com.swiftdrop.pricing.domain.RateVersionStatus;
import com.swiftdrop.pricing.error.InactiveZoneException;
import com.swiftdrop.pricing.error.RateVersionNotFoundException;
import com.swiftdrop.pricing.error.ZoneNotFoundException;
import com.swiftdrop.pricing.persistence.RateRuleRepository;
import com.swiftdrop.pricing.persistence.RateVersionEntity;
import com.swiftdrop.pricing.persistence.RateVersionRepository;
import com.swiftdrop.pricing.persistence.ZoneEntity;
import com.swiftdrop.pricing.persistence.ZoneRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * Application service orchestrating price calculations across persistent zones,
 * immutable rate cards, and deterministic pricing strategies.
 */
@Service
@Transactional(readOnly = true)
public class PricingCalculationService {

    private final ZoneRepository zoneRepository;
    private final RateVersionRepository rateVersionRepository;
    private final RateRuleRepository rateRuleRepository;
    private final PricingStrategy pricingStrategy;

    public PricingCalculationService(ZoneRepository zoneRepository,
                                     RateVersionRepository rateVersionRepository,
                                     RateRuleRepository rateRuleRepository,
                                     PricingStrategy pricingStrategy) {
        this.zoneRepository = zoneRepository;
        this.rateVersionRepository = rateVersionRepository;
        this.rateRuleRepository = rateRuleRepository;
        this.pricingStrategy = pricingStrategy;
    }

    /**
     * Calculates the deterministic delivery price for the given command.
     *
     * @param command input containing route, dimensions, weight, and optional rate version
     * @return calculated PricingCalculation detailing all components
     */
    public PricingCalculation calculatePrice(CalculationCommand command) {
        Objects.requireNonNull(command, "CalculationCommand cannot be null");

        // 1. Validate measurements
        ParcelMeasurements measurements = command.toMeasurements();

        // 2. Resolve origin and destination zones
        ZoneEntity pickupEntity = zoneRepository.findByZoneCode(command.pickupZoneCode())
                .orElseThrow(() -> new ZoneNotFoundException(command.pickupZoneCode()));
        if (!pickupEntity.isActive()) {
            throw new InactiveZoneException(command.pickupZoneCode());
        }

        ZoneEntity dropoffEntity = zoneRepository.findByZoneCode(command.dropoffZoneCode())
                .orElseThrow(() -> new ZoneNotFoundException(command.dropoffZoneCode()));
        if (!dropoffEntity.isActive()) {
            throw new InactiveZoneException(command.dropoffZoneCode());
        }

        PricingZone pickupZone = pickupEntity.toDomain();
        PricingZone dropoffZone = dropoffEntity.toDomain();

        // 3. Resolve rate version
        RateVersionEntity rateVersionEntity;
        if (command.rateVersionCode() != null && !command.rateVersionCode().isBlank()) {
            rateVersionEntity = rateVersionRepository.findByVersionCode(command.rateVersionCode())
                    .orElseThrow(() -> new RateVersionNotFoundException(command.rateVersionCode()));
        } else {
            rateVersionEntity = rateVersionRepository.findFirstByCurrencyAndStatus(
                            command.currency(), RateVersionStatus.ACTIVE)
                    .orElseThrow(() -> new RateVersionNotFoundException(
                            "No active rate version found for currency " + command.currency()));
        }

        RateVersion rateVersion = rateVersionEntity.toDomain();

        // 4. Load rate rules for this version and route
        List<RateRule> rules = rateRuleRepository
                .findByRateVersionIdAndPickupZoneCodeAndDropoffZoneCode(
                        rateVersionEntity.getId(),
                        pickupZone.getZoneCode(),
                        dropoffZone.getZoneCode())
                .stream()
                .map(entity -> entity.toDomain(rateVersion.getCurrency()))
                .toList();

        // 5. Calculate deterministically using the pricing strategy
        return pricingStrategy.calculate(rateVersion, pickupZone, dropoffZone, measurements, rules);
    }
}
