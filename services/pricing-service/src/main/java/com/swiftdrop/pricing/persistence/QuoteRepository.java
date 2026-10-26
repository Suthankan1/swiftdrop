package com.swiftdrop.pricing.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuoteRepository extends JpaRepository<QuoteEntity, UUID> {

    Optional<QuoteEntity> findById(UUID id);
}
