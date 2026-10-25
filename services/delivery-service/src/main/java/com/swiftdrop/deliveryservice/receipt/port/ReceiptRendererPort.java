package com.swiftdrop.deliveryservice.receipt.port;

import com.swiftdrop.deliveryservice.receipt.model.ReceiptData;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptRenderLimits;

/**
 * Port interface for rendering validated receipt data into an official PDF document.
 */
public interface ReceiptRendererPort {

    /**
     * Renders receipt data into a byte array representing a valid PDF document,
     * enforcing the specified resource limits.
     *
     * @param data validated domain receipt data
     * @param limits explicit resource limits
     * @return PDF binary byte array
     */
    byte[] renderReceipt(ReceiptData data, ReceiptRenderLimits limits);

    /**
     * Renders receipt data using the default production resource limits.
     */
    default byte[] renderReceipt(ReceiptData data) {
        return renderReceipt(data, ReceiptRenderLimits.DEFAULT);
    }
}
