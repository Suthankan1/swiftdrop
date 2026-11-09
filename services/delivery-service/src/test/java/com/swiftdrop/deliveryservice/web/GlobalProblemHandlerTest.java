package com.swiftdrop.deliveryservice.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalProblemHandlerTest {

    private final GlobalProblemHandler handler = new GlobalProblemHandler();

    @Test
    void springErrorResponsesKeepTheirStatusAsProblemDetails() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/assignments/my");
        var response = handler.handleUnexpected(new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignedSince must be an ISO-8601 instant"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().getDetail()).contains("assignedSince");
        assertThat(response.getBody().getInstance()).hasToString("/api/v1/assignments/my");
    }

    @Test
    void unexpectedErrorsAre500WithoutLeakingInternals() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/parcels");
        var response = handler.handleUnexpected(new IllegalStateException("db password=secret at host 10.0.0.5"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().getTitle()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().getDetail()).doesNotContain("secret").doesNotContain("10.0.0.5");
    }

    @Test
    void securityDenialsAreLeftToTheSecurityChain() {
        var request = new MockHttpServletRequest("GET", "/api/v1/parcels/x");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.handleUnexpected(
                new org.springframework.security.access.AccessDeniedException("denied"), request))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
