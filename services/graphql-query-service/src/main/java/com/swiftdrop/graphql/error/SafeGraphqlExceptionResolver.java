package com.swiftdrop.graphql.error;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import io.grpc.Status;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public class SafeGraphqlExceptionResolver extends DataFetcherExceptionResolverAdapter {
    @Override
    protected GraphQLError resolveToSingleError(Throwable error, DataFetchingEnvironment env) {
        String code;
        ErrorType type;
        if (error instanceof AccessDeniedException) {
            code = "FORBIDDEN"; type = ErrorType.FORBIDDEN;
        } else if (error instanceof IllegalArgumentException) {
            code = "INVALID_ARGUMENT"; type = ErrorType.BAD_REQUEST;
        } else {
            code = switch (Status.fromThrowable(error).getCode()) {
                case PERMISSION_DENIED -> "FORBIDDEN";
                case UNAUTHENTICATED -> "UNAUTHENTICATED";
                case INVALID_ARGUMENT -> "INVALID_ARGUMENT";
                case NOT_FOUND -> "NOT_FOUND";
                case DEADLINE_EXCEEDED -> "DEADLINE_EXCEEDED";
                case UNAVAILABLE, UNIMPLEMENTED -> "UNAVAILABLE";
                default -> "INTERNAL";
            };
            type = switch (code) {
                case "FORBIDDEN" -> ErrorType.FORBIDDEN;
                case "UNAUTHENTICATED" -> ErrorType.UNAUTHORIZED;
                case "INVALID_ARGUMENT" -> ErrorType.BAD_REQUEST;
                case "NOT_FOUND" -> ErrorType.NOT_FOUND;
                default -> ErrorType.INTERNAL_ERROR;
            };
        }
        return GraphqlErrorBuilder.newError(env).message(code).errorType(type)
                .extensions(java.util.Map.of("code", code)).build();
    }
}
