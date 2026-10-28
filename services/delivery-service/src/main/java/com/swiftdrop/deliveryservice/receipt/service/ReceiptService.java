package com.swiftdrop.deliveryservice.receipt.service;

import com.swiftdrop.deliveryservice.receipt.exception.ReceiptAccessDeniedException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptNotAvailableException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptNotFoundException;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptData;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptRenderLimits;
import com.swiftdrop.deliveryservice.receipt.port.ReceiptDataProvider;
import com.swiftdrop.deliveryservice.receipt.port.ReceiptRendererPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Core application service coordinating receipt data retrieval, eligibility checks,
 * object-level authorization, and bounded PDF rendering.
 */
@Service
public class ReceiptService {

    private final ReceiptDataProvider receiptDataProvider;
    private final ReceiptAuthorizationService authorizationService;
    private final ReceiptRendererPort receiptRendererPort;

    public ReceiptService(ReceiptDataProvider receiptDataProvider,
                          ReceiptAuthorizationService authorizationService,
                          ReceiptRendererPort receiptRendererPort) {
        this.receiptDataProvider = Objects.requireNonNull(receiptDataProvider, "receiptDataProvider must not be null");
        this.authorizationService = Objects.requireNonNull(authorizationService, "authorizationService must not be null");
        this.receiptRendererPort = Objects.requireNonNull(receiptRendererPort, "receiptRendererPort must not be null");
    }

    public RenderedReceipt generateReceipt(UUID parcelId, Jwt jwt) {
        return generateReceipt(parcelId, jwt, ReceiptRenderLimits.DEFAULT);
    }

    public RenderedReceipt generateReceipt(UUID parcelId, Jwt jwt, ReceiptRenderLimits limits) {
        Objects.requireNonNull(parcelId, "parcelId must not be null");
        Objects.requireNonNull(jwt, "jwt must not be null");
        Objects.requireNonNull(limits, "limits must not be null");

        // 1. Resolve authoritative receipt data from trusted persistence
        ReceiptData receipt = receiptDataProvider.getAuthoritativeReceiptData(parcelId)
                .orElseThrow(() -> new ReceiptNotFoundException("Receipt not available or parcel not completed"));

        // 2. Validate parcel completion & payment status
        if (!ReceiptData.ELIGIBLE_STATUS.equalsIgnoreCase(receipt.deliveryStatus())) {
            throw new ReceiptNotAvailableException("Receipt not available or parcel not completed");
        }
        if (!receipt.paymentConfirmed()) {
            throw new ReceiptNotAvailableException("Receipt not available: parcel payment is unconfirmed");
        }

        // 3. Object-level authorization check (fail closed)
        if (!authorizationService.isAuthorized(jwt, receipt)) {
            throw new ReceiptAccessDeniedException("Forbidden - unauthorized to download this receipt");
        }

        // 4. Bounded PDF rendering
        byte[] pdfBytes = receiptRendererPort.renderReceipt(receipt, limits);

        // 5. Build sanitized disposition filename
        String sanitizedTracking = receipt.trackingNumber().replaceAll("[^A-Za-z0-9_-]", "");
        String filename = "receipt-" + sanitizedTracking + ".pdf";

        return new RenderedReceipt(pdfBytes, receipt.trackingNumber(), filename);
    }
}
