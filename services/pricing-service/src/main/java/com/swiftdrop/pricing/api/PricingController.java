package com.swiftdrop.pricing.api;

import com.swiftdrop.pricing.api.dto.QuoteRequest;
import com.swiftdrop.pricing.api.dto.QuoteResponse;
import com.swiftdrop.pricing.api.dto.ZoneListResponse;
import com.swiftdrop.pricing.application.QuoteApplicationService;
import com.swiftdrop.pricing.application.RateVersionQueryService;
import com.swiftdrop.pricing.domain.Money;
import com.swiftdrop.pricing.domain.PricingZone;
import com.swiftdrop.pricing.domain.RateVersion;
import com.swiftdrop.pricing.security.CallerContext;
import com.swiftdrop.pricing.security.SecurityContextCallerResolver;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST controller implementing the reviewed Pricing & Zones endpoints per openapi-pricing.yaml:
 * - POST /api/v1/quotes
 * - GET /api/v1/quotes/{quoteId}
 * - GET /api/v1/zones
 */
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class PricingController {

    private final QuoteApplicationService quoteService;
    private final RateVersionQueryService rateVersionQueryService;
    private final SecurityContextCallerResolver callerResolver;

    public PricingController(
            QuoteApplicationService quoteService,
            RateVersionQueryService rateVersionQueryService,
            SecurityContextCallerResolver callerResolver) {
        this.quoteService = quoteService;
        this.rateVersionQueryService = rateVersionQueryService;
        this.callerResolver = callerResolver;
    }

    @PostMapping(value = "/quotes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<QuoteResponse> calculateQuote(@Valid @RequestBody QuoteRequest request) {
        CallerContext caller = callerResolver.resolveCurrentCaller();
        QuoteResponse response = quoteService.createQuote(request, caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/quotes/{quoteId}")
    public ResponseEntity<QuoteResponse> getQuoteById(@PathVariable UUID quoteId) {
        CallerContext caller = callerResolver.resolveCurrentCaller();
        QuoteResponse response = quoteService.getQuote(quoteId, caller);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/zones")
    public ResponseEntity<ZoneListResponse> listZones() {
        RateVersion activeRate = rateVersionQueryService.getActiveRateVersion(Money.DEFAULT_CURRENCY);
        List<PricingZone> activeZones = rateVersionQueryService.getActiveZones();

        List<ZoneListResponse.ZoneItem> zoneItems = activeZones.stream()
                .map(z -> new ZoneListResponse.ZoneItem(
                        z.getZoneCode(),
                        z.getName(),
                        z.getDescription(),
                        z.getStandardTransitHours()
                ))
                .toList();

        return ResponseEntity.ok(new ZoneListResponse(activeRate.getVersionCode(), zoneItems));
    }
}
