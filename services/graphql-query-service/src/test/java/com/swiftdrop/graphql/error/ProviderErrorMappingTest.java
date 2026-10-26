package com.swiftdrop.graphql.error;

import graphql.Scalars;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.ResultPath;
import graphql.language.Field;
import graphql.language.SourceLocation;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.grpc.Status;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class ProviderErrorMappingTest {
    @ParameterizedTest
    @CsvSource({"UNAVAILABLE,UNAVAILABLE", "DEADLINE_EXCEEDED,DEADLINE_EXCEEDED", "PERMISSION_DENIED,FORBIDDEN",
            "UNAUTHENTICATED,UNAUTHENTICATED", "NOT_FOUND,NOT_FOUND", "INVALID_ARGUMENT,INVALID_ARGUMENT", "INTERNAL,INTERNAL"})
    void providerDescriptionsNeverBecomeGraphqlErrors(String status, String expected) {
        var env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .mergedField(graphql.execution.MergedField.newMergedField(Field.newField("root").sourceLocation(new SourceLocation(1, 1)).build()).build())
                .executionStepInfo(ExecutionStepInfo.newExecutionStepInfo().type(Scalars.GraphQLString)
                        .path(ResultPath.rootPath().segment("root")).build()).build();
        var error = new SafeGraphqlExceptionResolver().resolveToSingleError(
                Status.fromCode(Status.Code.valueOf(status)).withDescription("private-host SQL contact token").asRuntimeException(), env);
        assertThat(error.getMessage()).isEqualTo(expected);
        assertThat(error.getExtensions()).containsEntry("code", expected);
        assertThat(error.toSpecification().toString()).doesNotContain("private-host", "SQL", "contact", "token");
    }
}
