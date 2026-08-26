package com.mrrorigin.platform.secrets;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;

/**
 * Core resolution logic, deliberately separated from {@link AwsSecretsManagerEnvironmentPostProcessor}
 * so it is testable without Spring Boot's environment-post-processing plumbing or a real AWS client.
 * Per ADR-0012: resolves every configured mapping or throws -- never a partial result, never a
 * plaintext/default fallback for a mapping that fails to resolve.
 *
 * <p>Also enforces {@code requiredTargetProperties}: a target property this deployment declares
 * mandatory must have a mapping configured, or startup fails immediately -- before any AWS call and
 * regardless of whether a same-named plaintext environment variable happens to exist. Without this
 * check, simply omitting a target property from {@code mappings} would let it silently resolve from
 * whatever lower-precedence property source has it (a stray environment variable, or an
 * {@code application.yml} default), defeating the "no plaintext fallback" guarantee for exactly the
 * secrets a deployment most needs protected.
 */
final class SecretResolver {

    private final Log log;

    SecretResolver(Log log) {
        this.log = log;
    }

    Map<String, Object> resolve(
            List<SecretsProviderProperties.Mapping> mappings,
            List<String> requiredTargetProperties,
            SecretsManagerGateway gateway) {
        Set<String> mappedTargetProperties = new LinkedHashSet<>();
        for (SecretsProviderProperties.Mapping mapping : mappings) {
            mappedTargetProperties.add(mapping.targetProperty());
        }
        for (String required : requiredTargetProperties) {
            if (!mappedTargetProperties.contains(required)) {
                throw new SecretResolutionException("Required secret target property \"" + required
                        + "\" (mrrorigin.secrets.aws.required-target-properties) has no AWS Secrets Manager "
                        + "mapping configured for this deployment; refusing to start rather than silently "
                        + "resolving it from a plaintext environment variable.");
            }
        }

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
