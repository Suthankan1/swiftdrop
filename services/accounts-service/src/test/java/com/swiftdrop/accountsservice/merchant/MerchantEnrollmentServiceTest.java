package com.swiftdrop.accountsservice.merchant;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MerchantEnrollmentServiceTest {

    private MerchantRepository merchantRepository;
    private AccountProfileRepository accountProfileRepository;
    private MerchantEnrollmentService enrollmentService;

    private final String customerSub = "keycloak-sub-customer-123";
    private final String customerEmail = "customer@swiftdrop.test";
    private final String customerName = "Test Customer";
    private final TrustedIdentity customerIdentity = new TrustedIdentity(
            customerSub, customerEmail, customerName, List.of("CUSTOMER")
    );

    private final UUID accountProfileId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private AccountProfile accountProfile;

    @BeforeEach
    void setUp() {
        merchantRepository = mock(MerchantRepository.class);
        accountProfileRepository = mock(AccountProfileRepository.class);
        enrollmentService = new MerchantEnrollmentService(merchantRepository, accountProfileRepository);

        accountProfile = mock(AccountProfile.class);
        when(accountProfile.getId()).thenReturn(accountProfileId);
        when(accountProfile.getKeycloakSub()).thenReturn(customerSub);
        when(accountProfile.getEmail()).thenReturn(customerEmail);
        when(accountProfile.getFullName()).thenReturn(customerName);
        when(accountProfile.getPhoneNumber()).thenReturn("+94770001122");
        when(accountProfile.getCreatedAt()).thenReturn(Instant.now());

        when(accountProfileRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.of(accountProfile));
        when(merchantRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.empty());
        when(merchantRepository.findByRegistrationNumber(any())).thenReturn(Optional.empty());
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("1. Successful enrollment creates Merchant in PENDING state bound to verified subject and account profile")
    void successfulEnrollment() {
        var request = new MerchantRegistrationRequest("Lanka Retail Holdings Ltd", "PV-109923");

        var response = enrollmentService.enrollMerchant(customerIdentity, request);

        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        assertThat(response.keycloakSub()).isEqualTo(customerSub);
        assertThat(response.businessName()).isEqualTo("Lanka Retail Holdings Ltd");
        assertThat(response.registrationNumber()).isEqualTo("PV-109923");
        assertThat(response.status()).isEqualTo(MerchantStatus.PENDING);
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();

        var captor = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(response.id());
        assertThat(saved.getAccountProfileId()).isEqualTo(accountProfileId);
        assertThat(saved.getKeycloakSub()).isEqualTo(customerSub);
        assertThat(saved.getBusinessName()).isEqualTo("Lanka Retail Holdings Ltd");
        assertThat(saved.getRegistrationNumber()).isEqualTo("PV-109923");
        assertThat(saved.getStatus()).isEqualTo(MerchantStatus.PENDING);
    }

    @Test
    @DisplayName("2. Existing AccountProfile is reused without re-provisioning")
    void existingProfileReused() {
        var request = new MerchantRegistrationRequest("Lanka Retail Holdings Ltd", "PV-109923");

        enrollmentService.enrollMerchant(customerIdentity, request);

        verify(accountProfileRepository).findByKeycloakSub(customerSub);
        verify(accountProfileRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    @DisplayName("3. Missing AccountProfile triggers JIT profile provisioning")
    void missingProfileTriggersJitProvisioning() {
        when(accountProfileRepository.findByKeycloakSub(customerSub))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(accountProfile));

        var request = new MerchantRegistrationRequest("Lanka Retail Holdings Ltd", "PV-109923");

        var response = enrollmentService.enrollMerchant(customerIdentity, request);

        assertThat(response.keycloakSub()).isEqualTo(customerSub);
        verify(accountProfileRepository).insertIfAbsent(customerSub, customerEmail, customerName);
    }

    @Test
    @DisplayName("4. Duplicate merchant owner throws MerchantConflictException")
    void duplicateMerchantOwnerThrowsConflict() {
        var existingMerchant = new Merchant(
                UUID.randomUUID(), accountProfileId, customerSub,
                "Existing Shop", "PV-000001", MerchantStatus.APPROVED,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findByKeycloakSub(customerSub)).thenReturn(Optional.of(existingMerchant));

        var request = new MerchantRegistrationRequest("Another Business", "PV-109923");

        assertThatThrownBy(() -> enrollmentService.enrollMerchant(customerIdentity, request))
                .isInstanceOf(MerchantConflictException.class)
                .hasMessageContaining("Authenticated user already owns a merchant tenant");

        verify(merchantRepository, never()).save(any());
    }

    @Test
    @DisplayName("5. Duplicate registration number throws MerchantConflictException")
    void duplicateRegistrationNumberThrowsConflict() {
        var otherOwnerMerchant = new Merchant(
                UUID.randomUUID(), UUID.randomUUID(), "other-sub",
                "Other Shop", "PV-109923", MerchantStatus.PENDING,
                Instant.now(), Instant.now()
        );
        when(merchantRepository.findByRegistrationNumber("PV-109923")).thenReturn(Optional.of(otherOwnerMerchant));

        var request = new MerchantRegistrationRequest("Lanka Retail Holdings Ltd", "PV-109923");

        assertThatThrownBy(() -> enrollmentService.enrollMerchant(customerIdentity, request))
                .isInstanceOf(MerchantConflictException.class)
                .hasMessageContaining("Merchant registration number already exists: PV-109923");

        verify(merchantRepository, never()).save(any());
    }

    @Test
    @DisplayName("6. Database unique constraint on keycloak_sub translates to MerchantConflictException")
    void dbKeycloakSubConstraintViolationTranslatesToConflict() {
        when(merchantRepository.save(any(Merchant.class))).thenThrow(
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_merchants_keycloak_sub\"")
        );

        var request = new MerchantRegistrationRequest("Lanka Retail Holdings Ltd", "PV-109923");

        assertThatThrownBy(() -> enrollmentService.enrollMerchant(customerIdentity, request))
                .isInstanceOf(MerchantConflictException.class)
                .hasMessageContaining("Authenticated user already owns a merchant tenant");
    }

    @Test
    @DisplayName("7. Database unique constraint on registration_number translates to MerchantConflictException")
    void dbRegistrationNumberConstraintViolationTranslatesToConflict() {
        when(merchantRepository.save(any(Merchant.class))).thenThrow(
                new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_merchants_registration_number\"")
        );

        var request = new MerchantRegistrationRequest("Lanka Retail Holdings Ltd", "PV-109923");

        assertThatThrownBy(() -> enrollmentService.enrollMerchant(customerIdentity, request))
                .isInstanceOf(MerchantConflictException.class)
                .hasMessageContaining("Merchant registration number already exists: PV-109923");
    }

    @Test
    @DisplayName("8. Unrelated database error propagates without being converted to conflict")
    void unrelatedDatabaseErrorPropagates() {
        when(merchantRepository.save(any(Merchant.class))).thenThrow(
                new DataIntegrityViolationException("deadlock detected or table unavailable")
        );

        var request = new MerchantRegistrationRequest("Lanka Retail Holdings Ltd", "PV-109923");

        assertThatThrownBy(() -> enrollmentService.enrollMerchant(customerIdentity, request))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("deadlock detected");
    }

    @Test
    @DisplayName("9. Null or empty caller identity is rejected")
    void nullIdentityRejected() {
        var request = new MerchantRegistrationRequest("Lanka Retail Holdings Ltd", "PV-109923");

        assertThatThrownBy(() -> enrollmentService.enrollMerchant(null, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Authenticated caller identity is required");

        assertThatThrownBy(() -> new TrustedIdentity("", "email@swiftdrop.test", "Name", List.of("CUSTOMER")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Verified subject is required");
    }

    @Test
    @DisplayName("10. Null request is rejected")
    void nullRequestRejected() {
        assertThatThrownBy(() -> enrollmentService.enrollMerchant(customerIdentity, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Registration request is required");
    }

    @Test
    @DisplayName("11. Input strings are stripped before persistence")
    void inputsAreStripped() {
        var request = new MerchantRegistrationRequest("  Lanka Retail Holdings Ltd  ", "  PV-109923  ");

        var response = enrollmentService.enrollMerchant(customerIdentity, request);

        assertThat(response.businessName()).isEqualTo("Lanka Retail Holdings Ltd");
        assertThat(response.registrationNumber()).isEqualTo("PV-109923");
    }
}
