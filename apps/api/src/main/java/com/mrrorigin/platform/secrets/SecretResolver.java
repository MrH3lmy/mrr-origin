package com.mrrorigin.platform.secrets;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;

/**
 * Core resolution logic, deliberately separated from {@link AwsSecretsManagerEnvironmentPostProcessor}
 * so it is testable without Spring Boot's environment-post-processing plumbing or a real AWS client.
 * Per ADR-0012: resolves every configured mapping or throws -- never a partial result, never a
 * plaintext/default fallback for a mapping that fails to resolve.
 */
final class SecretResolver {

    private final Log log;

    SecretResolver(Log log) {
        this.log = log;
    }

    Map<String, Object> resolve(List<SecretsProviderProperties.Mapping> mappings, SecretsManagerGateway gateway) {
        if (mappings.isEmpty()) {
            throw new SecretResolutionException(
                    "mrrorigin.secrets.provider=aws-secrets-manager but mrrorigin.secrets.aws.mappings is empty; "
                            + "refusing to start with secret resolution enabled and nothing configured to resolve.");
        }

        Map<String, Object> resolved = new LinkedHashMap<>();
        for (SecretsProviderProperties.Mapping mapping : mappings) {
            String targetProperty = requireNonBlank(mapping.targetProperty(), "target-property");
            String secretId = requireNonBlank(
                    mapping.secretId(), "secret-id for target-property \"" + targetProperty + "\"");
            String value = gateway.getSecretValue(secretId);
            if (value == null || value.isBlank()) {
                throw new SecretResolutionException("AWS Secrets Manager returned an empty value for secret \""
                        + secretId + "\" (target property \"" + targetProperty + "\"); refusing to start.");
            }
            resolved.put(targetProperty, value);
            log.info("Resolved production secret for target property \"" + targetProperty
                    + "\" from AWS Secrets Manager (secretId=\"" + secretId + "\"). The value itself is never logged.");
        }
        return resolved;
    }

    private static String requireNonBlank(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new SecretResolutionException("Missing required " + what + " in mrrorigin.secrets.aws.mappings.");
        }
        return value;
    }
}
