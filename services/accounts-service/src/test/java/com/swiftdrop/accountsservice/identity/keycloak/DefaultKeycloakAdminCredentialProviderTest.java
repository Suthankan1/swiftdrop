package com.swiftdrop.accountsservice.identity.keycloak;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultKeycloakAdminCredentialProviderTest {

    private MockWebServer mockWebServer;
    private KeycloakAdminProperties properties;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        properties = new KeycloakAdminProperties();
        properties.setServerUrl(mockWebServer.url("").toString());
        properties.setRealm("swiftdrop");
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(1000);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("throws KeycloakAdminUnavailableException when credentials are not configured")
    void getAdminToken_failsWhenUnconfigured() {
        properties.setUsername(null);
        properties.setPassword(null);
        properties.setClientSecret(null);

        DefaultKeycloakAdminCredentialProvider provider = new DefaultKeycloakAdminCredentialProvider(properties);
        assertThat(provider.isConfigured()).isFalse();
        assertThatThrownBy(provider::getAdminToken)
                .isInstanceOf(KeycloakAdminUnavailableException.class)
                .hasMessageContaining("credentials are not configured");
    }

    @Test
    @DisplayName("authenticates via password grant on master realm and caches token")
    void getAdminToken_authenticatesViaPasswordGrant_andCaches() {
        properties.setUsername("admin");
        properties.setPassword("secret-pass");

        mockWebServer.setDispatcher(new Dispatcher() {
            private int requestCount = 0;

            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/realms/master/protocol/openid-connect/token")) {
                    requestCount++;
                    assertThat(request.getHeader("Content-Type")).contains("application/x-www-form-urlencoded");
                    String body = request.getBody().readUtf8();
                    assertThat(body).contains("grant_type=password")
                            .contains("username=admin")
                            .contains("password=secret-pass");

                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"access_token\":\"admin-token-num-" + requestCount + "\",\"expires_in\":300}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        DefaultKeycloakAdminCredentialProvider provider = new DefaultKeycloakAdminCredentialProvider(properties);
        assertThat(provider.isConfigured()).isTrue();

        String token1 = provider.getAdminToken();
        assertThat(token1).isEqualTo("admin-token-num-1");

        // Subsequent call within expiry window returns cached token without re-querying
        String token2 = provider.getAdminToken();
        assertThat(token2).isEqualTo("admin-token-num-1");
        assertThat(mockWebServer.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("authenticates via client_credentials grant on target realm when client secret is provided")
    void getAdminToken_authenticatesViaClientCredentials() {
        properties.setClientId("swiftdrop-admin-service");
        properties.setClientSecret("service-secret-xyz");

        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/realms/swiftdrop/protocol/openid-connect/token")) {
                    String body = request.getBody().readUtf8();
                    assertThat(body).contains("grant_type=client_credentials")
                            .contains("client_id=swiftdrop-admin-service")
                            .contains("client_secret=service-secret-xyz");

                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"access_token\":\"service-token-abc\",\"expires_in\":300}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        DefaultKeycloakAdminCredentialProvider provider = new DefaultKeycloakAdminCredentialProvider(properties);
        String token = provider.getAdminToken();
        assertThat(token).isEqualTo("service-token-abc");
    }

    @Test
    @DisplayName("throws KeycloakAdminUnavailableException when token endpoint returns error")
    void getAdminToken_failsOnHttpError() {
        properties.setUsername("admin");
        properties.setPassword("bad-password");

        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(401);
            }
        });

        DefaultKeycloakAdminCredentialProvider provider = new DefaultKeycloakAdminCredentialProvider(properties);
        assertThatThrownBy(provider::getAdminToken)
                .isInstanceOf(KeycloakAdminUnavailableException.class)
                .hasMessageContaining("authentication failed with HTTP 401");
    }
}
