package com.swiftdrop.deliveryservice.sse.service;

import com.swiftdrop.deliveryservice.assignment.DeliveryAssignmentRepository;
import com.swiftdrop.deliveryservice.sse.config.SseProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParcelSseConnectionManagerConstructorTest {

    @Test
    @DisplayName("ParcelSseConnectionManager constructs via ObjectProvider when MeterRegistry is present")
    void testConstructWithMeterRegistryPresent() {
        SseProperties properties = new SseProperties();
        DeliveryAssignmentRepository repository = mock(DeliveryAssignmentRepository.class);
        MeterRegistry registry = new SimpleMeterRegistry();

        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);

        ParcelSseConnectionManager manager = new ParcelSseConnectionManager(properties, repository, provider);
        assertThat(manager).isNotNull();
        assertThat(registry.find("swiftdrop.sse.connections.active").gauge()).isNotNull();
        manager.shutdown();
    }

    @Test
    @DisplayName("ParcelSseConnectionManager constructs via ObjectProvider when MeterRegistry is absent")
    void testConstructWithMeterRegistryAbsent() {
        SseProperties properties = new SseProperties();
        DeliveryAssignmentRepository repository = mock(DeliveryAssignmentRepository.class);

        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        ParcelSseConnectionManager manager = new ParcelSseConnectionManager(properties, repository, provider);
        assertThat(manager).isNotNull();
        manager.shutdown();
    }

    @Test
    @DisplayName("ParcelSseConnectionManager 2-param compatibility constructor works")
    void testCompatibilityConstructor() {
        SseProperties properties = new SseProperties();
        DeliveryAssignmentRepository repository = mock(DeliveryAssignmentRepository.class);

        ParcelSseConnectionManager manager = new ParcelSseConnectionManager(properties, repository);
        assertThat(manager).isNotNull();
        manager.shutdown();
    }
}
