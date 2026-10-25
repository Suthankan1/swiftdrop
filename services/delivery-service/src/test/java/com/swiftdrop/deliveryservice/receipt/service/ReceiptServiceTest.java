package com.swiftdrop.deliveryservice.receipt.service;

import com.swiftdrop.deliveryservice.receipt.exception.ReceiptAccessDeniedException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptNotAvailableException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptNotFoundException;
import com.swiftdrop.deliveryservice.receipt.exception.ReceiptResourceLimitException;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptData;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptLineItem;
import com.swiftdrop.deliveryservice.receipt.model.ReceiptRenderLimits;
import com.swiftdrop.deliveryservice.receipt.port.ReceiptDataProvider;
import com.swiftdrop.deliveryservice.receipt.port.ReceiptRendererPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    @Mock
    private ReceiptDataProvider dataProvider;

    @Mock
    private ReceiptAuthorizationService authorizationService;

    @Mock
    private ReceiptRendererPort rendererPort;

    private ReceiptService receiptService;
    private final UUID parcelId = UUID.randomUUID();
    private Jwt mockJwt;
    private ReceiptData sampleReceipt;

    @BeforeEach
    void setUp() {
        receiptService = new ReceiptService(dataProvider, authorizationService, rendererPort);

        mockJwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("auth0|alice")
                .claim("roles", List.of("CUSTOMER"))
                .build();

        sampleReceipt = new ReceiptData(
                "RCP-1",
                parcelId,
                "SWD-20261025-A1B2C3",
                "auth0|alice",
                null,
                "Recipient",
                "+94771234567",
                "Pickup",
                "Dropoff",
                "DELIVERED",
                Instant.now(),
                Instant.now(),
                "RATE-V1",
                "PAY-TX-1",
                true,
                "LKR",
                50000L,
                List.of(new ReceiptLineItem("Delivery", 50000L, "LKR"))
        );
    }

    @Test
    @DisplayName("Successful receipt generation when parcel is delivered, payment confirmed, and caller is authorized")
    void successfulReceiptGeneration() {
        byte[] expectedPdf = "%PDF-mock-bytes".getBytes();
        when(dataProvider.getAuthoritativeReceiptData(parcelId)).thenReturn(Optional.of(sampleReceipt));
        when(authorizationService.isAuthorized(mockJwt, sampleReceipt)).thenReturn(true);
        when(rendererPort.renderReceipt(eq(sampleReceipt), any(ReceiptRenderLimits.class))).thenReturn(expectedPdf);

        RenderedReceipt result = receiptService.generateReceipt(parcelId, mockJwt);

        assertThat(result).isNotNull();
        assertThat(result.pdfBytes()).isEqualTo(expectedPdf);
        assertThat(result.trackingNumber()).isEqualTo("SWD-20261025-A1B2C3");
        assertThat(result.filename()).isEqualTo("receipt-SWD-20261025-A1B2C3.pdf");

        verify(dataProvider).getAuthoritativeReceiptData(parcelId);
        verify(authorizationService).isAuthorized(mockJwt, sampleReceipt);
        verify(rendererPort).renderReceipt(eq(sampleReceipt), any(ReceiptRenderLimits.class));
    }

    @Test
    @DisplayName("Throws ReceiptNotFoundException when parcel does not exist in data provider")
    void parcelNotFoundThrowsException() {
        when(dataProvider.getAuthoritativeReceiptData(parcelId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> receiptService.generateReceipt(parcelId, mockJwt))
                .isInstanceOf(ReceiptNotFoundException.class)
                .hasMessageContaining("Receipt not available or parcel not completed");

        verifyNoInteractions(authorizationService);
        verifyNoInteractions(rendererPort);
    }

    @Test
    @DisplayName("Throws ReceiptAccessDeniedException when caller is not authorized")
    void unauthorizedCallerThrowsException() {
        when(dataProvider.getAuthoritativeReceiptData(parcelId)).thenReturn(Optional.of(sampleReceipt));
        when(authorizationService.isAuthorized(mockJwt, sampleReceipt)).thenReturn(false);

        assertThatThrownBy(() -> receiptService.generateReceipt(parcelId, mockJwt))
                .isInstanceOf(ReceiptAccessDeniedException.class)
                .hasMessageContaining("Forbidden - unauthorized to download this receipt");

        verifyNoInteractions(rendererPort);
    }

    @Test
    @DisplayName("Propagates ReceiptResourceLimitException when renderer detects boundary violation")
    void resourceLimitViolationPropagated() {
        when(dataProvider.getAuthoritativeReceiptData(parcelId)).thenReturn(Optional.of(sampleReceipt));
        when(authorizationService.isAuthorized(mockJwt, sampleReceipt)).thenReturn(true);
        when(rendererPort.renderReceipt(eq(sampleReceipt), any(ReceiptRenderLimits.class)))
                .thenThrow(new ReceiptResourceLimitException("Output document size exceeded"));

        assertThatThrownBy(() -> receiptService.generateReceipt(parcelId, mockJwt))
                .isInstanceOf(ReceiptResourceLimitException.class)
                .hasMessageContaining("Output document size exceeded");
    }
}
