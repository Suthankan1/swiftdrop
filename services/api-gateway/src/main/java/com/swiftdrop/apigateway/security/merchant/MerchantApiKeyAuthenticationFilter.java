package com.swiftdrop.apigateway.security.merchant;

import com.swiftdrop.apigateway.security.ProblemDetailAccessDeniedHandler;
import com.swiftdrop.apigateway.security.ProblemDetailAuthenticationEntryPoint;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.stereotype.Component;

/**
 * AuthenticationWebFilter for merchant machine API key authentication.
 * Intercepts requests to approved merchant machine endpoints carrying X-API-Key.
 */
@Component
public class MerchantApiKeyAuthenticationFilter extends AuthenticationWebFilter {

    public MerchantApiKeyAuthenticationFilter(
            MerchantApiKeyAuthenticationManager authenticationManager,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler) {
        super(authenticationManager);

        setServerAuthenticationConverter(new MerchantApiKeyServerAuthenticationConverter());
        setRequiresAuthenticationMatcher(ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/v1/merchant/parcels"));

        setAuthenticationFailureHandler((webFilterExchange, exception) -> {
            if (exception instanceof MerchantAuthenticationException mae && mae.isAccessDenied()) {
                return accessDeniedHandler.handle(
                        webFilterExchange.getExchange(),
                        new AccessDeniedException(mae.getMessage(), mae)
                );
            }
            return authenticationEntryPoint.commence(
                    webFilterExchange.getExchange(),
                    exception
            );
        });
    }
}
