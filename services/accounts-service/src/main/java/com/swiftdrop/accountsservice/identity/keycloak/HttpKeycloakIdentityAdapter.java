package com.swiftdrop.accountsservice.identity.keycloak;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Server-only Keycloak identity administration adapter using Keycloak Admin REST API.
 * Interacts with users, realm role mappings, and role queries with post-mutation verification.
 */
@Component
public class HttpKeycloakIdentityAdapter implements KeycloakIdentityAdapter {

    private static final Logger log = LoggerFactory.getLogger(HttpKeycloakIdentityAdapter.class);

    private final KeycloakAdminProperties properties;
    private final KeycloakAdminCredentialProvider credentialProvider;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public HttpKeycloakIdentityAdapter(KeycloakAdminProperties properties,
                                       KeycloakAdminCredentialProvider credentialProvider) {
        this(properties, credentialProvider, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build(), new ObjectMapper());
    }

    public HttpKeycloakIdentityAdapter(KeycloakAdminProperties properties,
                                       KeycloakAdminCredentialProvider credentialProvider,
                                       HttpClient httpClient,
                                       ObjectMapper objectMapper) {
        this.properties = Objects.requireNonNull(properties, "KeycloakAdminProperties is required");
        this.credentialProvider = Objects.requireNonNull(credentialProvider, "KeycloakAdminCredentialProvider is required");
        this.httpClient = Objects.requireNonNull(httpClient, "HttpClient is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper is required");
    }

    @Override
    public boolean identityExists(String subject) {
        return getUserIdentity(subject).isPresent();
    }

    @Override
    public Optional<KeycloakUserIdentity> getUserIdentity(String subject) {
        if (subject == null || subject.isBlank()) {
            return Optional.empty();
        }

        String encodedSub = URLEncoder.encode(subject.trim(), StandardCharsets.UTF_8);
        String url = properties.getServerUrl() + "/admin/realms/" + properties.getRealm() + "/users/" + encodedSub;

        HttpRequest request = createAdminRequest(url)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new KeycloakAdminUnavailableException(
                        "Keycloak admin authorization denied (HTTP " + response.statusCode() + ")."
                );
            }
            if (response.statusCode() != 200) {
                throw new KeycloakAdminUnavailableException(
                        "Failed to query Keycloak user identity (HTTP " + response.statusCode() + ")."
                );
            }

            JsonNode node = objectMapper.readTree(response.body());
            String id = node.path("id").asText(subject);
            String username = node.path("username").asText("");
            String email = node.path("email").asText("");
            boolean enabled = node.path("enabled").asBoolean(true);

