package com.swiftdrop.accountsservice.profile;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountProfileRepository extends Repository<AccountProfile, UUID> {
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
}
