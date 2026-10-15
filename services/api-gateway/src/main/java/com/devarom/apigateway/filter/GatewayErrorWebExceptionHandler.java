package com.devarom.apigateway.filter;

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
 * emitting clean RFC 7807 Problem Details without exposing internal stack traces or secrets.
 */
@Component
@Order(-1)
public class GatewayErrorWebExceptionHandler implements WebExceptionHandler {

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
            if (statusCode.value() == 404) {
                status = HttpStatus.NOT_FOUND;
                title = "Not Found";
                detail = "The requested resource was not found.";
            } else if (statusCode.value() == 503) {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                title = "Service Unavailable";
                detail = "Downstream service is currently unavailable.";
            } else if (statusCode.value() == 502 || statusCode.value() == 504) {
                status = HttpStatus.BAD_GATEWAY;
                title = "Bad Gateway";
                detail = "Downstream service error or timeout.";
            }
        } else if (isDownstreamConnectionFailure(ex)) {
            status = HttpStatus.SERVICE_UNAVAILABLE;
            title = "Service Unavailable";
            detail = "Downstream service is currently unavailable.";
        }

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

    private boolean isDownstreamConnectionFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof ConnectException ||
                    current.getClass().getName().contains("AnnotatedConnectException") ||
                    (current.getMessage() != null && current.getMessage().contains("Connection refused"))) {
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
