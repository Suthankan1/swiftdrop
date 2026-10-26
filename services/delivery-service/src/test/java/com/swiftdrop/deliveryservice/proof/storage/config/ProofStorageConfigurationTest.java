package com.swiftdrop.deliveryservice.proof.storage.config;

import com.swiftdrop.deliveryservice.proof.storage.ProofObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

class ProofStorageConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProofStorageConfiguration.class);

    @Test
    void storageIsDisabledByDefaultSoUnapprovedApplicationIntegrationCannotActivateIt() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(S3Client.class);
            assertThat(context).doesNotHaveBean(ProofObjectStorage.class);
        });
    }

    @Test
    void enabledStorageRequiresBackendOnlyProviderConfiguration() {
        contextRunner
                .withPropertyValues("swiftdrop.proof-storage.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("Missing swiftdrop.proof-storage.endpoint");
                });
    }

    @Test
    void rejectsProviderEndpointsContainingCredentials() {
        contextRunner
                .withPropertyValues(
                        "swiftdrop.proof-storage.enabled=true",
                        "swiftdrop.proof-storage.endpoint=http://user:password@127.0.0.1:9000",
                        "swiftdrop.proof-storage.bucket=synthetic-private-bucket",
                        "swiftdrop.proof-storage.region=us-east-1",
                        "swiftdrop.proof-storage.access-key=synthetic-access",
                        "swiftdrop.proof-storage.secret-key=synthetic-secret")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "swiftdrop.proof-storage.endpoint must be an absolute provider endpoint");
                });
    }

    @Test
    void createsPrivateS3PortWhenAllProviderPropertiesAreInjected() {
        contextRunner
                .withPropertyValues(
                        "swiftdrop.proof-storage.enabled=true",
                        "swiftdrop.proof-storage.endpoint=http://127.0.0.1:9000",
                        "swiftdrop.proof-storage.bucket=synthetic-private-bucket",
                        "swiftdrop.proof-storage.region=us-east-1",
                        "swiftdrop.proof-storage.access-key=synthetic-access",
                        "swiftdrop.proof-storage.secret-key=synthetic-secret",
                        "swiftdrop.proof-storage.api-call-timeout=5s",
                        "swiftdrop.proof-storage.api-call-attempt-timeout=2s")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(S3Client.class);
                    assertThat(context).hasSingleBean(ProofObjectStorage.class);
                });
    }
}
