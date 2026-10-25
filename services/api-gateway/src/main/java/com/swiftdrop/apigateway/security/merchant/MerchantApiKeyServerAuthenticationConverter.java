package com.swiftdrop.apigateway.security.merchant;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Extracts X-API-Key from incoming HTTP request headers.
 */
public class MerchantApiKeyServerAuthenticationConverter implements ServerAuthenticationConverter {

    public static final String HEADER_API_KEY = "X-API-Key";

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String apiKey = exchange.getRequest().getHeaders().getFirst(HEADER_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return Mono.just(new MerchantApiKeyPreAuthToken(apiKey.trim()));
        }
        return Mono.empty();
    }
}
