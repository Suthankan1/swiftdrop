package com.swiftdrop.accountsservice.identity.keycloak;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdentityPresenceCacheTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-10T08:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    @Test
    void reportsGoneOnlyWhenKeycloakSaysTheUserDoesNotExist() {
        KeycloakIdentityAdapter adapter = mock(KeycloakIdentityAdapter.class);
        when(adapter.identityExists("present")).thenReturn(true);
        when(adapter.identityExists("deleted")).thenReturn(false);
        IdentityPresenceCache cache = new IdentityPresenceCache(clock);
        cache.setIdentityAdapter(adapter);

        assertThat(cache.isGone("present")).isFalse();
        assertThat(cache.isGone("deleted")).isTrue();
    }

    @Test
    void failsOpenWhenKeycloakIsUnavailableOrNotConfigured() {
        KeycloakIdentityAdapter adapter = mock(KeycloakIdentityAdapter.class);
        when(adapter.identityExists("sub")).thenThrow(new KeycloakAdminUnavailableException("down"));
        IdentityPresenceCache cache = new IdentityPresenceCache(clock);
        cache.setIdentityAdapter(adapter);
        assertThat(cache.isGone("sub")).isFalse();

        assertThat(new IdentityPresenceCache(clock).isGone("sub")).isFalse();
    }

    @Test
    void cachesAnswersForAShortTime() {
        KeycloakIdentityAdapter adapter = mock(KeycloakIdentityAdapter.class);
        when(adapter.identityExists("sub")).thenReturn(false);
        IdentityPresenceCache cache = new IdentityPresenceCache(clock);
        cache.setIdentityAdapter(adapter);

        cache.isGone("sub");
        cache.isGone("sub");
        verify(adapter, times(1)).identityExists("sub");

        now.set(now.get().plus(IdentityPresenceCache.TTL).plusSeconds(1));
        cache.isGone("sub");
        verify(adapter, times(2)).identityExists("sub");
    }
}
