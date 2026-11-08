package com.swiftdrop.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ClientCredentialsServiceTokenProviderTest {

    private static final String TOKEN_URI = "http://keycloak/token";

    private MockRestServiceServer server;
    private RestClient restClient;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
    }

    private ClientCredentialsServiceTokenProvider provider() {
        return new ClientCredentialsServiceTokenProvider(TOKEN_URI, "svc", "s3cret", restClient, clock);
    }

    @Test
    void requestsTokenWithClientCredentialsAndCachesIt() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=client_credentials")))
                .andRespond(withSuccess("{\"access_token\":\"t1\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        ClientCredentialsServiceTokenProvider provider = provider();

        assertThat(provider.getServiceToken()).isEqualTo("t1");
        assertThat(provider.getServiceToken()).isEqualTo("t1");
        server.verify();
    }

    @Test
    void renewsShortlyBeforeExpiry() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"t1\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"t2\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
        ClientCredentialsServiceTokenProvider provider = provider();

        assertThat(provider.getServiceToken()).isEqualTo("t1");
        clock.now = clock.now.plusSeconds(280);
        assertThat(provider.getServiceToken()).isEqualTo("t2");
    }

    @Test
    void failsClosedWithEmptyTokenWhenKeycloakIsDown() {
        server.expect(ExpectedCount.once(), requestTo(TOKEN_URI)).andRespond(withServerError());

        assertThat(provider().getServiceToken()).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-07T00:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
