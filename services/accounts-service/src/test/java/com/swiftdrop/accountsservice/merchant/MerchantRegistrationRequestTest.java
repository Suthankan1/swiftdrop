package com.swiftdrop.accountsservice.merchant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantRegistrationRequestTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("Valid business details construct MerchantRegistrationRequest successfully")
    void validRequestConstructs() {
        var request = new MerchantRegistrationRequest("Lanka Retail Holdings Ltd", "PV-109923");
        assertThat(request.businessName()).isEqualTo("Lanka Retail Holdings Ltd");
        assertThat(request.registrationNumber()).isEqualTo("PV-109923");
    }

    @Test
    @DisplayName("Business name with fewer than 3 non-whitespace characters is rejected")
    void shortBusinessNameRejected() {
        assertThatThrownBy(() -> new MerchantRegistrationRequest("ab", "PV-109923"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Business name must have at least 3 non-whitespace characters");

        assertThatThrownBy(() -> new MerchantRegistrationRequest("  a  ", "PV-109923"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Business name must have at least 3 non-whitespace characters");
    }

    @Test
    @DisplayName("Blank or whitespace-only business name is rejected")
    void blankBusinessNameRejected() {
        assertThatThrownBy(() -> new MerchantRegistrationRequest("   ", "PV-109923"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Registration number with fewer than 3 non-whitespace characters is rejected")
    void shortRegistrationNumberRejected() {
        assertThatThrownBy(() -> new MerchantRegistrationRequest("Lanka Retail Holdings", "12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Registration number must have at least 3 non-whitespace characters");

        assertThatThrownBy(() -> new MerchantRegistrationRequest("Lanka Retail Holdings", "  x  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Blank or whitespace-only registration number is rejected")
    void blankRegistrationNumberRejected() {
        assertThatThrownBy(() -> new MerchantRegistrationRequest("Lanka Retail Holdings", "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("JSON deserialization accepts valid business fields")
    void jsonDeserializationAcceptsValidFields() throws Exception {
        String json = """
                {
                    "businessName": "Store Direct LK",
                    "registrationNumber": "PV-654321"
                }
                """;
        var request = jsonMapper.readValue(json, MerchantRegistrationRequest.class);
        assertThat(request.businessName()).isEqualTo("Store Direct LK");
        assertThat(request.registrationNumber()).isEqualTo("PV-654321");
    }

    @Test
    @DisplayName("Strict JSON policy rejects client-supplied privileged status field")
    void jsonDeserializationRejectsPrivilegedStatus() {
        String json = """
                {
                    "businessName": "Store Direct LK",
                    "registrationNumber": "PV-654321",
                    "status": "APPROVED"
                }
                """;
        assertThatThrownBy(() -> jsonMapper.readValue(json, MerchantRegistrationRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("status");
    }

    @Test
    @DisplayName("Strict JSON policy rejects client-supplied privileged roles field")
    void jsonDeserializationRejectsPrivilegedRoles() {
        String json = """
                {
                    "businessName": "Store Direct LK",
                    "registrationNumber": "PV-654321",
                    "roles": ["MERCHANT", "ADMIN"]
                }
                """;
        assertThatThrownBy(() -> jsonMapper.readValue(json, MerchantRegistrationRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("roles");
    }

    @Test
    @DisplayName("Strict JSON policy rejects client-supplied tenant identity fields")
    void jsonDeserializationRejectsTenantIdentityFields() {
        String jsonWithId = """
                {
                    "businessName": "Store Direct LK",
                    "registrationNumber": "PV-654321",
                    "merchantId": "11111111-1111-1111-1111-111111111111"
                }
                """;
        assertThatThrownBy(() -> jsonMapper.readValue(jsonWithId, MerchantRegistrationRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("merchantId");

        String jsonWithSub = """
                {
                    "businessName": "Store Direct LK",
                    "registrationNumber": "PV-654321",
                    "keycloakSub": "forged-subject-override"
                }
                """;
        assertThatThrownBy(() -> jsonMapper.readValue(jsonWithSub, MerchantRegistrationRequest.class))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining("keycloakSub");
    }
}
