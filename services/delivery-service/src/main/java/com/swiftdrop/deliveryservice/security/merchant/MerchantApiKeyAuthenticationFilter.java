package com.swiftdrop.deliveryservice.security.merchant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter for authenticating merchant requests carrying an X-API-Key header.
 * Direct requests to /api/v1/merchant/** without valid credentials fail closed with RFC 7807 problem details.
 * Forged identity headers (X-Merchant-Id, X-Tenant-Id, X-Roles) are strictly ignored.
 */
public class MerchantApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(MerchantApiKeyAuthenticationFilter.class);

    private final AccountsMerchantKeyValidator keyValidator;

    public MerchantApiKeyAuthenticationFilter(AccountsMerchantKeyValidator keyValidator) {
        this.keyValidator = keyValidator;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/v1/merchant/") && !path.equals("/api/v1/merchant");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String apiKey = request.getHeader("X-API-Key");
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        // Case 1: No credential supplied at all
        if ((apiKey == null || apiKey.isBlank()) && (authHeader == null || !authHeader.regionMatches(true, 0, "Bearer ", 0, 7))) {
            log.warn("Merchant endpoint called without credentials [uri={}]", request.getRequestURI());
            writeProblem(request, response, 401, "UNAUTHORIZED", "Missing authentication credential (X-API-Key or Bearer token).");
            return;
        }

        // Case 2: API key supplied -> authoritative validation via Accounts
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                MerchantKeyValidationResult result = keyValidator.validateApiKey(apiKey.trim());

                if (!result.isValid()) {
                    log.warn("Merchant API key authentication failed: invalid, expired, or revoked key");
                    writeProblem(request, response, 401, "UNAUTHORIZED", "Invalid, expired, or revoked merchant API key.");
                    return;
                }

                if (!result.isApproved()) {
                    log.warn("Merchant API key rejected: business status is '{}' [merchantId={}]",
                            result.status(), result.merchantId());
                    writeProblem(request, response, 403, "MERCHANT_NOT_APPROVED",
                            "Merchant business status is not APPROVED (status: " + result.status() + ").");
                    return;
                }

                if (!result.hasScope("parcels:create")) {
                    log.warn("Merchant API key rejected: missing parcels:create scope [held={}]", result.scopes());
                    writeProblem(request, response, 403, "INSUFFICIENT_SCOPE",
                            "API key does not hold the required 'parcels:create' scope.");
                    return;
                }

                MerchantAuthenticationToken auth = new MerchantAuthenticationToken(
                        result.merchantId(),
                        result.businessName(),
                        "API_KEY",
                        result.scopes()
                );
                SecurityContextHolder.getContext().setAuthentication(auth);

            } catch (AccountsUnavailableException e) {
                log.error("Accounts service unavailable during merchant key validation: {}", e.getMessage());
                writeProblem(request, response, 503, "ACCOUNTS_UNAVAILABLE", "Accounts credential validation service is currently unavailable.");
                return;
            } catch (Exception e) {
                log.error("Unexpected error during merchant API key authentication", e);
                writeProblem(request, response, 503, "AUTHENTICATION_UNAVAILABLE", "Authentication service error.");
                return;
            }
        }

        // Case 3: Bearer token supplied -> pass to OAuth2 Resource Server in the filter chain
        filterChain.doFilter(request, response);
    }

    private static void writeProblem(HttpServletRequest request, HttpServletResponse response,
                                     int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == 401) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey, Bearer");
        }
        String json = "{\"type\":\"https://swiftdrop.com/errors/" + code + "\"," +
                "\"title\":\"" + escapeJson(code) + "\"," +
                "\"status\":" + status + "," +
                "\"detail\":\"" + escapeJson(detail) + "\"," +
                "\"instance\":\"" + escapeJson(request.getRequestURI()) + "\"}";
        response.getWriter().write(json);
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
