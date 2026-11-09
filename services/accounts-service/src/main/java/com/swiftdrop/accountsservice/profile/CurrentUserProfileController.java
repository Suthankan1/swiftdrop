package com.swiftdrop.accountsservice.profile;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentUserProfileController {
    private final CurrentIdentityProvider identityProvider;
    private final CurrentUserProfileService profiles;
    private final AccountProfileRepository accounts;

    public CurrentUserProfileController(CurrentIdentityProvider identityProvider, CurrentUserProfileService profiles) {
        this(identityProvider, profiles, (AccountProfileRepository) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CurrentUserProfileController(CurrentIdentityProvider identityProvider, CurrentUserProfileService profiles,
                                        org.springframework.beans.factory.ObjectProvider<AccountProfileRepository> accounts) {
        this(identityProvider, profiles, accounts.getIfAvailable());
    }

    CurrentUserProfileController(CurrentIdentityProvider identityProvider, CurrentUserProfileService profiles,
                                 AccountProfileRepository accounts) {
        this.identityProvider = identityProvider;
        this.profiles = profiles;
        this.accounts = accounts;
    }

    public record AccessStatus(boolean disabled) { }

    /**
     * Whether an admin has disabled the caller's sign-in. Read by the gateway with the caller's own token so a
     * disabled user's remaining access tokens stop working at once. Never creates a profile.
     */
    @GetMapping("/api/v1/users/me/access")
    public ResponseEntity<AccessStatus> access() {
        String subject = identityProvider.currentIdentity().subject();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new AccessStatus(accounts != null && accounts.isSignInDisabled(subject)));
    }

    @GetMapping("/api/v1/users/me")
    public ResponseEntity<CurrentUserProfile> currentUser() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(profiles.getOrCreate(identityProvider.currentIdentity()));
    }
}
