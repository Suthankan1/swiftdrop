package com.swiftdrop.accountsservice.security;

import com.swiftdrop.accountsservice.identity.CurrentIdentityProvider;
import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Resolves trusted identity exclusively from the authenticated and verified Spring Security context.
 * Never inspects HTTP request headers, query parameters, or request bodies.
 */
public class SecurityContextCurrentIdentityProvider implements CurrentIdentityProvider {

    @Override
    public TrustedIdentity currentIdentity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Verified identity is unavailable");
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof Jwt jwt)) {
            throw new AuthenticationCredentialsNotFoundException("Verified JWT principal is required");
        }

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255 || subject.indexOf('\0') >= 0) {
            throw new BadCredentialsException("Verified subject is required and must fit the profile binding");
        }

        String email = jwt.getClaimAsString("email");
        if (email != null && (email.length() > 320 || email.indexOf('\0') >= 0)) {
            throw new BadCredentialsException("Verified email claim exceeds supported format");
        }

        String fullName = jwt.getClaimAsString("name");
        if (fullName == null || fullName.isBlank()) {
            String preferredUsername = jwt.getClaimAsString("preferred_username");
            if (preferredUsername != null && !preferredUsername.isBlank()) {
                fullName = preferredUsername;
            }
        }
        if (fullName != null && (fullName.length() > 255 || fullName.indexOf('\0') >= 0)) {
            throw new BadCredentialsException("Verified full name claim exceeds supported format");
        }

        List<String> approvedRoles = KeycloakRoleExtractor.extractApprovedRoles(jwt);

        return new TrustedIdentity(subject, email, fullName, approvedRoles);
    }
}
