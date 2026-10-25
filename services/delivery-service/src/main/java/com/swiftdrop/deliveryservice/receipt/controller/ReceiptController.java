package com.swiftdrop.deliveryservice.receipt.controller;

import com.swiftdrop.deliveryservice.receipt.service.ReceiptService;
import com.swiftdrop.deliveryservice.receipt.service.RenderedReceipt;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * REST endpoint for downloading official PDF delivery receipts.
 * Conforms to openapi-delivery.yaml: GET /api/v1/parcels/{parcelId}/receipt
 */
@RestController
@RequestMapping("/api/v1/parcels")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = Objects.requireNonNull(receiptService, "receiptService must not be null");
    }

    @GetMapping(value = "/{parcelId}/receipt", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getDeliveryReceipt(
            @PathVariable("parcelId") UUID parcelId,
            @AuthenticationPrincipal Jwt jwt) {

        RenderedReceipt rendered = receiptService.generateReceipt(parcelId, jwt);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + rendered.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, max-age=0")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .body(rendered.pdfBytes());
    }
}
