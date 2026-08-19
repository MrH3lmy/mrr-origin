package com.mrrorigin.tracking;

import org.springframework.http.HttpStatus;

final class EventIngestionException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final Long retryAfterSeconds;

    EventIngestionException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    EventIngestionException(HttpStatus status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    /** Non-null only for {@code 429} responses, where it becomes the {@code Retry-After} header value. */
    Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
