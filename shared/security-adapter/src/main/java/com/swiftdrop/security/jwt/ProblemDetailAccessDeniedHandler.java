package com.swiftdrop.security.jwt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Returns RFC 7807 Problem Detail response on unauthorized access attempts (HTTP 403).
 */
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String path = request.getRequestURI();
        String json = "{\"type\":\"about:blank\"," +
                "\"title\":\"Forbidden\"," +
                "\"status\":403," +
                "\"detail\":\"Access is denied.\"," +
                "\"instance\":\"" + escapeJson(path) + "\"}";
        response.getWriter().write(json);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
