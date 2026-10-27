package com.swiftdrop.communicationsservice.webhook.validation;

import com.swiftdrop.communicationsservice.webhook.exception.InvalidEventFilterException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookEventFilterValidatorTest {

    private WebhookEventFilterValidator validator;

    @BeforeEach
    void setUp() {
        validator = new WebhookEventFilterValidator();
    }

    @Test
    @DisplayName("Single approved event type 'parcel.status.changed.v1' is accepted")
    void approvedEventType_Accepted() {
        List<String> input = List.of("parcel.status.changed.v1");
        List<String> validated = validator.validate(input);
        assertThat(validated).containsExactly("parcel.status.changed.v1");
    }

    @Test
    @DisplayName("Duplicate event filters are normalized")
    void duplicateEventFilters_Normalized() {
        List<String> input = List.of("parcel.status.changed.v1", "parcel.status.changed.v1");
        List<String> validated = validator.validate(input);
        assertThat(validated).containsExactly("parcel.status.changed.v1");
    }

    @Test
    @DisplayName("Null event list is rejected")
    void nullEventList_Rejected() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(InvalidEventFilterException.class)
                .hasMessageContaining("At least one supported event type must be specified");
    }

    @Test
    @DisplayName("Empty event list is rejected")
    void emptyEventList_Rejected() {
        assertThatThrownBy(() -> validator.validate(Collections.emptyList()))
                .isInstanceOf(InvalidEventFilterException.class)
                .hasMessageContaining("At least one supported event type must be specified");
    }

    @Test
    @DisplayName("Null or blank entry in event list is rejected")
    void nullOrBlankEntry_Rejected() {
        List<String> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> validator.validate(withNull))
                .isInstanceOf(InvalidEventFilterException.class)
                .hasMessageContaining("must not be null or blank");

        assertThatThrownBy(() -> validator.validate(List.of("   ")))
                .isInstanceOf(InvalidEventFilterException.class)
                .hasMessageContaining("must not be null or blank");
    }

    @Test
    @DisplayName("Unsupported event type is rejected")
    void unsupportedEventType_Rejected() {
        assertThatThrownBy(() -> validator.validate(List.of("order.created.v1")))
                .isInstanceOf(InvalidEventFilterException.class)
                .hasMessageContaining("Unsupported event type: 'order.created.v1'");

        assertThatThrownBy(() -> validator.validate(List.of("parcel.status.changed.v1", "unapproved.event")))
                .isInstanceOf(InvalidEventFilterException.class)
                .hasMessageContaining("Unsupported event type: 'unapproved.event'");
    }

    @Test
    @DisplayName("Serialization and deserialization roundtrip preserves event types")
    void serializeAndDeserialize_Roundtrip() {
        List<String> events = List.of("parcel.status.changed.v1");
        String serialized = validator.serialize(events);
        assertThat(serialized).isEqualTo("parcel.status.changed.v1");

        List<String> deserialized = validator.deserialize(serialized);
        assertThat(deserialized).containsExactly("parcel.status.changed.v1");
    }
}
