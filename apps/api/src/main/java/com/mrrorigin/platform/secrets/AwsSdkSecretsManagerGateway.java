package com.mrrorigin.platform.secrets;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.SecretsManagerException;

/**
 * Real AWS-backed gateway. Per ADR-0012, credentials come exclusively from the AWS SDK's standard
 * default credential chain -- {@link DefaultCredentialsProvider} -- never a static access key
 * configured in this codebase. In the beta/production deployment this resolves to
 * {@code WebIdentityTokenFileCredentialsProvider}, which picks up the {@code AWS_ROLE_ARN} and
 * {@code AWS_WEB_IDENTITY_TOKEN_FILE} environment variables Render sets automatically once a service
 * is configured for OIDC federation (see {@code docs/security/aws-secrets-manager-setup.md}). Region
 * is likewise never hardcoded: it comes from the {@code region} constructor argument (deployment
 * configuration) or, if blank, the SDK's own default region provider chain.
 */
final class AwsSdkSecretsManagerGateway implements SecretsManagerGateway {

    private final SecretsManagerClient client;

    AwsSdkSecretsManagerGateway(String region) {
        SecretsManagerClientBuilder builder =
                SecretsManagerClient.builder().credentialsProvider(DefaultCredentialsProvider.create());
        if (region != null && !region.isBlank()) {
            builder = builder.region(Region.of(region));
        }
        this.client = builder.build();
    }

    @Override
    public String getSecretValue(String secretId) {
        try {
            return client.getSecretValue(
                            GetSecretValueRequest.builder().secretId(secretId).build())
                    .secretString();
        } catch (SecretsManagerException e) {
            // Deliberately excludes any response payload beyond the AWS-provided error message; never
            // logs or wraps a secret value, only the (non-sensitive) secret identifier and AWS's own
            // failure reason (e.g. AccessDenied, ResourceNotFound).
            throw new SecretResolutionException(
                    "AWS Secrets Manager rejected the request for secret \"" + secretId + "\": "
                            + e.awsErrorDetails().errorMessage(),
                    e);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
