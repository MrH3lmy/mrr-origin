package com.mrrorigin.platform.secrets;

/**
 * Thrown when a required production secret cannot be resolved from AWS Secrets Manager at startup.
 * Per ADR-0012, this must propagate out of {@link AwsSecretsManagerEnvironmentPostProcessor} and fail
 * application startup -- there is no plaintext fallback and no partial startup with a missing or
 * unresolved production secret. Never constructed with a secret's resolved value, only its identifier
 * (target property name / secret ID), which is not itself sensitive.
 */
public final class SecretResolutionException extends RuntimeException {

    SecretResolutionException(String message) {
        super(message);
    }

    SecretResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
