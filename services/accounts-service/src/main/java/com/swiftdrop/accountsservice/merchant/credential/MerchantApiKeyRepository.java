package com.swiftdrop.accountsservice.merchant.credential;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantApiKeyRepository extends JpaRepository<MerchantApiKey, UUID> {

    List<MerchantApiKey> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId);

    Optional<MerchantApiKey> findByIdAndMerchantId(UUID id, UUID merchantId);

    Optional<MerchantApiKey> findByKeyDigest(String keyDigest);

    List<MerchantApiKey> findByKeyPrefix(String keyPrefix);

    @Query("SELECT COUNT(k) FROM MerchantApiKey k WHERE k.merchantId = :merchantId AND k.isRevoked = false AND k.expiresAt > :now")
    long countActiveKeys(@Param("merchantId") UUID merchantId, @Param("now") Instant now);
}
