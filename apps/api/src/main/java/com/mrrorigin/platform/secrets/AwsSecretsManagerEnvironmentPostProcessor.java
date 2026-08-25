package com.mrrorigin.platform.secrets;

import java.util.Map;
import java.util.function.Function;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * ADR-0012: resolves production secrets from AWS Secrets Manager at application startup and injects
 * them into the {@link ConfigurableEnvironment} at the highest precedence, so every existing
 * {@code ${SOME_ENV_VAR:}}-style placeholder already in {@code application.yml} resolves to the
 * AWS-sourced value with zero change to any configuration consumer (e.g. {@code StripeConnectProperties},
 * {@code EmailProperties}, the datasource configuration).
 *
 * <p>Inert by default -- only activates when {@code mrrorigin.secrets.provider} resolves to
 * {@code aws-secrets-manager} (set via {@code MRRORIGIN_SECRETS_PROVIDER} in the beta/production
 * deployment only; local development and CI never set it). Registered via
 * {@code META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports}; Spring Boot
 * instantiates this through the single-argument {@link DeferredLogFactory} constructor below, which is
 * the documented pattern for logging from an {@link EnvironmentPostProcessor} before the logging
 * system is fully initialized.
 *
 * <p>Fails application startup ({@link SecretResolutionException}) if any configured secret cannot be
 * resolved. There is no plaintext fallback and no partial startup with a missing production secret.
 *
 * <p>{@link EnvironmentPostProcessor} itself is deprecated for removal as of Spring Boot 4.0 with no
 * replacement class present in this project's Spring Boot 4.1.0 dependency; it remains the only
 * available mechanism to inject resolved values into the {@code Environment} before any bean (and
 * therefore every existing {@code ${...}} placeholder consumer) is created. Revisit this class if a
 * successor API is introduced in a future Spring Boot upgrade.
 */
public final class AwsSecretsManagerEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "aws-secrets-manager";

    private final Log log;
    private final Function<String, SecretsManagerGateway> gatewayFactory;

    /** Constructor Spring Boot uses in production. */
    public AwsSecretsManagerEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this(logFactory.getLog(AwsSecretsManagerEnvironmentPostProcessor.class), AwsSdkSecretsManagerGateway::new);
    }

    /** Test seam: a fake gateway factory replaces the real AWS SDK client so tests never call AWS. */
    AwsSecretsManagerEnvironmentPostProcessor(Log log, Function<String, SecretsManagerGateway> gatewayFactory) {
        this.log = log;
        this.gatewayFactory = gatewayFactory;
    }

    @Override
    public int getOrder() {
        // Must run after ConfigDataEnvironmentPostProcessor so application.yml's mrrorigin.secrets.*
        // structure (and any deployment-supplied environment-variable overrides) are already
        // bind-able. Still runs, like every EnvironmentPostProcessor, before any bean is created.
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        SecretsProviderProperties properties = Binder.get(environment)
                .bind("mrrorigin.secrets", Bindable.of(SecretsProviderProperties.class))
                .orElseGet(() -> new SecretsProviderProperties(null, null));

        if (!properties.awsSecretsManagerEnabled()) {
            return;
        }

        log.info("mrrorigin.secrets.provider=aws-secrets-manager: resolving production secrets from AWS Secrets "
                + "Manager before application startup continues (ADR-0012).");

        SecretResolver resolver = new SecretResolver(log);
        try (SecretsManagerGateway gateway = gatewayFactory.apply(properties.aws().region())) {
            Map<String, Object> resolved = resolver.resolve(properties.aws().mappings(), gateway);
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, resolved));
        } catch (SecretResolutionException e) {
            throw e;
        } catch (Exception e) {
            throw new SecretResolutionException(
                    "Failed to resolve one or more production secrets from AWS Secrets Manager at startup.", e);
        }

        log.info("Successfully resolved all configured production secrets from AWS Secrets Manager.");
    }
}
