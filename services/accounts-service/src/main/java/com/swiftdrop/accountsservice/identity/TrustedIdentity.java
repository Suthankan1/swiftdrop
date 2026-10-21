package com.swiftdrop.accountsservice.identity;

import java.util.List;

/**
 * Internal trust boundary, NEVER an HTTP request DTO. Issue #37 must construct this
 * only after validating JWT signature, issuer, audience, lifetime and role claims.
 * Construction validates shape; it does not authenticate or grant any role.
 */
public record TrustedIdentity(String subject, String email, String fullName, List<String> roles) {
    public TrustedIdentity {
        if (subject == null || subject.isBlank() || subject.length() > 255 || subject.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Verified subject is required and must fit the profile binding");
        }
        // Preserve the opaque subject exactly; do not trim or parse it as a UUID.
        email = optionalClaim(email, 320);
        fullName = optionalClaim(fullName, 255);
        if (roles == null || roles.stream().anyMatch(role -> role == null || role.isBlank())) {
            throw new IllegalArgumentException("Verified roles must be a list of non-blank names");
        }
        roles = List.copyOf(roles);
    }

    private static String optionalClaim(String value, int maxLength) {
        if (value == null) return "";
        if (value.length() > maxLength || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Verified profile attribute exceeds supported format");
        }
        return value;
    }

    @Override
    public String toString() { return "TrustedIdentity[redacted]"; }
}
