package com.swiftdrop.deliveryservice.receipt.adapter;

import com.swiftdrop.deliveryservice.receipt.exception.ReceiptResourceLimitException;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptData;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptLineItem;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptRenderLimits;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfReceiptAdapterTest {

    private PdfReceiptAdapter adapter;
    private ReceiptData validReceipt;

    @BeforeEach
    void setUp() {
        adapter = new PdfReceiptAdapter();
        validReceipt = createSampleReceipt();
    }

    private ReceiptData createSampleReceipt() {
        return new ReceiptData(
                "RCP-SWD-20261025-ABCD",
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "SWD-20261025-ABCD",
                "auth0|customer-12345",
                null,
                "Kamal Silva",
                "+94771234567",
                "123 Galle Road, Colombo 03",
                "45 Kandy Road, Gampaha",
                "DELIVERED",
                Instant.parse("2026-10-25T14:30:00Z"),
                Instant.parse("2026-10-25T14:35:00Z"),
                "RATE-2026-V1",
                "PAY-TX-998877",
                true,
                "LKR",
                50000L,
                List.of(
                        new ReceiptLineItem("Standard City Delivery Fee", 45000L, "LKR"),
                        new ReceiptLineItem("Fuel Surcharge", 5000L, "LKR")
                )
        );
    }

    @Nested
    @DisplayName("A. PDF Generation Verification")
    class GenerationTests {

        @Test
        @DisplayName("Successfully renders valid PDF with PDF header and readable text content")
        void rendersValidPdfDocument() throws IOException {
            byte[] pdfBytes = adapter.renderReceipt(validReceipt, ReceiptRenderLimits.DEFAULT);

            assertThat(pdfBytes).isNotNull();
            assertThat(pdfBytes.length).isGreaterThan(100);

            // Verify standard PDF magic header bytes
            String header = new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII);
            assertThat(header).isEqualTo("%PDF-");

            // Load and parse using PDFBox to verify physical structure
            try (PDDocument document = Loader.loadPDF(pdfBytes)) {
                assertThat(document.getNumberOfPages()).isEqualTo(1);

                PDDocumentInformation info = document.getDocumentInformation();
                assertThat(info.getTitle()).contains("SWD-20261025-ABCD");
                assertThat(info.getAuthor()).isEqualTo("SwiftDrop Delivery Operations");
                assertThat(info.getCreator()).isEqualTo("SwiftDrop Platform");

                // Extract text and verify canonical business fields
                PDFTextStripper stripper = new PDFTextStripper();
                String text = stripper.getText(document);

                assertThat(text)
                        .contains("SWIFTDROP")
                        .contains("OFFICIAL PARCEL DELIVERY RECEIPT")
                        .contains("RCP-SWD-20261025-ABCD")
                        .contains("SWD-20261025-ABCD")
                        .contains("11111111-1111-1111-1111-111111111111")
                        .contains("Kamal Silva")
                        .contains("+94771234567")
                        .contains("123 Galle Road")
                        .contains("45 Kandy Road")
                        .contains("DELIVERED")
                        .contains("Standard City Delivery Fee")
                        .contains("450.00")
                        .contains("Fuel Surcharge")
                        .contains("50.00")
                        .contains("TOTAL PAID")
                        .contains("500.00")
                        .contains("LKR")
                        .contains("PAY-TX-998877")
                        .contains("RATE-2026-V1")
                        .contains("UTC");
            }
        }

        @Test
        @DisplayName("Generates deterministic PDF structure for equivalent inputs")
        void deterministicPdfGeneration() {
            byte[] run1 = adapter.renderReceipt(validReceipt, ReceiptRenderLimits.DEFAULT);
            byte[] run2 = adapter.renderReceipt(validReceipt, ReceiptRenderLimits.DEFAULT);

            assertThat(run1.length).isEqualTo(run2.length);
        }

        @Test
        @DisplayName("Currency formatting handles exact minor units without floating-point errors")
        void currencyFormattingAccuracy() {
            assertThat(PdfReceiptAdapter.formatCurrency(0L)).isEqualTo("0.00");
            assertThat(PdfReceiptAdapter.formatCurrency(5L)).isEqualTo("0.05");
            assertThat(PdfReceiptAdapter.formatCurrency(50L)).isEqualTo("0.50");
            assertThat(PdfReceiptAdapter.formatCurrency(100L)).isEqualTo("1.00");
            assertThat(PdfReceiptAdapter.formatCurrency(50000L)).isEqualTo("500.00");
            assertThat(PdfReceiptAdapter.formatCurrency(123456789L)).isEqualTo("1234567.89");
        }

        @Test
        @DisplayName("Text sanitizer safely neutralizes control characters and replaces unsupported glyphs")
        void textSanitizerHandlesSpecialCharacters() {
            String input = "Safe Text \n \r \t with Unicode: \u0000 \u0007 \u2022 \uD83D\uDE00";
            String sanitized = PdfReceiptAdapter.sanitizeText(input);

            // Null bytes and control characters replaced or neutralized
            assertThat(sanitized).doesNotContain("\u0000");
            assertThat(sanitized).doesNotContain("\n");
            assertThat(sanitized).doesNotContain("\r");
            assertThat(sanitized).contains("Safe Text");
        }
    }

    @Nested
    @DisplayName("B. Resource-Boundary & Limit Enforcement")
    class ResourceLimitTests {

        @Test
        @DisplayName("Fails closed when text length exceeds configured maxTextLength")
        void textLengthLimitExceeded() {
            ReceiptRenderLimits tightLimits = new ReceiptRenderLimits(50, 50, 5, 1_048_576);

            // Address is 26 chars in sample, let's create a receipt with > 50 chars address
            ReceiptData longTextReceipt = new ReceiptData(
                    validReceipt.receiptId(),
                    validReceipt.parcelId(),
                    validReceipt.trackingNumber(),
                    validReceipt.createdBySub(),
                    validReceipt.merchantId(),
                    validReceipt.recipientName(),
                    validReceipt.recipientPhone(),
                    "This is an excessively long pickup address that exceeds the fifty character limit for testing bounds",
                    validReceipt.dropoffAddress(),
                    validReceipt.deliveryStatus(),
                    validReceipt.deliveryCompletedAt(),
                    validReceipt.issuedAt(),
                    validReceipt.rateVersion(),
                    validReceipt.paymentReference(),
                    validReceipt.paymentConfirmed(),
                    validReceipt.currency(),
                    validReceipt.amountMinorUnits(),
                    validReceipt.lineItems()
            );

            assertThatThrownBy(() -> adapter.renderReceipt(longTextReceipt, tightLimits))
                    .isInstanceOf(ReceiptResourceLimitException.class)
                    .hasMessageContaining("pickupAddress");
        }

        @Test
        @DisplayName("Fails closed when number of line items exceeds maxLineItems")
        void lineItemsLimitExceeded() {
            ReceiptRenderLimits tightLimits = new ReceiptRenderLimits(2_000, 3, 5, 1_048_576);

            List<ReceiptLineItem> items = List.of(
                    new ReceiptLineItem("Item 1", 1000L, "LKR"),
                    new ReceiptLineItem("Item 2", 1000L, "LKR"),
                    new ReceiptLineItem("Item 3", 1000L, "LKR"),
                    new ReceiptLineItem("Item 4", 1000L, "LKR")
            );

            ReceiptData manyItemsReceipt = new ReceiptData(
                    validReceipt.receiptId(),
                    validReceipt.parcelId(),
                    validReceipt.trackingNumber(),
                    validReceipt.createdBySub(),
                    validReceipt.merchantId(),
                    validReceipt.recipientName(),
                    validReceipt.recipientPhone(),
                    validReceipt.pickupAddress(),
                    validReceipt.dropoffAddress(),
                    validReceipt.deliveryStatus(),
                    validReceipt.deliveryCompletedAt(),
                    validReceipt.issuedAt(),
                    validReceipt.rateVersion(),
                    validReceipt.paymentReference(),
                    validReceipt.paymentConfirmed(),
                    validReceipt.currency(),
                    4000L,
                    items
            );

            assertThatThrownBy(() -> adapter.renderReceipt(manyItemsReceipt, tightLimits))
                    .isInstanceOf(ReceiptResourceLimitException.class)
                    .hasMessageContaining("max limit of 3");
        }

        @Test
        @DisplayName("Fails closed when output size exceeds maxOutputSizeBytes")
        void outputSizeLimitExceeded() {
            // Unreasonably small max output size (e.g. 500 bytes; a valid PDF is at least ~1KB)
            ReceiptRenderLimits tinySizeLimits = new ReceiptRenderLimits(2_000, 50, 5, 500);

            assertThatThrownBy(() -> adapter.renderReceipt(validReceipt, tinySizeLimits))
                    .isInstanceOf(ReceiptResourceLimitException.class)
                    .hasMessageContaining("exceeded limit of 500 bytes");
        }
    }
}
