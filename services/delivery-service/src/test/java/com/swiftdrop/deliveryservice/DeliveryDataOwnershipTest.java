package com.swiftdrop.deliveryservice;

import com.swiftdrop.deliveryservice.core.Parcel;
import com.swiftdrop.deliveryservice.idempotency.IdempotencyRecord;
import com.swiftdrop.deliveryservice.status.StatusHistory;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryDataOwnershipTest {

    private static final List<Class<?>> OWNED_ENTITIES = List.of(
            Parcel.class,
            StatusHistory.class,
            IdempotencyRecord.class
    );

    private static final Set<String> ALLOWED_TABLES = Set.of(
            "parcels",
            "status_history",
            "idempotency_records"
    );

    @Test
    @DisplayName("Verify Delivery service owns strictly its approved tables")
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
    @DisplayName("Verify zero cross-service foreign keys or entity relationships exist in Delivery domain")
    void verifyZeroCrossServiceRelationships() {
        for (Class<?> entityClass : OWNED_ENTITIES) {
            for (Field field : entityClass.getDeclaredFields()) {
                if (field.isAnnotationPresent(ManyToOne.class) ||
                    field.isAnnotationPresent(OneToOne.class) ||
                    field.isAnnotationPresent(OneToMany.class)) {
                    // Allowed relationship: intra-delivery only
                    assertThat(OWNED_ENTITIES)
                            .as("Relationship field %s in %s must only target Delivery-owned entities",
                                    field.getName(), entityClass.getSimpleName())
                            .contains(field.getType());
                }

                // Verify external references are stored as scalar IDs, not external JPA entities
                String name = field.getName().toLowerCase();
                if (name.contains("merchant") || name.contains("sender") || name.contains("actor") ||
                    name.contains("quote") || name.contains("proof")) {
                    assertThat(field.getType())
                            .as("Field %s in %s should be a scalar reference (UUID or String), not an external entity",
                                    field.getName(), entityClass.getSimpleName())
                            .isIn(UUID.class, String.class);
                }
            }
        }
    }
}
