package com.swiftdrop.apigateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;

/**
 * Handles errors occurring within the gateway routing pipeline, such as downstream connection failures,
 * upstream timeouts, or bad gateway responses, emitting clean RFC 7807 Problem Details without exposing
 * internal stack traces, hostnames, IPs, or credentials.
 */
@Component
@Order(-1)
public class GatewayErrorWebExceptionHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorWebExceptionHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String title = "Internal Server Error";
        String detail = "An internal gateway error occurred.";

        if (ex instanceof ResponseStatusException rse) {
            HttpStatusCode statusCode = rse.getStatusCode();
            int code = statusCode.value();
            if (code == 404) {
                status = HttpStatus.NOT_FOUND;
                title = "Not Found";
                detail = "The requested resource was not found.";
            } else if (code == 405) {
                status = HttpStatus.METHOD_NOT_ALLOWED;
                title = "Method Not Allowed";
                detail = "The HTTP method is not supported for this route.";
            } else if (code == 503) {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                title = "Service Unavailable";
                detail = "Downstream service is currently unavailable.";
            } else if (code == 504) {
                status = HttpStatus.GATEWAY_TIMEOUT;
                title = "Gateway Timeout";
                detail = "Downstream service request timed out.";
            } else if (code == 502) {
                status = HttpStatus.BAD_GATEWAY;
                title = "Bad Gateway";
                detail = "Downstream service error or invalid response.";
            } else {
                HttpStatus resolved = HttpStatus.resolve(code);
                if (resolved != null) {
                    status = resolved;
                    title = resolved.getReasonPhrase();
                    detail = rse.getReason() != null ? rse.getReason() : "A gateway routing error occurred.";
                }
            }
        } else if (isTimeoutFailure(ex)) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            title = "Gateway Timeout";
            detail = "Downstream service request timed out.";
        } else if (isDownstreamConnectionFailure(ex)) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            title = "Service Unavailable";
            detail = "Downstream service is currently unavailable.";
        } else if (isBadGatewayFailure(ex)) {
            status = HttpStatus.BAD_GATEWAY;
            title = "Bad Gateway";
            detail = "Downstream service error or invalid response.";
        }

        log.warn("Gateway error handling [{}] {} -> status={} {}: {}",
                exchange.getRequest().getMethod(),
                exchange.getRequest().getPath(),
                status.value(),
                title,
                ex.getClass().getSimpleName());

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.valueOf("application/problem+json"));

        String path = exchange.getRequest().getPath().value();
        String json = "{\"type\":\"about:blank\"," +
                "\"title\":\"" + escapeJson(title) + "\"," +
                "\"status\":" + status.value() + "," +
                "\"detail\":\"" + escapeJson(detail) + "\"," +
                "\"instance\":\"" + escapeJson(path) + "\"}";

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private boolean isTimeoutFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String name = current.getClass().getName();
            if (current instanceof java.util.concurrent.TimeoutException ||
                    name.contains("TimeoutException") ||
                    name.contains("ReadTimeoutException") ||
                    name.contains("ConnectTimeoutException")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isDownstreamConnectionFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (current instanceof ConnectException ||
                    name.contains("AnnotatedConnectException") ||
                    current instanceof java.net.UnknownHostException ||
                    current instanceof java.net.NoRouteToHostException ||
                    (message != null && (message.contains("Connection refused") || message.contains("Failed to resolve")))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isBadGatewayFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (name.contains("PrematureCloseException") ||
                    (message != null && (message.contains("Connection reset") || message.contains("Broken pipe")))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
