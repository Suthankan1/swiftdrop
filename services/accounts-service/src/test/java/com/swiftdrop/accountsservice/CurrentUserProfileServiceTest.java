package com.swiftdrop.accountsservice;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.merchant.MerchantRecord;
import com.swiftdrop.accountsservice.merchant.MerchantStatus;
import com.swiftdrop.accountsservice.merchant.MerchantTenantAuthorizationProvider;
import com.swiftdrop.accountsservice.profile.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentUserProfileServiceTest {
    private final AccountProfileRepository repository = mock(AccountProfileRepository.class);
    private final MerchantTenantAuthorizationProvider merchantProvider = mock(MerchantTenantAuthorizationProvider.class);
    private final CurrentUserProfileService service = new CurrentUserProfileService(repository, merchantProvider);
    private final TrustedIdentity identity = new TrustedIdentity("synthetic-sub", null, null, List.of("CUSTOMER"));

    private AccountProfile persisted() {
        return persisted(identity.subject());
    }

    private AccountProfile persisted(String sub) {
        var profile = mock(AccountProfile.class);
        when(profile.getId()).thenReturn(UUID.fromString("99040f35-9241-4f6b-a7a8-8049219a1b78"));
        when(profile.getKeycloakSub()).thenReturn(sub);
        when(profile.getEmail()).thenReturn("");
        when(profile.getFullName()).thenReturn("");
        when(profile.getPhoneNumber()).thenReturn("");
        when(profile.getCreatedAt()).thenReturn(Instant.parse("2026-10-16T00:00:00Z"));
        return profile;
    }

    @Test
    void returnsExistingProfileWithoutWriting() {
        var profile = persisted();
        when(repository.findByKeycloakSub(identity.subject())).thenReturn(Optional.of(profile));
        var result = service.getOrCreate(identity);
        assertThat(result.keycloakSub()).isEqualTo(identity.subject());
        assertThat(result.roles()).containsExactly("CUSTOMER");
        assertThat(result.merchantProfile()).isNull();
        verify(repository, never()).insertIfAbsent(anyString(), anyString(), anyString());
    }

    @Test
    void createsWithoutCallerSuppliedIdThenReloads() {
        var profile = persisted();
        when(repository.findByKeycloakSub(identity.subject())).thenReturn(Optional.empty(), Optional.of(profile));
        assertThat(service.getOrCreate(identity).id()).isNotNull();
        verify(repository).insertIfAbsent(identity.subject(), "", "");
        verify(repository, times(2)).findByKeycloakSub(identity.subject());
    }

    @Test
    void reloadsConcurrentWinnerWhenInsertDoesNothing() {
        var profile = persisted();
        when(repository.findByKeycloakSub(identity.subject())).thenReturn(Optional.empty(), Optional.of(profile));
        when(repository.insertIfAbsent(anyString(), anyString(), anyString())).thenReturn(0);
        assertThat(service.getOrCreate(identity).keycloakSub()).isEqualTo(identity.subject());
    }

    @Test
    void databaseFailuresAreNotSwallowedOrRetried() {
        when(repository.findByKeycloakSub(identity.subject())).thenReturn(Optional.empty());
        var failure = new DataAccessResourceFailureException("synthetic failure");
        when(repository.insertIfAbsent(anyString(), anyString(), anyString())).thenThrow(failure);
        assertThatThrownBy(() -> service.getOrCreate(identity)).isSameAs(failure);
        verify(repository).insertIfAbsent(anyString(), anyString(), anyString());
    }

    @Test
    void missingReloadIsAnErrorNotAFabricatedSuccess() {
        when(repository.findByKeycloakSub(identity.subject())).thenReturn(Optional.empty());
        assertThatIllegalStateException().isThrownBy(() -> service.getOrCreate(identity));
    }

    @Test
    void nullIdentityNeverReachesPersistence() {
        assertThatNullPointerException().isThrownBy(() -> service.getOrCreate(null));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Populates merchantProfile when caller has MERCHANT role and merchant is bound")
    void populatesMerchantProfileForMerchantRole() {
        var merchantIdentity = new TrustedIdentity("merchant-sub", "m@swiftdrop.test", "Merchant Owner", List.of("MERCHANT"));
        var profile = persisted(merchantIdentity.subject());
        when(repository.findByKeycloakSub(merchantIdentity.subject())).thenReturn(Optional.of(profile));

        var merchantId = UUID.randomUUID();
        var record = new MerchantRecord(
                merchantId, profile.getId(), merchantIdentity.subject(),
                "Store Direct LK", "PV-123456", MerchantStatus.PENDING, Instant.now()
        );
        when(merchantProvider.resolveMerchantForIdentity(merchantIdentity)).thenReturn(Optional.of(record));

        var result = service.getOrCreate(merchantIdentity);
        assertThat(result.merchantProfile()).isNotNull();
        assertThat(result.merchantProfile().merchantId()).isEqualTo(merchantId.toString());
        assertThat(result.merchantProfile().businessName()).isEqualTo("Store Direct LK");
        assertThat(result.merchantProfile().registrationNumber()).isEqualTo("PV-123456");
        assertThat(result.merchantProfile().status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("merchantProfile is null when caller has MERCHANT role but no merchant record exists")
    void merchantProfileIsNullWhenUnboundMerchant() {
        var merchantIdentity = new TrustedIdentity("unbound-merchant-sub", "m@swiftdrop.test", "Merchant Owner", List.of("MERCHANT"));
        var profile = persisted(merchantIdentity.subject());
        when(repository.findByKeycloakSub(merchantIdentity.subject())).thenReturn(Optional.of(profile));
        when(merchantProvider.resolveMerchantForIdentity(merchantIdentity)).thenReturn(Optional.empty());

        var result = service.getOrCreate(merchantIdentity);
        assertThat(result.merchantProfile()).isNull();
    }
}
