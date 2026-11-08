package com.swiftdrop.deliveryservice.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Deletes idempotency records past their 24-hour window so the table does not grow without bound. */
@Component
public class IdempotencyRecordPurger {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRecordPurger.class);

    private final IdempotencyRecordRepository repository;

    public IdempotencyRecordPurger(IdempotencyRecordRepository repository) {
        this.repository = repository;
    }

    @Scheduled(fixedDelayString = "${IDEMPOTENCY_PURGE_INTERVAL_MS:3600000}",
            initialDelayString = "${IDEMPOTENCY_PURGE_INTERVAL_MS:3600000}")
    public void purgeExpired() {
        int removed = repository.deleteExpired(Instant.now());
        if (removed > 0) {
            log.info("Purged {} expired idempotency records", removed);
        }
    }
}
