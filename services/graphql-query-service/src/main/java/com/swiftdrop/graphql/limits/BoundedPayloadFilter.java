package com.swiftdrop.graphql.limits;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class BoundedPayloadFilter extends OncePerRequestFilter {
    public static final int MAX_BYTES = 65536;
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(request.getContextPath() + "/graphql") || !request.getMethod().equals("POST");
    }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean forged = Collections.list(request.getHeaderNames()).stream().map(n -> n.toLowerCase(Locale.ROOT))
                .anyMatch(n -> n.startsWith("x-swiftdrop-actor-") || n.startsWith("x-swiftdrop-tenant")
                        || n.equals("x-swiftdrop-service-token"));
        if (forged) { response.sendError(400); return; }
        if (request.getContentLengthLong() > MAX_BYTES) { response.sendError(413); return; }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) { response.sendError(413); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input = new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
        }, response);
    }
}
