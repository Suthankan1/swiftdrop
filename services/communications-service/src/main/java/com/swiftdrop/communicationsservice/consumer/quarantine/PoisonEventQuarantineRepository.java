package com.swiftdrop.communicationsservice.consumer.quarantine;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PoisonEventQuarantineRepository extends JpaRepository<PoisonEventQuarantine, UUID> {

    boolean existsByConsumerGroupAndSourceTopicAndSourcePartitionAndSourceOffset(
            String consumerGroup, String sourceTopic, Integer sourcePartition, Long sourceOffset);

    Optional<PoisonEventQuarantine> findByConsumerGroupAndSourceTopicAndSourcePartitionAndSourceOffset(
            String consumerGroup, String sourceTopic, Integer sourcePartition, Long sourceOffset);

    List<PoisonEventQuarantine> findByStatusOrderByQuarantinedAtDesc(String status, Pageable pageable);

    long countByStatus(String status);
}
