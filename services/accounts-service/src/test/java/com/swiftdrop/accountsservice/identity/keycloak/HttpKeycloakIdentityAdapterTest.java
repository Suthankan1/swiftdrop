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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpKeycloakIdentityAdapterTest {

    private MockWebServer mockWebServer;
    private KeycloakAdminProperties properties;
    private KeycloakAdminCredentialProvider credentialProvider;
    private HttpKeycloakIdentityAdapter adapter;

    private final String targetSub = "4a18d1a2-b3e4-4c5d-9f0a-112233445566";

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        properties = new KeycloakAdminProperties();
        properties.setServerUrl(mockWebServer.url("").toString());
        properties.setRealm("swiftdrop");
        properties.setUsername("admin");
        properties.setPassword("secret-test-password");
        properties.setConnectTimeoutMs(1000);
        properties.setReadTimeoutMs(1000);

        credentialProvider = new KeycloakAdminCredentialProvider() {
            @Override
            public String getAdminToken() {
                return "mock-admin-token-xyz";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }
        };

        adapter = new HttpKeycloakIdentityAdapter(properties, credentialProvider);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    @Test
    @DisplayName("identityExists returns true when Keycloak user query returns 200")
    void identityExists_returnsTrue_whenFound() {
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/admin/realms/swiftdrop/users/" + targetSub)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"" + targetSub + "\",\"username\":\"john.courier\",\"email\":\"john@swiftdrop.test\",\"enabled\":true}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        assertThat(adapter.identityExists(targetSub)).isTrue();
    }

    @Test
    @DisplayName("identityExists returns false when Keycloak user query returns 404")
    void identityExists_returnsFalse_whenNotFound() {
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(404);
            }
        });

        assertThat(adapter.identityExists("non-existent-sub")).isFalse();
    }

    @Test
    @DisplayName("getUserIdentity parses user metadata correctly")
    void getUserIdentity_parsesMetadata() {
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/admin/realms/swiftdrop/users/" + targetSub)) {
                    assertThat(request.getHeader("Authorization")).isEqualTo("Bearer mock-admin-token-xyz");
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"" + targetSub + "\",\"username\":\"john.courier\",\"email\":\"john@swiftdrop.test\",\"enabled\":true}");
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        Optional<KeycloakUserIdentity> identityOpt = adapter.getUserIdentity(targetSub);
        assertThat(identityOpt).isPresent();
        KeycloakUserIdentity identity = identityOpt.get();
        assertThat(identity.subject()).isEqualTo(targetSub);
        assertThat(identity.username()).isEqualTo("john.courier");
        assertThat(identity.email()).isEqualTo("john@swiftdrop.test");
        assertThat(identity.enabled()).isTrue();
    }

    @Test
    @DisplayName("getUserRoles extracts approved SwiftDrop roles and ignores unapproved or internal roles")
    void getUserRoles_filtersToApprovedRoles() {
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/admin/realms/swiftdrop/users/" + targetSub + "/role-mappings/realm")) {
                    String json = """
                            [
                              {"id":"r1","name":"COURIER"},
                              {"id":"r2","name":"default-roles-swiftdrop"},
                              {"id":"r3","name":"offline_access"},
                              {"id":"r4","name":"CUSTOMER"},
                              {"id":"r5","name":"UNAPPROVED_CUSTOM_ROLE"}
                            ]
                            """;
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody(json);
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        Set<String> roles = adapter.getUserRoles(targetSub);
        assertThat(roles).containsExactlyInAnyOrder("COURIER", "CUSTOMER");
    }

    @Test
    @DisplayName("grantRole returns changed=false when role is already present (idempotent no-op)")
    void grantRole_isIdempotentNoOp_whenAlreadyAssigned() {
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/admin/realms/swiftdrop/users/" + targetSub)) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"" + targetSub + "\",\"enabled\":true}");
                }
                if (request.getPath().equals("/admin/realms/swiftdrop/users/" + targetSub + "/role-mappings/realm")) {
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("[{\"id\":\"role-courier\",\"name\":\"COURIER\"}]");
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        RoleMutationResult result = adapter.grantRole(targetSub, "COURIER");
        assertThat(result.changed()).isFalse();
        assertThat(result.roleName()).isEqualTo("COURIER");
        assertThat(result.activeRoles()).contains("COURIER");
    }

    @Test
    @DisplayName("grantRole grants approved role and verifies post-mutation state")
    void grantRole_grantsAndVerifies() {
        mockWebServer.setDispatcher(new Dispatcher() {
            private int roleMappingGetCount = 0;

            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path.equals("/admin/realms/swiftdrop/users/" + targetSub)) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"" + targetSub + "\",\"enabled\":true}");
                }
                if (path.equals("/admin/realms/swiftdrop/roles/COURIER")) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"role-courier-123\",\"name\":\"COURIER\"}");
                }
                if (path.equals("/admin/realms/swiftdrop/users/" + targetSub + "/role-mappings/realm")) {
                    if ("GET".equals(request.getMethod())) {
                        roleMappingGetCount++;
                        if (roleMappingGetCount == 1) {
                            // Initial state: only CUSTOMER
                            return new MockResponse().setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("[{\"id\":\"role-cust\",\"name\":\"CUSTOMER\"}]");
                        } else {
                            // After grant: CUSTOMER + COURIER
                            return new MockResponse().setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("[{\"id\":\"role-cust\",\"name\":\"CUSTOMER\"},{\"id\":\"role-courier-123\",\"name\":\"COURIER\"}]");
                        }
                    }
                    if ("POST".equals(request.getMethod())) {
                        assertThat(request.getBody().readUtf8()).contains("role-courier-123");
                        return new MockResponse().setResponseCode(204);
                    }
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        RoleMutationResult result = adapter.grantRole(targetSub, "COURIER");
        assertThat(result.changed()).isTrue();
        assertThat(result.roleName()).isEqualTo("COURIER");
        assertThat(result.activeRoles()).contains("COURIER", "CUSTOMER");
    }

    @Test
    @DisplayName("grantRole rejects unapproved role name with UnapprovedRoleException")
    void grantRole_rejectsUnapprovedRole() {
        assertThatThrownBy(() -> adapter.grantRole(targetSub, "HACKER_PRIVILEGE"))
                .isInstanceOf(UnapprovedRoleException.class)
                .hasMessageContaining("not an approved reconcilable role");
    }

    @Test
    @DisplayName("grantRole rejects missing Keycloak identity with IdentityNotFoundException")
    void grantRole_failsClosed_whenIdentityNotFound() {
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(404);
            }
        });

        assertThatThrownBy(() -> adapter.grantRole("missing-sub-123", "COURIER"))
                .isInstanceOf(IdentityNotFoundException.class);
    }

    @Test
    @DisplayName("revokeRole revokes role and verifies removal")
    void revokeRole_revokesAndVerifies() {
        mockWebServer.setDispatcher(new Dispatcher() {
            private int roleMappingGetCount = 0;

            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath();
                if (path.equals("/admin/realms/swiftdrop/users/" + targetSub)) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"" + targetSub + "\",\"enabled\":true}");
                }
                if (path.equals("/admin/realms/swiftdrop/roles/COURIER")) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"role-courier-123\",\"name\":\"COURIER\"}");
                }
                if (path.equals("/admin/realms/swiftdrop/users/" + targetSub + "/role-mappings/realm")) {
                    if ("GET".equals(request.getMethod())) {
                        roleMappingGetCount++;
                        if (roleMappingGetCount == 1) {
                            // Initially has COURIER
                            return new MockResponse().setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("[{\"id\":\"role-courier-123\",\"name\":\"COURIER\"}]");
                        } else {
                            // After revocation: empty
                            return new MockResponse().setResponseCode(200)
                                    .setHeader("Content-Type", "application/json")
                                    .setBody("[]");
                        }
                    }
                    if ("DELETE".equals(request.getMethod())) {
                        return new MockResponse().setResponseCode(204);
                    }
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        RoleMutationResult result = adapter.revokeRole(targetSub, "COURIER");
        assertThat(result.changed()).isTrue();
        assertThat(result.roleName()).isEqualTo("COURIER");
        assertThat(result.activeRoles()).doesNotContain("COURIER");
    }

    @Test
    @DisplayName("revokeRole is an idempotent no-op when role is already absent")
    void revokeRole_isIdempotentNoOp_whenAlreadyAbsent() {
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().equals("/admin/realms/swiftdrop/users/" + targetSub)) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("{\"id\":\"" + targetSub + "\",\"enabled\":true}");
                }
                if (request.getPath().equals("/admin/realms/swiftdrop/users/" + targetSub + "/role-mappings/realm")) {
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Type", "application/json")
                            .setBody("[]");
                }
                return new MockResponse().setResponseCode(404);
            }
        });

        RoleMutationResult result = adapter.revokeRole(targetSub, "COURIER");
        assertThat(result.changed()).isFalse();
        assertThat(result.roleName()).isEqualTo("COURIER");
        assertThat(result.activeRoles()).doesNotContain("COURIER");
    }

    @Test
    @DisplayName("reconcileRole returns SUCCESS, NO_OP, or DENIED structured outcome")
    void reconcileRole_returnsStructuredOutcomes() {
        // Test unapproved role -> DENIED
        ReconciliationResult deniedResult = adapter.reconcileRole(targetSub, "SUPER_ROOT", true);
        assertThat(deniedResult.outcome()).isEqualTo(ReconciliationOutcome.DENIED);

        // Test non-existent user -> FAILED
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(404);
            }
        });
        ReconciliationResult failedResult = adapter.reconcileRole("unknown-sub", "COURIER", true);
        assertThat(failedResult.outcome()).isEqualTo(ReconciliationOutcome.FAILED);
    }

    @Test
    @DisplayName("adapter throws KeycloakAdminUnavailableException when server returns 401/403 or is down")
    void adapter_failsClosed_whenKeycloakUnauthorizedOrDown() throws IOException {
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setResponseCode(401);
            }
        });

        assertThatThrownBy(() -> adapter.getUserRoles(targetSub))
                .isInstanceOf(KeycloakAdminUnavailableException.class)
                .hasMessageContaining("authorization denied");

        mockWebServer.shutdown();
        assertThatThrownBy(() -> adapter.identityExists(targetSub))
                .isInstanceOf(KeycloakAdminUnavailableException.class);
    }
}
