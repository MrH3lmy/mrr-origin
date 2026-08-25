package com.mrrorigin.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Per ADR-0012: inert unless {@code mrrorigin.secrets.provider=aws-secrets-manager}; resolves and
 * injects at the highest property-source precedence when enabled; fails application startup, with no
 * partial state left behind, when a configured secret cannot be resolved.
 */
class AwsSecretsManagerEnvironmentPostProcessorTests {

    private final RecordingLog log = new RecordingLog();
    private final SpringApplication application = new SpringApplication();

    @Test
    void doesNothingAndNeverConstructsAGatewayWhenProviderIsNotConfigured() {
        var environment = new StandardEnvironment();
        Function<String, SecretsManagerGateway> gatewayFactory =
                region -> throwingGateway("must not construct a gateway when the provider is not aws-secrets-manager");
        var postProcessor = new AwsSecretsManagerEnvironmentPostProcessor(log, gatewayFactory);

        postProcessor.postProcessEnvironment(environment, application);

        assertThat(environment.getPropertySources().contains(AwsSecretsManagerEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
    }

    @Test
    void resolvesAndInjectsSecretsAtTheHighestPrecedenceWhenEnabled() {
        var environment = new StandardEnvironment();
        // Simulates a stray plaintext environment variable of the same name -- the AWS-resolved value
        // must win, proving there is no accidental plaintext fallback once AWS resolution is enabled.
        environment
                .getPropertySources()
                .addLast(new MapPropertySource("stray-env-like-source", Map.of("STRIPE_CONNECT_LIVE_SECRET_KEY", "sk_live_STALE_PLAINTEXT_VALUE")));
        addAwsSecretsConfig(
                environment,
                Map.of("mrrorigin.secrets.aws.mappings[0].target-property", "STRIPE_CONNECT_LIVE_SECRET_KEY",
                        "mrrorigin.secrets.aws.mappings[0].secret-id", "prod/stripe/live-secret"));
        var fakeGateway = fakeGateway(Map.of("prod/stripe/live-secret", "sk_live_FROM_AWS_SECRETS_MANAGER"));
        var postProcessor = new AwsSecretsManagerEnvironmentPostProcessor(log, region -> fakeGateway);

        postProcessor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty("STRIPE_CONNECT_LIVE_SECRET_KEY")).isEqualTo("sk_live_FROM_AWS_SECRETS_MANAGER");
        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo(AwsSecretsManagerEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
    }

    @Test
    void failsClosedAndLeavesNoPartialStateWhenASecretCannotBeResolved() {
        var environment = new StandardEnvironment();
        addAwsSecretsConfig(
                environment,
                Map.of("mrrorigin.secrets.aws.mappings[0].target-property", "STRIPE_CONNECT_LIVE_SECRET_KEY",
                        "mrrorigin.secrets.aws.mappings[0].secret-id", "prod/stripe/live-secret"));
        SecretsManagerGateway deniedGateway =
                secretId -> {
                    throw new SecretResolutionException(
                            "AWS Secrets Manager rejected the request for secret \"" + secretId + "\": AccessDenied");
                };
        var postProcessor = new AwsSecretsManagerEnvironmentPostProcessor(log, region -> deniedGateway);

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, application))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("AccessDenied");
        assertThat(environment.getPropertySources().contains(AwsSecretsManagerEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
    }

    @Test
    void failsClosedWhenEnabledWithNoMappingsConfigured() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("test", Map.of("mrrorigin.secrets.provider", "aws-secrets-manager")));
        Function<String, SecretsManagerGateway> gatewayFactory =
                region -> throwingGateway("getSecretValue must not be called when there is nothing to resolve");
        var postProcessor = new AwsSecretsManagerEnvironmentPostProcessor(log, gatewayFactory);

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, application))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("mappings is empty");
    }

    @Test
    void runsAfterConfigDataIsAlreadyLoaded() {
        var postProcessor = new AwsSecretsManagerEnvironmentPostProcessor(log, region -> throwingGateway("unused"));

        assertThat(postProcessor.getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }

    private static void addAwsSecretsConfig(StandardEnvironment environment, Map<String, String> mappingEntries) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mrrorigin.secrets.provider", "aws-secrets-manager");
        props.putAll(mappingEntries);
        environment.getPropertySources().addLast(new MapPropertySource("test", props));
    }

    private static SecretsManagerGateway fakeGateway(Map<String, String> valuesBySecretId) {
        return secretId -> {
            if (!valuesBySecretId.containsKey(secretId)) {
                throw new SecretResolutionException("no fixture value configured for secret \"" + secretId + "\"");
            }
            return valuesBySecretId.get(secretId);
        };
    }

    private static SecretsManagerGateway throwingGateway(String assertionMessage) {
        return secretId -> {
            throw new AssertionError(assertionMessage);
        };
    }
}
