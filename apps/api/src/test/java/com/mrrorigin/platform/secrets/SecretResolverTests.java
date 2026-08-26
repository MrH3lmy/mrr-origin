package com.mrrorigin.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Core resolution logic, tested without Spring Boot's environment-post-processing plumbing or a real
 * AWS client -- see {@link SecretsManagerGateway}. Per ADR-0012: every configured mapping must resolve
 * or the whole operation fails closed; nothing here ever falls back to a plaintext/default value.
 */
class SecretResolverTests {

    private final RecordingLog log = new RecordingLog();
    private final SecretResolver resolver = new SecretResolver(log);

    @Test
    void resolvesEveryConfiguredMapping() {
        var mappings = List.of(
                new SecretsProviderProperties.Mapping("STRIPE_CONNECT_LIVE_SECRET_KEY", "prod/stripe/live-secret"),
                new SecretsProviderProperties.Mapping("DATABASE_PASSWORD", "prod/db/password"));
        var gateway = fakeGateway(Map.of(
                "prod/stripe/live-secret", "sk_live_super_secret_value",
                "prod/db/password", "db-super-secret-value"));

        Map<String, Object> resolved = resolver.resolve(mappings, List.of(), gateway);

        assertThat(resolved)
                .containsEntry("STRIPE_CONNECT_LIVE_SECRET_KEY", "sk_live_super_secret_value")
                .containsEntry("DATABASE_PASSWORD", "db-super-secret-value");
    }

    @Test
    void emptyMappingListFailsClosed() {
        assertThatThrownBy(() -> resolver.resolve(List.of(), List.of(), fakeGateway(Map.of())))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("mappings is empty");
    }

    @Test
    void missingSecretIdFailsClosedWithoutCallingTheGateway() {
        var mappings = List.of(new SecretsProviderProperties.Mapping("STRIPE_CONNECT_LIVE_SECRET_KEY", "  "));
        SecretsManagerGateway gateway = secretId -> {
            throw new AssertionError("must not call the gateway for a mapping missing a secret-id");
        };

        assertThatThrownBy(() -> resolver.resolve(mappings, List.of(), gateway))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("secret-id")
                .hasMessageContaining("STRIPE_CONNECT_LIVE_SECRET_KEY");
    }

    @Test
    void blankTargetPropertyFailsClosed() {
        var mappings = List.of(new SecretsProviderProperties.Mapping("  ", "some-secret-id"));

        assertThatThrownBy(() -> resolver.resolve(mappings, List.of(), fakeGateway(Map.of())))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("target-property");
    }

    @Test
    void gatewayFailureFailsClosedAndIsNotSwallowed() {
        var mappings = List.of(
                new SecretsProviderProperties.Mapping("STRIPE_CONNECT_LIVE_SECRET_KEY", "prod/stripe/live-secret"));
        SecretsManagerGateway gateway = secretId -> {
            throw new SecretResolutionException(
                    "AWS Secrets Manager rejected the request for secret \"" + secretId + "\": AccessDenied");
        };

        assertThatThrownBy(() -> resolver.resolve(mappings, List.of(), gateway))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("AccessDenied");
    }

    @Test
    void emptyResolvedValueFailsClosedInsteadOfSilentlyAcceptingIt() {
        var mappings = List.of(
                new SecretsProviderProperties.Mapping("STRIPE_CONNECT_LIVE_SECRET_KEY", "prod/stripe/live-secret"));
        var gateway = fakeGateway(Map.of("prod/stripe/live-secret", ""));

        assertThatThrownBy(() -> resolver.resolve(mappings, List.of(), gateway))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("empty value");
    }

    @Test
    void neverLogsAResolvedSecretValue() {
        var mappings = List.of(
                new SecretsProviderProperties.Mapping("STRIPE_CONNECT_LIVE_SECRET_KEY", "prod/stripe/live-secret"));
        var secretValue = "sk_live_should_never_appear_in_a_log_message";
        var gateway = fakeGateway(Map.of("prod/stripe/live-secret", secretValue));

        resolver.resolve(mappings, List.of(), gateway);

        assertThat(log.messages()).noneMatch(message -> message.contains(secretValue));
    }

    // --- requiredTargetProperties: guarantees a declared-required secret can never silently fall back
    // to a plaintext environment variable just because its mapping was omitted. See ADR-0012 and the
    // AwsSecretsManagerEnvironmentPostProcessorTests equivalents for the full Environment-level scenario.

    @Test
    void requiredTargetPropertyPresentInMappingsResolvesSuccessfully() {
        var mappings = List.of(new SecretsProviderProperties.Mapping("DATABASE_PASSWORD", "prod/db/password"));
        var gateway = fakeGateway(Map.of("prod/db/password", "db-super-secret-value"));

        Map<String, Object> resolved = resolver.resolve(mappings, List.of("DATABASE_PASSWORD"), gateway);

        assertThat(resolved).containsEntry("DATABASE_PASSWORD", "db-super-secret-value");
    }

    @Test
    void requiredTargetPropertyMissingFromMappingsFailsClosedWithoutCallingTheGateway() {
        // STRIPE_CONNECT_LIVE_SECRET_KEY is mapped, but DATABASE_PASSWORD is required and was omitted --
        // this must fail before any gateway call, even for the mapping that IS present.
        var mappings = List.of(
                new SecretsProviderProperties.Mapping("STRIPE_CONNECT_LIVE_SECRET_KEY", "prod/stripe/live-secret"));
        SecretsManagerGateway gateway = secretId -> {
            throw new AssertionError("must not call the gateway when a required target property is unmapped");
        };

        assertThatThrownBy(() -> resolver.resolve(mappings, List.of("DATABASE_PASSWORD"), gateway))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("DATABASE_PASSWORD")
                .hasMessageContaining("required-target-properties");
    }

    @Test
    void optionalTargetPropertyNotListedAsRequiredMayRemainUnmapped() {
        // STRIPE_CONNECT_TEST_SECRET_KEY is neither mapped nor required -- a test-mode-only field on a
        // live-only deployment, say -- and must not cause a failure by its mere absence.
        var mappings = List.of(
                new SecretsProviderProperties.Mapping("STRIPE_CONNECT_LIVE_SECRET_KEY", "prod/stripe/live-secret"));
        var gateway = fakeGateway(Map.of("prod/stripe/live-secret", "sk_live_value"));

        Map<String, Object> resolved =
                resolver.resolve(mappings, List.of("STRIPE_CONNECT_LIVE_SECRET_KEY"), gateway);

        assertThat(resolved).containsOnlyKeys("STRIPE_CONNECT_LIVE_SECRET_KEY");
    }

    private static SecretsManagerGateway fakeGateway(Map<String, String> valuesBySecretId) {
        return secretId -> {
            if (!valuesBySecretId.containsKey(secretId)) {
                throw new SecretResolutionException("no fixture value configured for secret \"" + secretId + "\"");
            }
            return valuesBySecretId.get(secretId);
        };
    }
}
