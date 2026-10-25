package com.swiftdrop.accountsservice.merchant;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Strict JSON deserializer for MerchantRegistrationRequest.
 * Enforces strict input validation by rejecting client-controlled privileged fields
 * (such as status, roles, merchantId, accountProfileId, keycloakSub) and any unknown fields.
 */
public class MerchantRegistrationRequestDeserializer extends StdDeserializer<MerchantRegistrationRequest> {

    private static final Set<String> ALLOWED_PROPERTIES = Set.of("businessName", "registrationNumber");

    public MerchantRegistrationRequestDeserializer() {
        super(MerchantRegistrationRequest.class);
    }

    @Override
    public MerchantRegistrationRequest deserialize(JsonParser parser, DeserializationContext ctxt) {
        JsonNode node = parser.readValueAsTree();
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Expected JSON object for merchant registration request");
        }

        for (String field : node.propertyNames()) {
            if (!ALLOWED_PROPERTIES.contains(field)) {
                throw UnrecognizedPropertyException.from(
                        parser,
                        MerchantRegistrationRequest.class,
                        field,
                        (Collection) ALLOWED_PROPERTIES
                );
            }
        }

        JsonNode businessNameNode = node.get("businessName");
        String businessName = businessNameNode != null && !businessNameNode.isNull() ? businessNameNode.asText() : null;

        JsonNode regNumberNode = node.get("registrationNumber");
        String registrationNumber = regNumberNode != null && !regNumberNode.isNull() ? regNumberNode.asText() : null;

        return new MerchantRegistrationRequest(businessName, registrationNumber);
    }
}
