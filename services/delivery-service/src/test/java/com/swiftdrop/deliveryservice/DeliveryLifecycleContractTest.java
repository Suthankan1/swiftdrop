package com.swiftdrop.deliveryservice;

import com.swiftdrop.deliveryservice.core.ParcelStatus;
import com.swiftdrop.deliveryservice.status.InvalidTransitionException;
import com.swiftdrop.deliveryservice.status.ParcelLifecycleTransitionContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryLifecycleContractTest {

    @Test
    @DisplayName("Permitted forward lifecycle transitions pass validation")
    void testPermittedForwardTransitions() {
        UUID proofId = UUID.randomUUID();

        // CREATED -> PICKED_UP
        assertThat(ParcelLifecycleTransitionContract.isTransitionPermitted(ParcelStatus.CREATED, ParcelStatus.PICKED_UP)).isTrue();
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.CREATED, ParcelStatus.PICKED_UP, null, null))
                .doesNotThrowAnyException();

        // PICKED_UP -> IN_TRANSIT
        assertThat(ParcelLifecycleTransitionContract.isTransitionPermitted(ParcelStatus.PICKED_UP, ParcelStatus.IN_TRANSIT)).isTrue();
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.PICKED_UP, ParcelStatus.IN_TRANSIT, null, null))
                .doesNotThrowAnyException();

        // IN_TRANSIT -> OUT_FOR_DELIVERY
        assertThat(ParcelLifecycleTransitionContract.isTransitionPermitted(ParcelStatus.IN_TRANSIT, ParcelStatus.OUT_FOR_DELIVERY)).isTrue();
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.IN_TRANSIT, ParcelStatus.OUT_FOR_DELIVERY, null, null))
                .doesNotThrowAnyException();

        // OUT_FOR_DELIVERY -> DELIVERED (requires proofId)
        assertThat(ParcelLifecycleTransitionContract.isTransitionPermitted(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED)).isTrue();
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED, null, proofId))
                .doesNotThrowAnyException();

        // OUT_FOR_DELIVERY -> FAILED (requires approved reason)
        assertThat(ParcelLifecycleTransitionContract.isTransitionPermitted(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED)).isTrue();
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.OUT_FOR_DELIVERY,
                ParcelStatus.FAILED,
                ParcelLifecycleTransitionContract.REASON_RECIPIENT_UNAVAILABLE,
                null
        )).doesNotThrowAnyException();

        // Early cancellation: CREATED -> FAILED
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.CREATED,
                ParcelStatus.FAILED,
                ParcelLifecycleTransitionContract.REASON_CANCELLED_BY_SENDER,
                null
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Forbidden backward transitions, stage skips, and terminal mutations are rejected")
    void testForbiddenTransitions() {
        // Backward transition: IN_TRANSIT -> PICKED_UP
        assertThat(ParcelLifecycleTransitionContract.isTransitionPermitted(ParcelStatus.IN_TRANSIT, ParcelStatus.PICKED_UP)).isFalse();
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.IN_TRANSIT, ParcelStatus.PICKED_UP, null, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("Illegal transition");

        // Stage skip: CREATED -> DELIVERED
        assertThat(ParcelLifecycleTransitionContract.isTransitionPermitted(ParcelStatus.CREATED, ParcelStatus.DELIVERED)).isFalse();
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.CREATED, ParcelStatus.DELIVERED, null, UUID.randomUUID()))
                .isInstanceOf(InvalidTransitionException.class);

        // Terminal state immutability: DELIVERED -> OUT_FOR_DELIVERY
        assertThat(ParcelStatus.DELIVERED.isTerminal()).isTrue();
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.DELIVERED, ParcelStatus.OUT_FOR_DELIVERY, null, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("terminal state");

        // Terminal state immutability: FAILED -> DELIVERED
        assertThat(ParcelStatus.FAILED.isTerminal()).isTrue();
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.FAILED, ParcelStatus.DELIVERED, null, UUID.randomUUID()))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("terminal state");
    }

    @Test
    @DisplayName("FAILED transition requires mandatory approved reason")
    void testFailedTransitionReasonRequirements() {
        // Missing reason
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED, null, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("Failure reason is mandatory");

        // Blank reason
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED, "   ", null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("Failure reason is mandatory");

        // Unapproved reason
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED, "DRIVER_TIRED", null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("Unapproved failure reason");
    }

    @Test
    @DisplayName("Stage-specific failure reasons are strictly enforced per Runtime Decision Register v1.3")
    void testStageSpecificFailureReasons() {
        // CREATED -> FAILED
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.CREATED, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_CANCELLED_BY_SENDER, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.CREATED, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_PICKUP_FAILED_SENDER_ABSENT, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.CREATED, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_RECIPIENT_UNAVAILABLE, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("not permitted when transitioning from stage CREATED");

        // PICKED_UP -> FAILED
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.PICKED_UP, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_PARCEL_DAMAGED, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.PICKED_UP, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_FORCE_MAJEURE, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.PICKED_UP, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_CANCELLED_BY_SENDER, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("not permitted when transitioning from stage PICKED_UP");

        // IN_TRANSIT -> FAILED
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.IN_TRANSIT, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_PARCEL_DAMAGED, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.IN_TRANSIT, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_FORCE_MAJEURE, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.IN_TRANSIT, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_REFUSED_BY_RECIPIENT, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("not permitted when transitioning from stage IN_TRANSIT");

        // OUT_FOR_DELIVERY -> FAILED
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_RECIPIENT_UNAVAILABLE, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_INCORRECT_ADDRESS, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_REFUSED_BY_RECIPIENT, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(
                ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.FAILED, ParcelLifecycleTransitionContract.REASON_CANCELLED_BY_SENDER, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("not permitted when transitioning from stage OUT_FOR_DELIVERY");
    }

    @Test
    @DisplayName("DELIVERED transition requires mandatory proofId")
    void testDeliveredTransitionProofRequirement() {
        assertThatThrownBy(() -> ParcelLifecycleTransitionContract.validateTransition(ParcelStatus.OUT_FOR_DELIVERY, ParcelStatus.DELIVERED, null, null))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("Proof of delivery (proofId) is mandatory");
    }
}
