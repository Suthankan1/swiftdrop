package com.swiftdrop.deliveryservice.security.merchant;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MerchantApiKeyAuthenticationFilterTest {

    private AccountsMerchantKeyValidator keyValidator;
    private MerchantApiKeyAuthenticationFilter filter;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        keyValidator = mock(AccountsMerchantKeyValidator.class);
        filter = new MerchantApiKeyAuthenticationFilter(keyValidator);
        filterChain = mock(FilterChain.class);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Non-merchant endpoints bypass filter entirely")
    void nonMerchantEndpointsBypassFilter() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/parcels/123");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    @DisplayName("Missing credential returns 401 Unauthorized with RFC 7807 problem details")
    void missingCredentialReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchant/parcels");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("ApiKey, Bearer");
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Valid X-API-Key sets MerchantAuthenticationToken in SecurityContext and continues chain")
    void validApiKeySetsSecurityContextAndContinues() throws Exception {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        UUID merchantId = UUID.randomUUID();

        when(keyValidator.validateApiKey(apiKey)).thenReturn(
                MerchantKeyValidationResult.valid(merchantId, "Merchant Corp", "APPROVED", Set.of("parcels:create", "parcels:read"))
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchant/parcels");
        request.addHeader("X-API-Key", apiKey);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200); // Response untouched by filter
        verify(filterChain).doFilter(request, response);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth).isInstanceOf(MerchantAuthenticationToken.class);
        MerchantAuthenticationToken token = (MerchantAuthenticationToken) auth;
        assertThat(token.getMerchantId()).isEqualTo(merchantId);
        assertThat(token.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_MERCHANT"));
        assertThat(token.getAuthorities()).anyMatch(a -> a.getAuthority().equals("SCOPE_parcels:create"));
    }

    @Test
    @DisplayName("Invalid X-API-Key returns 401 Unauthorized")
    void invalidApiKeyReturns401() throws Exception {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        when(keyValidator.validateApiKey(apiKey)).thenReturn(
                MerchantKeyValidationResult.invalid("INVALID_KEY")
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchant/parcels");
        request.addHeader("X-API-Key", apiKey);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Non-approved merchant status returns 403 Forbidden with MERCHANT_NOT_APPROVED")
    void nonApprovedMerchantReturns403() throws Exception {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        UUID merchantId = UUID.randomUUID();

        when(keyValidator.validateApiKey(apiKey)).thenReturn(
                MerchantKeyValidationResult.valid(merchantId, "Suspended Corp", "SUSPENDED", Set.of("parcels:create"))
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchant/parcels");
        request.addHeader("X-API-Key", apiKey);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("MERCHANT_NOT_APPROVED");
        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Missing parcels:create scope returns 403 Forbidden with INSUFFICIENT_SCOPE")
    void missingScopeReturns403() throws Exception {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        UUID merchantId = UUID.randomUUID();

        when(keyValidator.validateApiKey(apiKey)).thenReturn(
                MerchantKeyValidationResult.valid(merchantId, "Merchant Corp", "APPROVED", Set.of("parcels:read"))
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchant/parcels");
        request.addHeader("X-API-Key", apiKey);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("INSUFFICIENT_SCOPE");
        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Accounts service unavailable returns 503 Service Unavailable")
    void accountsUnavailableReturns503() throws Exception {
        String apiKey = "swd_live_0123456789abcdef0123456789abcdef";
        when(keyValidator.validateApiKey(apiKey)).thenThrow(new AccountsUnavailableException("Timeout"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchant/parcels");
        request.addHeader("X-API-Key", apiKey);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("ACCOUNTS_UNAVAILABLE");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("Bearer token passes through filter for downstream OAuth2 Resource Server evaluation")
    void bearerTokenPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/merchant/parcels");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOi...");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(keyValidator, never()).validateApiKey(anyString());
    }
}
