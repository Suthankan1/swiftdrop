package com.swiftdrop.security.grpc;

import com.swiftdrop.security.jwt.KeycloakRoleExtractor;
import com.swiftdrop.security.jwt.SubjectValidator;
import com.swiftdrop.security.jwt.SwiftDropRoles;
import com.swiftdrop.security.service.SwiftDropServiceIdentity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.*;

/**
 * Validates delegated actor context against cryptographic authorization proof (either a verified actor JWT
 * or a signed delegation claim inside the service token).
 * Fails closed on missing cryptographic proof, subject forgery, role elevation, or invalid scope.
 */
public class ActorDelegationValidator {

    public record Result(boolean isValid, DelegatedActorContext context, String errorMessage) {
        public static Result success(DelegatedActorContext context) {
            return new Result(true, context, null);
        }

        public static Result failure(String errorMessage) {
            return new Result(false, null, errorMessage);
        }
    }

    private final JwtDecoder actorJwtDecoder;

    public ActorDelegationValidator(JwtDecoder actorJwtDecoder) {
        this.actorJwtDecoder = actorJwtDecoder;
    }

    public ActorDelegationValidator() {
        this(null);
    }

    public Result validateDelegation(
            String actorSubHeader,
            String actorRolesHeader,
            String actorTokenHeader,
            Jwt verifiedServiceJwt,
            SwiftDropServiceIdentity callingService) {

        if (actorSubHeader == null || actorSubHeader.isBlank()) {
            return Result.failure("Missing required delegated actor subject (x-swiftdrop-actor-sub)");
        }

        String sanitizedSub = actorSubHeader.trim();

        // Parse claimed roles from header
        Set<String> claimedRoles = parseRoles(actorRolesHeader);

        // Case A: Cryptographic actor JWT provided via x-swiftdrop-actor-token
        if (actorTokenHeader != null && !actorTokenHeader.isBlank()) {
            if (actorJwtDecoder == null) {
                return Result.failure("Actor JWT validation is not configured on this server");
            }
            try {
                Jwt actorJwt = actorJwtDecoder.decode(actorTokenHeader);

                // Temporal check
                Instant now = Instant.now();
                if (actorJwt.getExpiresAt() != null && now.isAfter(actorJwt.getExpiresAt())) {
                    return Result.failure("Delegated actor JWT has expired");
                }
                if (actorJwt.getNotBefore() != null && now.isBefore(actorJwt.getNotBefore())) {
                    return Result.failure("Delegated actor JWT is not yet valid (nbf)");
                }

                // Verify subject match (anti-spoofing)
                String verifiedSub = actorJwt.getSubject();
                if (!sanitizedSub.equals(verifiedSub)) {
                    return Result.failure("Forged actor subject: header ('" + sanitizedSub +
                            "') does not match verified token subject ('" + verifiedSub + "')");
                }

                // Verify roles (anti-elevation)
                Set<String> verifiedRoles = KeycloakRoleExtractor.extractApprovedRoles(actorJwt);
                for (String claimedRole : claimedRoles) {
                    if (!verifiedRoles.contains(claimedRole)) {
                        return Result.failure("Forged actor role: claimed role '" + claimedRole +
                                "' is not granted in verified actor token (role elevation rejected)");
                    }
                }

                String tenantId = actorJwt.getClaimAsString("merchant_id");
                DelegatedActorContext context = new DelegatedActorContext(verifiedSub, verifiedRoles, callingService, tenantId);
                return Result.success(context);

            } catch (JwtException ex) {
                return Result.failure("Invalid delegated actor JWT: " + ex.getMessage());
            } catch (Exception ex) {
                return Result.failure("Delegated actor token verification failed: " + ex.getMessage());
            }
        }

        // Case B: Signed delegation claims embedded directly in verified service token
        if (verifiedServiceJwt != null) {
            String signedDelegatedSub = verifiedServiceJwt.getClaimAsString("delegated_sub");
            if (signedDelegatedSub == null) {
                signedDelegatedSub = verifiedServiceJwt.getClaimAsString("actor_sub");
            }

            if (signedDelegatedSub != null && !signedDelegatedSub.isBlank()) {
                if (!sanitizedSub.equals(signedDelegatedSub)) {
                    return Result.failure("Forged actor subject: header ('" + sanitizedSub +
                            "') does not match signed service delegation claim ('" + signedDelegatedSub + "')");
                }

                Set<String> signedRoles = new LinkedHashSet<>();
                Object rolesClaim = verifiedServiceJwt.getClaim("delegated_roles");
                if (rolesClaim == null) {
                    rolesClaim = verifiedServiceJwt.getClaim("actor_roles");
                }
                if (rolesClaim instanceof Collection<?> col) {
                    for (Object item : col) {
                        if (item instanceof String r && SwiftDropRoles.isValidRole(r)) {
                            signedRoles.add(r.trim().toUpperCase());
                        }
                    }
                }

                for (String claimedRole : claimedRoles) {
                    if (!signedRoles.contains(claimedRole)) {
                        return Result.failure("Forged actor role: claimed role '" + claimedRole +
                                "' is not present in signed delegation claim");
                    }
                }

                String tenantId = verifiedServiceJwt.getClaimAsString("tenant_id");
                DelegatedActorContext context = new DelegatedActorContext(sanitizedSub, signedRoles, callingService, tenantId);
                return Result.success(context);
            }
        }

        // Case C: Raw forwarding headers without cryptographic proof -> FAIL CLOSED
        return Result.failure("Unverified actor delegation: caller-controlled actor metadata lacks cryptographic proof");
    }

    private static Set<String> parseRoles(String header) {
        if (header == null || header.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> roles = new LinkedHashSet<>();
        String[] parts = header.split(",");
        for (String part : parts) {
            String trimmed = part.trim().toUpperCase();
            if (!trimmed.isEmpty()) {
                roles.add(trimmed);
            }
        }
        return roles;
    }
}
