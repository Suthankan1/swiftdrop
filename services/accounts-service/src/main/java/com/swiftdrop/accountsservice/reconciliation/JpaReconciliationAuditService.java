package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA-backed implementation of {@link ReconciliationAuditService} that persists
 * reconciliation audit entries to accounts_db with independent transaction propagation
 * so failed business or Keycloak operations are reliably recorded.
 */
@Service
public class JpaReconciliationAuditService implements ReconciliationAuditService {

    private static final Logger log = LoggerFactory.getLogger(JpaReconciliationAuditService.class);

    private final IdentityReconciliationAuditRepository auditRepository;

    public JpaReconciliationAuditService(IdentityReconciliationAuditRepository auditRepository) {
        this.auditRepository = Objects.requireNonNull(auditRepository, "auditRepository is required");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID recordAudit(String targetSub,
                            String targetRole,
                            String action,
                            String statusBefore,
                            String statusAfter,
                            ReconciliationOutcome outcome,
                            String initiatedBy,
                            String correlationId,
                            String errorMessage) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        // Ensure error message is sanitized (no credentials, passwords or sensitive tokens)
        String sanitizedError = sanitizeError(errorMessage);

        IdentityReconciliationAudit audit = new IdentityReconciliationAudit(
                id,
                targetSub,
                targetRole,
                action,
                statusBefore,
                statusAfter,
                outcome,
                initiatedBy,
                correlationId,
                1,
                sanitizedError,
                now,
                now
        );

        auditRepository.save(audit);
        log.info("Recorded reconciliation audit [id={}, sub={}, role={}, action={}, outcome={}, initiatedBy={}]",
                id, targetSub, targetRole, action, outcome, initiatedBy);
        return id;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateAuditOutcome(UUID auditId,
                                   ReconciliationOutcome outcome,
                                   int attemptCount,
                                   String statusAfter,
                                   String errorMessage) {
        Optional<IdentityReconciliationAudit> existing = auditRepository.findById(auditId);
        if (existing.isPresent()) {
            IdentityReconciliationAudit audit = existing.get();
            String sanitizedError = sanitizeError(errorMessage);
            audit.updateOutcome(outcome, attemptCount, statusAfter, sanitizedError, Instant.now());
            auditRepository.save(audit);
            log.info("Updated reconciliation audit [id={}, newOutcome={}, attempts={}]", auditId, outcome, attemptCount);
        } else {
            log.warn("Cannot update non-existent reconciliation audit id: {}", auditId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdentityReconciliationAudit> findById(UUID id) {
        return auditRepository.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public List<IdentityReconciliationAudit> findByTargetSub(String targetSub) {
        return auditRepository.findByTargetSubOrderByCreatedAtDesc(targetSub);
    }

    private String sanitizeError(String error) {
        if (error == null || error.isBlank()) {
            return null;
        }
        // Scrub potential sensitive patterns like client_secret=..., bearer tokens, passwords
        String sanitized = error.replaceAll("(?i)(password|secret|token|client_secret)\\s*[:=]\\s*[^,\\s&]+", "$1=***");
        if (sanitized.length() > 2000) {
            sanitized = sanitized.substring(0, 2000) + "... [truncated]";
        }
        return sanitized;
    }
}
