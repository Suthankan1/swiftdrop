package com.swiftdrop.accountsservice.staff;

import com.swiftdrop.accountsservice.identity.TrustedIdentity;
import com.swiftdrop.accountsservice.profile.AccountProfile;
import com.swiftdrop.accountsservice.profile.AccountProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service providing staff business profile resolution and controlled administrative provisioning.
 * Enforces strict role validation, status checks, and data ownership boundaries.
 */
@Service
@Transactional(readOnly = true)
public class StaffProfileService implements StaffProfileProvider {

    private final StaffProfileRepository staffProfiles;
    private final AccountProfileRepository accountProfiles;

    public StaffProfileService(StaffProfileRepository staffProfiles) {
        this(staffProfiles, null);
    }

    @Autowired
    public StaffProfileService(StaffProfileRepository staffProfiles,
                               @Autowired(required = false) AccountProfileRepository accountProfiles) {
        this.staffProfiles = Objects.requireNonNull(staffProfiles, "StaffProfileRepository is required");
        this.accountProfiles = accountProfiles;
    }

    @Override
    public StaffProfileResponse getStaffProfile(TrustedIdentity identity) {
        if (identity == null || identity.subject() == null || identity.subject().isBlank()) {
            throw new StaffAccessDeniedException("Authenticated identity is required");
        }

        if (!StaffRole.hasAnyStaffRole(identity.roles())) {
            throw new StaffAccessDeniedException(
                    "Access denied: Caller lacks required staff role. Allowed: COURIER, DISPATCHER, ADMIN. Caller roles: " + identity.roles()
            );
        }

        StaffProfile profile = staffProfiles.findByKeycloakSub(identity.subject())
                .orElseThrow(() -> new StaffProfileNotFoundException(
                        "No staff business profile found for authenticated subject: " + identity.subject()
                ));

        // Enforce role alignment: caller's Keycloak roles must contain the profile's assigned role
        if (!identity.roles().contains(profile.getRole().name())) {
            throw new StaffRoleMismatchException(
                    "Role mismatch: Authenticated token roles " + identity.roles()
                            + " do not match profile staff role " + profile.getRole().name()
            );
        }

        // Enforce active business status
        if (!profile.getStatus().isActive()) {
            throw new StaffProfileInactiveException(
                    profile.getStatus(),
                    "Staff profile is " + profile.getStatus() + ". Operational access is denied."
            );
        }

        return mapToResponse(profile, identity);
    }

    @Override
    public Optional<StaffProfileResponse> findProfileBySubject(String keycloakSub) {
        if (keycloakSub == null || keycloakSub.isBlank()) {
            return Optional.empty();
        }
        return staffProfiles.findByKeycloakSub(keycloakSub)
                .map(p -> mapToResponse(p, null));
    }

    @Override
    public Optional<StaffProfileResponse> findProfileByStaffId(String staffId) {
        if (staffId == null || staffId.isBlank()) {
            return Optional.empty();
        }
        return staffProfiles.findByStaffId(staffId)
                .map(p -> mapToResponse(p, null));
    }

    @Override
    public Optional<StaffProfileResponse> findProfileById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return staffProfiles.findById(id)
                .map(p -> mapToResponse(p, null));
    }

    /**
     * Controlled administrative provisioning operation.
     * Accessible only to authenticated platform administrators.
     * Staff identities cannot be provisioned via public registration.
     */
    @Transactional
    public StaffProfileResponse provisionStaff(TrustedIdentity adminIdentity, ProvisionStaffCommand command) {
        if (adminIdentity == null || !adminIdentity.roles().contains("ADMIN")) {
            throw new StaffAccessDeniedException("Staff provisioning requires authenticated ADMIN role");
        }
        Objects.requireNonNull(command, "ProvisionStaffCommand is required");

        if (staffProfiles.existsByKeycloakSub(command.keycloakSub())) {
            throw new StaffProvisioningException("Staff profile already exists for subject: " + command.keycloakSub());
        }
        if (staffProfiles.existsByStaffId(command.staffId())) {
            throw new StaffProvisioningException("Staff ID is already assigned: " + command.staffId());
        }

        UUID profileId = UUID.randomUUID();
        Instant now = Instant.now();

        StaffProfile profile = new StaffProfile(
                profileId,
                command.accountProfileId(),
                command.keycloakSub(),
                command.staffId(),
                command.role(),
                command.department(),
                command.assignedZone(),
                StaffStatus.ACTIVE,
                now,
                now
        );

        StaffProfile saved = staffProfiles.save(profile);
        return mapToResponse(saved, null);
    }

    private StaffProfileResponse mapToResponse(StaffProfile profile, TrustedIdentity identity) {
        String displayName = identity != null && identity.fullName() != null ? identity.fullName() : "";
        String email = identity != null && identity.email() != null ? identity.email() : "";
        String phoneNumber = "";

        if (accountProfiles != null) {
            Optional<AccountProfile> accountProfile = accountProfiles.findByKeycloakSub(profile.getKeycloakSub());
            if (accountProfile.isPresent()) {
                AccountProfile ap = accountProfile.get();
                if (ap.getFullName() != null && !ap.getFullName().isBlank()) {
                    displayName = ap.getFullName();
                }
                if (ap.getEmail() != null && !ap.getEmail().isBlank()) {
                    email = ap.getEmail();
                }
                if (ap.getPhoneNumber() != null && !ap.getPhoneNumber().isBlank()) {
                    phoneNumber = ap.getPhoneNumber();
                }
            }
        }

        return new StaffProfileResponse(
                profile.getId(),
                profile.getStaffId(),
                profile.getKeycloakSub(),
                displayName,
                email,
                phoneNumber,
                profile.getRole(),
                profile.getDepartment(),
                profile.getAssignedZone(),
                profile.getStatus(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}
