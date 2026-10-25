package com.swiftdrop.accountsservice.reconciliation;

import com.swiftdrop.accountsservice.identity.keycloak.ReconciliationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JpaReconciliationAuditServiceTest {

    @Mock
    private IdentityReconciliationAuditRepository repository;

    private JpaReconciliationAuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new JpaReconciliationAuditService(repository);
    }

    @Test
    @DisplayName("recordAudit creates and saves audit entity with sanitized error message")
    void recordAuditSavesSanitizedEntity() {
        String sensitiveError = "Failed to authenticate with client_secret=SuperSecretValue123 and token=jwt-token-value";
        UUID id = auditService.recordAudit(
                "sub-123", "COURIER", "PROVISION_STAFF", "NONE", "ACTIVE",
                ReconciliationOutcome.SUCCESS, "admin-sub", "corr-456", sensitiveError
        );

        assertThat(id).isNotNull();

        ArgumentCaptor<IdentityReconciliationAudit> captor = ArgumentCaptor.forClass(IdentityReconciliationAudit.class);
        verify(repository).save(captor.capture());

        IdentityReconciliationAudit saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(id);
        assertThat(saved.getTargetSub()).isEqualTo("sub-123");
        assertThat(saved.getTargetRole()).isEqualTo("COURIER");
        assertThat(saved.getAction()).isEqualTo("PROVISION_STAFF");
        assertThat(saved.getStatusBefore()).isEqualTo("NONE");
        assertThat(saved.getStatusAfter()).isEqualTo("ACTIVE");
        assertThat(saved.getOutcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        assertThat(saved.getInitiatedBy()).isEqualTo("admin-sub");
        assertThat(saved.getCorrelationId()).isEqualTo("corr-456");
        assertThat(saved.getAttemptCount()).isEqualTo(1);

        // Verify credentials scrubbed
        assertThat(saved.getErrorMessage()).doesNotContain("SuperSecretValue123");
        assertThat(saved.getErrorMessage()).contains("client_secret=***");
        assertThat(saved.getErrorMessage()).contains("token=***");
    }

    @Test
    @DisplayName("updateAuditOutcome updates existing audit entity outcome, attempt count, and error")
    void updateAuditOutcomeModifiesEntity() {
        UUID auditId = UUID.randomUUID();
        IdentityReconciliationAudit existing = new IdentityReconciliationAudit(
                auditId, "sub-123", "MERCHANT", "APPROVE_MERCHANT", "PENDING", "PENDING_RETRY",
                ReconciliationOutcome.PENDING_RETRY, "admin-sub", null, 1,
                "Network glitch", Instant.now().minusSeconds(60), Instant.now().minusSeconds(60)
        );
        when(repository.findById(auditId)).thenReturn(Optional.of(existing));

        auditService.updateAuditOutcome(auditId, ReconciliationOutcome.SUCCESS, 2, "APPROVED", null);

        verify(repository).save(existing);
        assertThat(existing.getOutcome()).isEqualTo(ReconciliationOutcome.SUCCESS);
        assertThat(existing.getAttemptCount()).isEqualTo(2);
        assertThat(existing.getStatusAfter()).isEqualTo("APPROVED");
        assertThat(existing.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("findByTargetSub queries repository for audit history")
    void findByTargetSubDelegates() {
        String sub = "target-sub-456";
        when(repository.findByTargetSubOrderByCreatedAtDesc(sub)).thenReturn(List.of());

        List<IdentityReconciliationAudit> result = auditService.findByTargetSub(sub);
        assertThat(result).isEmpty();
        verify(repository).findByTargetSubOrderByCreatedAtDesc(sub);
    }
}
