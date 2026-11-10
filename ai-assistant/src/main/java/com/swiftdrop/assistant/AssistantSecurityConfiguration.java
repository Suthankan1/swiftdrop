package com.swiftdrop.assistant;

import com.swiftdrop.security.jwt.ServiceSecurityCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

/**
 * Anonymous visitors may use the assistant for public questions (tracking, quotes, zones). A valid bearer
 * token, when present, is forwarded to the owning services so "my parcels" stays scoped to the caller.
 * Everything else is denied by the shared default-deny chain.
 */
@Configuration(proxyBeanMethods = false)
public class AssistantSecurityConfiguration {

    @Bean
    public ServiceSecurityCustomizer assistantSecurityCustomizer() {
        return auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/assistant/status").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/assistant/chat").permitAll()
                // MCP: same tools, same caller-scoped access; GET answers 405 (no server-initiated stream).
                .requestMatchers(HttpMethod.POST, "/api/v1/assistant/mcp").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/assistant/mcp").permitAll();
    }
}
