package com.swiftdrop.deliveryservice.tracking;

import com.swiftdrop.deliveryservice.core.ParcelRepository;
import com.swiftdrop.deliveryservice.status.StatusHistoryRepository;
import com.swiftdrop.deliveryservice.tracking.exception.InvalidTrackingReferenceException;
import com.swiftdrop.deliveryservice.tracking.exception.TrackingNotFoundException;
import com.swiftdrop.deliveryservice.tracking.service.DefaultPublicTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Enumeration-resistance and tracking reference format validation tests.
 * Ensures attackers cannot enumerate parcels, discover active identifiers,
 * or use internal UUIDs to probe system state.
 */
@ExtendWith(MockitoExtension.class)
class PublicTrackingEnumerationAndValidationTest {

    @Mock
    private ParcelRepository parcelRepository;

    @Mock
    private StatusHistoryRepository statusHistoryRepository;

    private DefaultPublicTrackingService trackingService;

    @BeforeEach
    void setUp() {
        trackingService = new DefaultPublicTrackingService(parcelRepository, statusHistoryRepository);
    }

    @Test
    @DisplayName("Enumeration resistance: Unknown valid-format tracking reference throws TrackingNotFoundException")
    void unknownTrackingReference_ThrowsTrackingNotFoundException() {
        String nonExistentReference = "SWD-20261023-NOTFOUND";
        when(parcelRepository.findByTrackingNumber(nonExistentReference)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> trackingService.getPublicTracking(nonExistentReference))
                .isInstanceOf(TrackingNotFoundException.class)
                .hasMessage("Tracking reference '" + nonExistentReference + "' was not found.");

        verify(parcelRepository).findByTrackingNumber(nonExistentReference);
        verifyNoInteractions(statusHistoryRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "invalid-prefix-12345",
            "SWD-2026102-A1B2",      // 7 digits date
            "SWD-202610234-A1B2",     // 9 digits date
            "swd-20261023-a1b2c3",    // lowercase disallowed
            "SWD-20261023-A",         // suffix too short (<4)
            "SWD-20261023-TOOLONGSUFFIX", // suffix too long (>8)
            "SWD-20261023-A!B@C#",    // special characters disallowed
            "   ",                    // blank
            "SELECT * FROM parcels",  // SQL injection attempt
            "../../etc/passwd",       // path traversal attempt
            "<script>alert(1)</script>" // XSS probe
    })
    @DisplayName("Input validation: Malformed or malicious tracking references are rejected before database queries")
    void malformedTrackingReference_RejectedBeforeDatabaseQuery(String malformedRef) {
        assertThatThrownBy(() -> trackingService.getPublicTracking(malformedRef))
                .isInstanceOf(InvalidTrackingReferenceException.class);

        // Security check: Zero database interactions for malformed inputs
        verifyNoInteractions(parcelRepository);
        verifyNoInteractions(statusHistoryRepository);
    }

    @Test
    @DisplayName("Input validation: Null tracking reference is rejected with clear error")
    void nullTrackingReference_Rejected() {
        assertThatThrownBy(() -> trackingService.getPublicTracking(null))
                .isInstanceOf(InvalidTrackingReferenceException.class)
                .hasMessageContaining("cannot be empty");

        verifyNoInteractions(parcelRepository);
    }

    @Test
    @DisplayName("Input validation: Oversized tracking reference (>32 characters) is rejected safely")
    void oversizedTrackingReference_Rejected() {
        String oversized = "SWD-20261023-" + "A".repeat(50);
        assertThatThrownBy(() -> trackingService.getPublicTracking(oversized))
                .isInstanceOf(InvalidTrackingReferenceException.class)
                .hasMessageContaining("between 17 and 32 characters");

        verifyNoInteractions(parcelRepository);
    }

    @Test
    @DisplayName("Enumeration resistance: Internal UUID primary keys cannot be used as public tracking numbers")
    void internalUuid_RejectedWithoutDatabaseLookup() {
        String internalUuid = UUID.randomUUID().toString();

        assertThatThrownBy(() -> trackingService.getPublicTracking(internalUuid))
                .isInstanceOf(InvalidTrackingReferenceException.class);

        verifyNoInteractions(parcelRepository);
    }
}
