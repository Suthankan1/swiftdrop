package com.swiftdrop.pricing.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class AdminAuditRepository {
    private final JdbcTemplate jdbc;

    public AdminAuditRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void append(String actor, String operation, String resourceType, UUID resourceId, UUID ruleId,
                       String previousStatus, String newStatus, Long previousRevision, long newRevision,
                       Object previousState, Object newState, Instant at, UUID requestId) {
        jdbc.update("""
                INSERT INTO pricing_admin_audit
                (id, actor_sub, operation, resource_type, resource_id, rule_id, previous_status, new_status,
                 previous_revision, new_revision, previous_state, new_state, occurred_at, request_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor, operation, resourceType, resourceId, ruleId,
                previousStatus, newStatus, previousRevision, newRevision,
                previousState == null ? null : previousState.toString(), newState.toString(), Timestamp.from(at), requestId);
    }
}
