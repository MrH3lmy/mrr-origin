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
    void requiredTargetPropertyOmittedFromMappingsFailsClosedEvenWhenAPlaintextEnvVarExists() {
        var environment = new StandardEnvironment();
        // A stray plaintext DATABASE_PASSWORD exists in the environment (e.g. a leftover process env
        // var) -- it must never be allowed to silently satisfy a declared-required production secret
        // just because that secret's AWS mapping was omitted.
        environment
                .getPropertySources()
                .addLast(new MapPropertySource("stray-env-like-source", Map.of("DATABASE_PASSWORD", "plaintext_should_never_be_used")));
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mrrorigin.secrets.provider", "aws-secrets-manager");
        props.put("mrrorigin.secrets.aws.required-target-properties", "STRIPE_CONNECT_LIVE_SECRET_KEY,DATABASE_PASSWORD");
        props.put("mrrorigin.secrets.aws.mappings[0].target-property", "STRIPE_CONNECT_LIVE_SECRET_KEY");
        props.put("mrrorigin.secrets.aws.mappings[0].secret-id", "prod/stripe/live-secret");
        environment.getPropertySources().addLast(new MapPropertySource("test", props));
        Function<String, SecretsManagerGateway> gatewayFactory =
                region -> throwingGateway("must not call the gateway when a required target property is unmapped");
        var postProcessor = new AwsSecretsManagerEnvironmentPostProcessor(log, gatewayFactory);

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, application))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("DATABASE_PASSWORD")
                .hasMessageContaining("required-target-properties");
        assertThat(environment.getPropertySources().contains(AwsSecretsManagerEnvironmentPostProcessor.PROPERTY_SOURCE_NAME))
                .isFalse();
    }

    @Test
    void requiredTargetPropertyOmittedFromMappingsFailsClosedWithNoPlaintextEnvVarEither() {
        var environment = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mrrorigin.secrets.provider", "aws-secrets-manager");
        props.put("mrrorigin.secrets.aws.required-target-properties", "DATABASE_PASSWORD");
        props.put("mrrorigin.secrets.aws.mappings[0].target-property", "STRIPE_CONNECT_LIVE_SECRET_KEY");
        props.put("mrrorigin.secrets.aws.mappings[0].secret-id", "prod/stripe/live-secret");
        environment.getPropertySources().addLast(new MapPropertySource("test", props));
        Function<String, SecretsManagerGateway> gatewayFactory =
                region -> throwingGateway("must not call the gateway when a required target property is unmapped");
        var postProcessor = new AwsSecretsManagerEnvironmentPostProcessor(log, gatewayFactory);

        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(environment, application))
                .isInstanceOf(SecretResolutionException.class)
                .hasMessageContaining("DATABASE_PASSWORD");
        assertThat(environment.getProperty("DATABASE_PASSWORD")).isNull();
    }

    @Test
    void allRequiredTargetPropertiesMappedStartsSuccessfullyAndOptionalOnesMayStayAbsent() {
        var environment = new StandardEnvironment();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mrrorigin.secrets.provider", "aws-secrets-manager");
        // Only the live Stripe secret is required (e.g. a live-only beta); the test-mode Stripe secret
        // is neither required nor mapped, and must not block startup by its mere absence.
        props.put("mrrorigin.secrets.aws.required-target-properties", "STRIPE_CONNECT_LIVE_SECRET_KEY");
        props.put("mrrorigin.secrets.aws.mappings[0].target-property", "STRIPE_CONNECT_LIVE_SECRET_KEY");
        props.put("mrrorigin.secrets.aws.mappings[0].secret-id", "prod/stripe/live-secret");
        environment.getPropertySources().addLast(new MapPropertySource("test", props));
        var fakeGateway = fakeGateway(Map.of("prod/stripe/live-secret", "sk_live_FROM_AWS_SECRETS_MANAGER"));
        var postProcessor = new AwsSecretsManagerEnvironmentPostProcessor(log, region -> fakeGateway);

        postProcessor.postProcessEnvironment(environment, application);

        assertThat(environment.getProperty("STRIPE_CONNECT_LIVE_SECRET_KEY")).isEqualTo("sk_live_FROM_AWS_SECRETS_MANAGER");
        assertThat(environment.getProperty("STRIPE_CONNECT_TEST_SECRET_KEY")).isNull();
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
