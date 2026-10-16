package com.devarom.accountsservice.profile;

import com.devarom.accountsservice.identity.TrustedIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Service
public class CurrentUserProfileService {
    private final AccountProfileRepository profiles;

    public CurrentUserProfileService(AccountProfileRepository profiles) {
        this.profiles = profiles;
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
        return new CurrentUserProfile(profile.getId(), profile.getKeycloakSub(), profile.getEmail(),
                profile.getFullName(), profile.getPhoneNumber(), identity.roles(), null, profile.getCreatedAt());
    }
}
