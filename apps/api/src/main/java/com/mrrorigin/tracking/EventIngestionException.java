package com.mrrorigin.tracking;

import org.springframework.http.HttpStatus;

final class EventIngestionException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    EventIngestionException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
