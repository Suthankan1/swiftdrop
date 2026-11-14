package com.swiftdrop.accountsservice.profile;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    public record UpdateMyDetailsRequest(String fullName, String phoneNumber) { }

    private static final java.util.regex.Pattern PHONE = java.util.regex.Pattern.compile("^\\+?[0-9\\s\\-()]{7,25}$");

    /**
     * Updates contact details (full name and/or phone number) for the authenticated current user.
     */
    @PatchMapping("/api/v1/users/me")
    public ResponseEntity<CurrentUserProfile> updateMyDetails(@RequestBody(required = false) UpdateMyDetailsRequest request) {
        var identity = identityProvider.currentIdentity();
        if (identity == null || identity.subject() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (request == null || (request.fullName() == null && request.phoneNumber() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide fullName and/or phoneNumber");
        }
        if (request.fullName() != null) {
            String name = request.fullName().trim();
            if (name.isEmpty() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Full name must be 1 to 255 characters without control characters");
            }
        }
        if (request.phoneNumber() != null) {
            String phone = request.phoneNumber().trim();
            if (!phone.isEmpty() && !PHONE.matcher(phone).matches()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone number must be 7 to 25 digits, spaces, dashes or brackets, optionally starting with +");
            }
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(profiles.updateContactDetails(identity, request.fullName(), request.phoneNumber()));
    }

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
