package com.mrrorigin.platform.secrets;

/**
 * Minimal seam over the real secret-storage client so {@link SecretResolver} is testable without
 * talking to AWS. The production implementation is {@link AwsSdkSecretsManagerGateway}.
 */
interface SecretsManagerGateway extends AutoCloseable {

    /**
     * Returns the current plaintext secret value for {@code secretId}, or throws
     * {@link SecretResolutionException} if it cannot be retrieved. Never returns a fallback/default
     * value.
     */
    String getSecretValue(String secretId);

    @Override
    default void close() {
        // No-op by default; the AWS SDK-backed implementation closes its underlying client.
    }
}
