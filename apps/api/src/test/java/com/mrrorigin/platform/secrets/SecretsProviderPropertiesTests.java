package com.mrrorigin.platform.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Plain constructor-level tests -- no Spring context needed. */
class SecretsProviderPropertiesTests {

    @Test
    void defaultsToProviderNoneAndEmptyMappingsWhenNothingIsConfigured() {
        var properties = new SecretsProviderProperties(null, null);

        assertThat(properties.provider()).isEqualTo("none");
        assertThat(properties.awsSecretsManagerEnabled()).isFalse();
        assertThat(properties.aws().mappings()).isEmpty();
    }

    @Test
    void blankProviderFallsBackToNone() {
        var properties = new SecretsProviderProperties("  ", null);

        assertThat(properties.provider()).isEqualTo("none");
        assertThat(properties.awsSecretsManagerEnabled()).isFalse();
    }

    @Test
    void awsSecretsManagerEnabledOnlyForTheExactProviderValue() {
        assertThat(new SecretsProviderProperties("aws-secrets-manager", null).awsSecretsManagerEnabled())
                .isTrue();
        assertThat(new SecretsProviderProperties("AWS-SECRETS-MANAGER", null).awsSecretsManagerEnabled())
                .isFalse();
        assertThat(new SecretsProviderProperties("vault", null).awsSecretsManagerEnabled())
                .isFalse();
    }

    @Test
    void nullMappingsListNormalizesToEmptyNotNull() {
        var aws = new SecretsProviderProperties.Aws("us-east-1", null);

        assertThat(aws.mappings()).isNotNull().isEmpty();
    }

    @Test
    void mappingsListIsDefensivelyCopied() {
        var mutable = new java.util.ArrayList<SecretsProviderProperties.Mapping>();
        mutable.add(new SecretsProviderProperties.Mapping("STRIPE_CONNECT_LIVE_SECRET_KEY", "secret-id"));
        var aws = new SecretsProviderProperties.Aws("us-east-1", mutable);

        mutable.clear();

        assertThat(aws.mappings()).hasSize(1);
    }

    @Test
    void mappingsSurviveOrderAndContent() {
        var mappings = List.of(
                new SecretsProviderProperties.Mapping("STRIPE_CONNECT_LIVE_SECRET_KEY", "prod/stripe/live-secret"),
                new SecretsProviderProperties.Mapping("DATABASE_PASSWORD", "prod/db/password"));
        var properties = new SecretsProviderProperties("aws-secrets-manager", new SecretsProviderProperties.Aws(null, mappings));

        assertThat(properties.aws().mappings()).containsExactlyElementsOf(mappings);
    }
}
