package com.devarom.accountsservice.profile;

import com.devarom.accountsservice.identity.CurrentIdentityProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CurrentUserProfileController {
    private final CurrentIdentityProvider identityProvider;
    private final CurrentUserProfileService profiles;

    public CurrentUserProfileController(CurrentIdentityProvider identityProvider, CurrentUserProfileService profiles) {
        this.identityProvider = identityProvider;
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/users/me")
    public ResponseEntity<CurrentUserProfile> currentUser() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(profiles.getOrCreate(identityProvider.currentIdentity()));
    }
}
