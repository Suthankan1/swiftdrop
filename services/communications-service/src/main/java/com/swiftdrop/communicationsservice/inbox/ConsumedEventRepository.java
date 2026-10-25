package com.swiftdrop.communicationsservice.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, ConsumedEventId> {

    boolean existsByIdConsumerNameAndIdEventId(String consumerName, UUID eventId);

    Optional<ConsumedEvent> findByIdConsumerNameAndIdEventId(String consumerName, UUID eventId);
}
