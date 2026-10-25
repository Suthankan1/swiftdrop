package com.swiftdrop.deliveryservice.receipt.provider;

import com.swiftdrop.deliveryservice.receipt.model.ReceiptData;
import com.swiftdrop.deliveryservice.receipt.port.ReceiptDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Production receipt data provider seam.
 * Per repository architectural constraints (Phase 6), production delivery booking,
 * payment confirmation, and courier lifecycle transitions are under parallel development.
 * This provider safely fails closed (returns empty) rather than fabricating synthetic
 * financial records or unconfirmed delivery state in production.
 */
@Component
@ConditionalOnMissingBean(name = "testReceiptDataProvider")
public class DefaultReceiptDataProvider implements ReceiptDataProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultReceiptDataProvider.class);

    @Override
    public Optional<ReceiptData> getAuthoritativeReceiptData(UUID parcelId) {
        log.info("Receipt requested for parcelId={} - Upstream Delivery completion and payment records pending integration.", parcelId);
        // Fails closed until upstream delivery completion and payment tables are merged into main
        return Optional.empty();
    }
}
