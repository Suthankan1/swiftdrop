package com.swiftdrop.accountsservice.profile;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.merchant.MerchantTenantAuthorizationProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class CurrentUserProfileService {
    private final AccountProfileRepository profiles;
    private final MerchantTenantAuthorizationProvider merchantProvider;

    public CurrentUserProfileService(AccountProfileRepository profiles) {
        this(profiles, null);
    }

    @Autowired
    public CurrentUserProfileService(AccountProfileRepository profiles,
                                     MerchantTenantAuthorizationProvider merchantProvider) {
        this.profiles = Objects.requireNonNull(profiles, "AccountProfileRepository is required");
        this.merchantProvider = merchantProvider;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CurrentUserProfile getOrCreate(TrustedIdentity identity) {
        Objects.requireNonNull(identity, "Trusted identity is required");
        var profile = profiles.findByKeycloakSub(identity.subject()).orElseGet(() -> {
            profiles.insertIfAbsent(identity.subject(), identity.email(), identity.fullName());
            // A separate statement under READ COMMITTED sees the concurrent winner after
            // ON CONFLICT has waited for its commit. No aborted transaction/retry loop.
            return profiles.findByKeycloakSub(identity.subject())
                    .orElseThrow(() -> new IllegalStateException("Profile creation did not persist"));
        });

        CurrentUserProfile.MerchantProfile merchantProfile = null;
        if (merchantProvider != null && identity.roles().contains("MERCHANT")) {
            merchantProfile = merchantProvider.resolveMerchantForIdentity(identity)
                    .map(m -> new CurrentUserProfile.MerchantProfile(
                            m.id().toString(),
                            m.businessName(),
                            m.registrationNumber(),
                            m.status().name()))
                    .orElse(null);
        }

        return new CurrentUserProfile(profile.getId(), profile.getKeycloakSub(), profile.getEmail(),
                profile.getFullName(), profile.getPhoneNumber(), identity.roles(), merchantProfile, profile.getCreatedAt());
    }
}
