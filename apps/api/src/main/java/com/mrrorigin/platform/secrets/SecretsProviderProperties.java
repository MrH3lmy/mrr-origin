package com.mrrorigin.platform.secrets;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code mrrorigin.secrets.*}. Per ADR-0012, inert by default: {@code provider} only activates
 * AWS Secrets Manager resolution when it resolves to {@code aws-secrets-manager}
 * ({@code MRRORIGIN_SECRETS_PROVIDER} in the beta/production deployment). Local development and CI
 * never set that variable, so this has no effect there.
 *
 * <p>{@code aws.mappings} is deliberately never populated from a value committed to this repository —
 * see {@code application.yml}'s {@code mrrorigin.secrets} block and
 * {@code docs/security/aws-secrets-manager-setup.md} for why the mapping list is deployment-owned
 * configuration (no AWS account ID, secret ARN, or region belongs in source control).
 */
@ConfigurationProperties(prefix = "mrrorigin.secrets")
public record SecretsProviderProperties(String provider, Aws aws) {

    static final String AWS_SECRETS_MANAGER = "aws-secrets-manager";

    public SecretsProviderProperties {
        provider = (provider == null || provider.isBlank()) ? "none" : provider;
        aws = aws == null ? new Aws(null, null) : aws;
    }

    boolean awsSecretsManagerEnabled() {
        return AWS_SECRETS_MANAGER.equals(provider);
    }

    public record Aws(String region, List<Mapping> mappings) {
        public Aws {
            mappings = mappings == null ? List.of() : List.copyOf(mappings);
        }
    }

    /** One {@code {target-property, secret-id}} pair. {@code secretId} is an AWS Secrets Manager secret name or ARN. */
    public record Mapping(String targetProperty, String secretId) {}
}
