package com.swiftdrop.communicationsservice;

import com.swiftdrop.communicationsservice.inbox.ConsumedEvent;
import com.swiftdrop.communicationsservice.notification.NotificationJob;
import com.swiftdrop.communicationsservice.webhook.WebhookAttempt;
import com.swiftdrop.communicationsservice.webhook.WebhookDelivery;
import com.swiftdrop.communicationsservice.webhook.WebhookSubscription;
import jakarta.persistence.Entity;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CommunicationsDataOwnershipTest {

    private static final List<Class<?>> OWNED_ENTITIES = List.of(
            ConsumedEvent.class,
            NotificationJob.class,
            WebhookSubscription.class,
            WebhookDelivery.class,
            WebhookAttempt.class,
            com.swiftdrop.communicationsservice.webhook.WebhookReplayAudit.class
    );

    private static final Set<String> ALLOWED_TABLES = Set.of(
            "consumed_events",
            "notifications",
            "webhook_subscriptions",
            "webhook_deliveries",
            "webhook_attempts",
            "webhook_replays"
    );

    @Test
    @DisplayName("Verify Communications service owns strictly its approved tables")
    void verifyOwnedTables() {
        for (Class<?> entityClass : OWNED_ENTITIES) {
            assertThat(entityClass.isAnnotationPresent(Entity.class))
                    .as(entityClass.getSimpleName() + " should be annotated with @Entity")
                    .isTrue();

            Table table = entityClass.getAnnotation(Table.class);
            assertThat(table).isNotNull();
            assertThat(ALLOWED_TABLES).contains(table.name());
        }
    }

    @Test
    @DisplayName("Verify zero cross-service foreign keys or entity relationships exist in Communications domain")
    void verifyZeroCrossServiceRelationships() {
        for (Class<?> entityClass : OWNED_ENTITIES) {
            for (Field field : entityClass.getDeclaredFields()) {
                if (field.isAnnotationPresent(ManyToOne.class) ||
                    field.isAnnotationPresent(OneToOne.class) ||
                    field.isAnnotationPresent(OneToMany.class)) {
                    // Allowed relationship: intra-communications only (e.g. within communications domain)
                    assertThat(OWNED_ENTITIES)
                            .as("Relationship field %s in %s must only target Communications-owned entities",
                                    field.getName(), entityClass.getSimpleName())
                            .contains(field.getType());
                }

                // Verify external references are stored as scalar IDs, not external JPA entities
                String name = field.getName().toLowerCase();
                if (name.contains("merchant") || name.contains("parcel") || name.contains("recipient")) {
                    assertThat(field.getType())
                            .as("Field %s in %s should be a scalar reference, not an entity", field.getName(), entityClass.getSimpleName())
                            .isIn(java.util.UUID.class, String.class);
                }
            }
        }
    }
}
