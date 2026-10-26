package com.swiftdrop.graphql.config;

import com.swiftdrop.security.jwt.ServiceSecurityCustomizer;
import com.swiftdrop.security.jwt.SwiftDropSecurityConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;

@Configuration(proxyBeanMethods = false)
@Import(SwiftDropSecurityConfiguration.class)
public class GraphqlSecurityConfig {
    @Bean
    ServiceSecurityCustomizer graphqlSecurityCustomizer() {
        return auth -> auth.requestMatchers(HttpMethod.POST, "/graphql").authenticated();
    }
}
