package com.swiftdrop.accountsservice.profile;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountProfileRepository extends Repository<AccountProfile, UUID> {

    Page<AccountProfile> findAll(Pageable pageable);

    Page<AccountProfile> findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCase(
            String email, String fullName, Pageable pageable);

    Optional<AccountProfile> findById(UUID id);
    Optional<AccountProfile> findByKeycloakSub(String keycloakSub);

    // Only this named uniqueness boundary is ignored; all other DB failures propagate.
    @Modifying
    @Query(value = """
        INSERT INTO account_profiles (id, keycloak_sub, email, full_name, phone_number, created_at)
        VALUES (gen_random_uuid(), :subject, :email, :fullName, '', CURRENT_TIMESTAMP)
        ON CONFLICT ON CONSTRAINT uq_account_profiles_keycloak_sub DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("subject") String subject, @Param("email") String email,
                       @Param("fullName") String fullName);

    /** Admin correction of contact details; email stays owned by the identity provider. */
    @Modifying
    @Query(value = "UPDATE account_profiles SET full_name = :fullName, phone_number = :phoneNumber WHERE id = :id",
            nativeQuery = true)
    int updateContactDetails(@Param("id") UUID id, @Param("fullName") String fullName,
                             @Param("phoneNumber") String phoneNumber);

    @Modifying
    @Query(value = "UPDATE account_profiles SET sign_in_disabled = :disabled WHERE id = :id", nativeQuery = true)
    int setSignInDisabled(@Param("id") UUID id, @Param("disabled") boolean disabled);

    /** True only for a profile an admin has disabled; unknown subjects are not disabled. */
    @Query(value = "SELECT COALESCE((SELECT sign_in_disabled FROM account_profiles WHERE keycloak_sub = :subject), FALSE)",
            nativeQuery = true)
    boolean isSignInDisabled(@Param("subject") String subject);
}
