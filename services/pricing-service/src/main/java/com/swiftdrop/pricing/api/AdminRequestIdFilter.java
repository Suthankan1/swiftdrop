package com.swiftdrop.pricing.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class AdminRequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = AdminRequestIdFilter.class.getName() + ".requestId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/admin/pricing/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID requestId = UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader("X-Request-ID", requestId.toString());
        chain.doFilter(request, response);
    }
}
