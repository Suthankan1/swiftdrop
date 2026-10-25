package com.swiftdrop.deliveryservice.assignment;

/**
 * Port contract for verifying courier dispatch eligibility via Accounts Service (IF-GRPC-01).
 */
public interface CourierEligibilityClient {

    /**
     * Checks whether the specified courier is active and eligible for assignment.
     *
     * @param courierSub Keycloak subject identifying the courier
     * @return CourierEligibilityResult describing status and any rejection reason
     */
    CourierEligibilityResult checkEligibility(String courierSub);
}
