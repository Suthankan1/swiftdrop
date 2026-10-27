package com.swiftdrop.pricing.api;

import com.swiftdrop.pricing.api.dto.AdminPricingDtos.*;
import com.swiftdrop.pricing.application.AdminPricingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/pricing")
public class AdminPricingController {
    private final AdminPricingService service;

    public AdminPricingController(AdminPricingService service) { this.service = service; }

    @GetMapping("/zones")
    public PageView<ZoneView> zones(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.listZones(page, size);
    }

    @GetMapping("/zones/{id}")
    public ZoneView zone(@PathVariable UUID id) { return service.getZone(id); }

    @PostMapping("/zones")
    public ResponseEntity<ZoneView> createZone(@Valid @RequestBody ZoneCreate input) {
        ZoneView view = service.createZone(input);
        return ResponseEntity.created(URI.create("/api/v1/admin/pricing/zones/" + view.id())).body(view);
    }

    @PutMapping("/zones/{id}")
    public ZoneView updateZone(@PathVariable UUID id, @Valid @RequestBody ZoneUpdate input) { return service.updateZone(id, input); }

    @PostMapping("/zones/{id}/disable")
    public ZoneView disableZone(@PathVariable UUID id, @Valid @RequestBody ExpectedRevision input) { return service.disableZone(id, input); }

    @GetMapping("/rate-versions")
    public PageView<VersionView> versions(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.listVersions(page, size);
    }

    @GetMapping("/rate-versions/{id}/rules")
    public PageView<RuleView> rules(@PathVariable UUID id, @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) { return service.listRules(id, page, size); }

    @PostMapping("/rate-versions")
    public ResponseEntity<VersionView> draft(@Valid @RequestBody DraftCreate input) {
        VersionView view = service.createDraft(input);
        return ResponseEntity.created(URI.create("/api/v1/admin/pricing/rate-versions/" + view.id() + "/rules")).body(view);
    }

    @PostMapping("/rate-versions/{id}/rules")
    public ResponseEntity<RuleView> addRule(@PathVariable UUID id, @Valid @RequestBody RuleWrite input) {
        RuleView view = service.writeRule(id, null, input);
        return ResponseEntity.created(URI.create("/api/v1/admin/pricing/rate-versions/" + id + "/rules/" + view.id())).body(view);
    }

    @PutMapping("/rate-versions/{id}/rules/{ruleId}")
    public RuleView updateRule(@PathVariable UUID id, @PathVariable UUID ruleId, @Valid @RequestBody RuleWrite input) {
        return service.writeRule(id, ruleId, input);
    }

    @PostMapping("/rate-versions/{id}/activate")
    public VersionView activate(@PathVariable UUID id, @Valid @RequestBody Activate input) { return service.activate(id, input); }

    @PostMapping("/rate-versions/{id}/archive")
    public VersionView archive(@PathVariable UUID id, @Valid @RequestBody ExpectedRevision input) { return service.archive(id, input); }
}
