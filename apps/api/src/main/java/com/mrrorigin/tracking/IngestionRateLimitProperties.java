package com.mrrorigin.tracking;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-configurable ceiling for the public ingestion rate limiter (#65). Defaults to 60
 * requests/minute per ingestion key, matching the accepted contract on #65, when left unset.
 */
@ConfigurationProperties(prefix = "mrrorigin.tracking.rate-limit")
public record IngestionRateLimitProperties(Integer requestsPerMinute) {

    private static final int DEFAULT_REQUESTS_PER_MINUTE = 60;

    public IngestionRateLimitProperties {
        requestsPerMinute = requestsPerMinute == null ? DEFAULT_REQUESTS_PER_MINUTE : requestsPerMinute;
        if (requestsPerMinute < 1) {
            throw new IllegalArgumentException(
                    "mrrorigin.tracking.rate-limit.requests-per-minute must be at least 1");
        }
    }
}
