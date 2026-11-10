package com.swiftdrop.accountsservice.identity.keycloak;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers "was this Keycloak user deleted?" for operational lists such as the dispatcher's courier list, so a
 * user removed in Keycloak stops being offered for assignment even though their local profiles remain.
 *
 * <p>Fails open: when Keycloak cannot be read, or no adapter is configured, the identity counts as present.
 * Answers are cached for a short time so a dashboard load does not call Keycloak once per courier.</p>
 */
@Component
public class IdentityPresenceCache {

    static final Duration TTL = Duration.ofMinutes(5);

    private record Entry(boolean gone, Instant checkedAt) { }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Clock clock;
    private KeycloakIdentityAdapter adapter;

    public IdentityPresenceCache() {
        this(Clock.systemUTC());
    }

    IdentityPresenceCache(Clock clock) {
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setIdentityAdapter(KeycloakIdentityAdapter adapter) {
        this.adapter = adapter;
    }

    /** True only when Keycloak definitively reports that no user with this subject exists. */
    public boolean isGone(String subject) {
        if (adapter == null || subject == null || subject.isBlank()) {
            return false;
        }
        Instant now = clock.instant();
        Entry cached = cache.get(subject);
        if (cached != null && cached.checkedAt().plus(TTL).isAfter(now)) {
            return cached.gone();
        }
        try {
            boolean gone = !adapter.identityExists(subject);
            cache.put(subject, new Entry(gone, now));
            return gone;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
