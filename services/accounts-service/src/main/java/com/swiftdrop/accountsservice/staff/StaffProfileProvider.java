package com.swiftdrop.accountsservice.staff;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;

import java.util.Optional;
import java.util.UUID;

/**
 * Provider interface for resolving staff business profiles and enforcing staff security boundaries.
 */
public interface StaffProfileProvider {

    /**
     * Resolves and validates the staff profile for the authenticated identity.
     * Enforces role requirements, role alignment, and active business status.
     *
     * @param identity verified caller identity from OAuth2 JWT
     * @return populated StaffProfileResponse
     * @throws StaffAccessDeniedException if identity lacks a staff role
     * @throws StaffProfileNotFoundException if no staff profile exists for the subject
     * @throws StaffRoleMismatchException if token role does not match profile role
     * @throws StaffProfileInactiveException if staff profile status is not ACTIVE
     */
    StaffProfileResponse getStaffProfile(TrustedIdentity identity);

    /**
     * Resolves staff profile by Keycloak subject without throwing if absent.
     */
    Optional<StaffProfileResponse> findProfileBySubject(String keycloakSub);

    /**
     * Resolves staff profile by staffId (e.g. STF-2026-0042) without throwing if absent.
     */
    Optional<StaffProfileResponse> findProfileByStaffId(String staffId);

    /**
     * Resolves staff profile by internal UUID without throwing if absent.
     */
    Optional<StaffProfileResponse> findProfileById(UUID id);
}
