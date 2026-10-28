package com.swiftdrop.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * Returns RFC 7807 Problem Detail response on unauthenticated access attempts (HTTP 401).
 */
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.regionMatches(true, 0, "Basic", 0, 5)) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        String path = request.getRequestURI();
        String json = "{\"type\":\"about:blank\"," +
                "\"title\":\"Unauthorized\"," +
                "\"status\":401," +
                "\"detail\":\"Bearer token is missing, expired, or invalid.\"," +
                "\"instance\":\"" + escapeJson(path) + "\"}";
        response.getWriter().write(json);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