            return Optional.of(new KeycloakUserIdentity(id, username, email, enabled));
        } catch (KeycloakAdminException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakAdminUnavailableException("Keycloak user lookup failed for subject: " + subject, e);
        }
    }

    @Override
    public Set<String> getUserRoles(String subject) {
        if (subject == null || subject.isBlank()) {
            return Collections.emptySet();
        }

        String encodedSub = URLEncoder.encode(subject.trim(), StandardCharsets.UTF_8);
        String url = properties.getServerUrl() + "/admin/realms/" + properties.getRealm() +
                     "/users/" + encodedSub + "/role-mappings/realm";

        HttpRequest request = createAdminRequest(url)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new IdentityNotFoundException(subject);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new KeycloakAdminUnavailableException(
                        "Keycloak admin authorization denied (HTTP " + response.statusCode() + ")."
                );
            }
            if (response.statusCode() != 200) {
                throw new KeycloakAdminUnavailableException(
                        "Failed to query Keycloak user roles (HTTP " + response.statusCode() + ")."
                );
            }

            JsonNode arrayNode = objectMapper.readTree(response.body());
            Set<String> roles = new HashSet<>();
            if (arrayNode.isArray()) {
                for (JsonNode item : arrayNode) {
                    String roleName = item.path("name").asText(null);
                    if (roleName != null && KeycloakRoleAllowlist.isAllowedRole(roleName)) {
                        roles.add(roleName);
                    }
                }
            }
            return Collections.unmodifiableSet(roles);
        } catch (KeycloakAdminException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakAdminUnavailableException("Failed to read user roles for subject: " + subject, e);
        }
    }

    @Override
    public RoleMutationResult grantRole(String subject, String roleName) {
        KeycloakRoleAllowlist.validateReconcilableRole(roleName);
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Subject must not be null or blank");
        }

        // Verify identity exists
        if (!identityExists(subject)) {
            throw new IdentityNotFoundException(subject);
        }

        Set<String> currentRoles = getUserRoles(subject);
        if (currentRoles.contains(roleName)) {
            log.debug("Subject {} already possesses role {}; grant is a no-op", subject, roleName);
            return new RoleMutationResult(false, roleName, currentRoles);
        }

        RoleDescriptor descriptor = getRoleDescriptor(roleName);

        String encodedSub = URLEncoder.encode(subject.trim(), StandardCharsets.UTF_8);
        String url = properties.getServerUrl() + "/admin/realms/" + properties.getRealm() +
                     "/users/" + encodedSub + "/role-mappings/realm";

        String payload = "[{\"id\":\"" + descriptor.id() + "\",\"name\":\"" + descriptor.name() + "\"}]";

        HttpRequest request = createAdminRequest(url)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 204) {
                throw new KeycloakAdminUnavailableException(
                        "Keycloak role grant failed with HTTP " + response.statusCode()
                );
            }
        } catch (KeycloakAdminException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakAdminUnavailableException("Failed to grant role '" + roleName + "' to subject " + subject, e);
        }

        // Post-operation verification
        Set<String> verifiedRoles = getUserRoles(subject);
        if (!verifiedRoles.contains(roleName)) {
            throw new KeycloakAdminUnavailableException(
                    "Post-mutation verification failed: Role '" + roleName + "' was not observed on subject " + subject
            );
        }

        log.info("Successfully granted role {} to subject {}", roleName, subject);
        return new RoleMutationResult(true, roleName, verifiedRoles);
    }

    @Override
    public RoleMutationResult revokeRole(String subject, String roleName) {
        KeycloakRoleAllowlist.validateReconcilableRole(roleName);
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Subject must not be null or blank");
        }

        // Verify identity exists
        if (!identityExists(subject)) {
            throw new IdentityNotFoundException(subject);
        }

        Set<String> currentRoles = getUserRoles(subject);
        if (!currentRoles.contains(roleName)) {
            log.debug("Subject {} does not possess role {}; revoke is a no-op", subject, roleName);
            return new RoleMutationResult(false, roleName, currentRoles);
        }

        RoleDescriptor descriptor = getRoleDescriptor(roleName);

        String encodedSub = URLEncoder.encode(subject.trim(), StandardCharsets.UTF_8);
        String url = properties.getServerUrl() + "/admin/realms/" + properties.getRealm() +
                     "/users/" + encodedSub + "/role-mappings/realm";

        String payload = "[{\"id\":\"" + descriptor.id() + "\",\"name\":\"" + descriptor.name() + "\"}]";

        HttpRequest request = createAdminRequest(url)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 && response.statusCode() != 204) {
                throw new KeycloakAdminUnavailableException(
                        "Keycloak role revocation failed with HTTP " + response.statusCode()
                );
            }
        } catch (KeycloakAdminException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakAdminUnavailableException("Failed to revoke role '" + roleName + "' from subject " + subject, e);
        }

        // Post-operation verification
        Set<String> verifiedRoles = getUserRoles(subject);
        if (verifiedRoles.contains(roleName)) {
            throw new KeycloakAdminUnavailableException(
                    "Post-mutation verification failed: Role '" + roleName + "' is still present on subject " + subject
            );
        }

        log.info("Successfully revoked role {} from subject {}", roleName, subject);
        return new RoleMutationResult(true, roleName, verifiedRoles);
    }

    @Override
    public ReconciliationResult reconcileRole(String subject, String roleName, boolean shouldHaveRole) {
        try {
            KeycloakRoleAllowlist.validateReconcilableRole(roleName);
            RoleMutationResult result;
            if (shouldHaveRole) {
                result = grantRole(subject, roleName);
                if (result.changed()) {
                    return ReconciliationResult.success(subject, roleName, "Role '" + roleName + "' granted successfully.");
                } else {
                    return ReconciliationResult.noOp(subject, roleName, "Role '" + roleName + "' was already assigned.");
                }
            } else {
                result = revokeRole(subject, roleName);
                if (result.changed()) {
                    return ReconciliationResult.success(subject, roleName, "Role '" + roleName + "' revoked successfully.");
                } else {
                    return ReconciliationResult.noOp(subject, roleName, "Role '" + roleName + "' was already absent.");
                }
            }
        } catch (UnapprovedRoleException e) {
            log.warn("Reconciliation denied for subject {} and role {}: {}", subject, roleName, e.getMessage());
            return ReconciliationResult.denied(subject, roleName, e.getMessage());
        } catch (IdentityNotFoundException e) {
            log.warn("Reconciliation failed: Subject {} not found in Keycloak", subject);
            return ReconciliationResult.failed(subject, roleName, e.getMessage());
        } catch (KeycloakAdminUnavailableException e) {
            log.error("Reconciliation pending retry for subject {} and role {}: {}", subject, roleName, e.getMessage());
            return ReconciliationResult.pendingRetry(subject, roleName, e.getMessage());
        } catch (Exception e) {
            log.error("Reconciliation unexpected failure for subject {} and role {}: {}", subject, roleName, e.getMessage());
            return ReconciliationResult.failed(subject, roleName, "Reconciliation failed: " + e.getMessage());
        }
    }

    private RoleDescriptor getRoleDescriptor(String roleName) {
        String url = properties.getServerUrl() + "/admin/realms/" + properties.getRealm() +
                     "/roles/" + URLEncoder.encode(roleName, StandardCharsets.UTF_8);

        HttpRequest request = createAdminRequest(url)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                throw new UnapprovedRoleException("Role '" + roleName + "' does not exist in realm " + properties.getRealm());
            }
            if (response.statusCode() != 200) {
                throw new KeycloakAdminUnavailableException(
                        "Failed to query role descriptor for '" + roleName + "' (HTTP " + response.statusCode() + ")"
                );
            }

            JsonNode node = objectMapper.readTree(response.body());
            String roleId = node.path("id").asText(null);
            String name = node.path("name").asText(roleName);
            if (roleId == null || roleId.isBlank()) {
                throw new KeycloakAdminUnavailableException("Role response did not include an ID for role: " + roleName);
            }
            return new RoleDescriptor(roleId, name);
        } catch (KeycloakAdminException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakAdminUnavailableException("Failed to read role descriptor for '" + roleName + "'", e);
        }
    }

    private HttpRequest.Builder createAdminRequest(String url) {
        String token = credentialProvider.getAdminToken();
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
    }

    private record RoleDescriptor(String id, String name) {}
}
