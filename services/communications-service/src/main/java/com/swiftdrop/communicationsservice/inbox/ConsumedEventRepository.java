package com.swiftdrop.communicationsservice.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, ConsumedEventId> {

    boolean existsByIdConsumerNameAndIdEventId(String consumerName, UUID eventId);

    Optional<ConsumedEvent> findByIdConsumerNameAndIdEventId(String consumerName, UUID eventId);

    long countByIdConsumerName(String consumerName);

    @Transactional
    @Modifying
    @Query("""
        DELETE FROM ConsumedEvent e
        WHERE e.processedAt IS NOT NULL
          AND e.processedAt < :cutoff
    """)
    int deleteProcessedEventsOlderThan(@Param("cutoff") Instant cutoff);
}
