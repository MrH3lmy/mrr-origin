package com.mrrorigin.tracking;

import org.springframework.http.HttpStatus;

/** Shared error type for the authenticated, workspace/project-scoped tracking-management APIs (#8). */
final class TrackingManagementException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    TrackingManagementException(HttpStatus status, String code, String message) {
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
