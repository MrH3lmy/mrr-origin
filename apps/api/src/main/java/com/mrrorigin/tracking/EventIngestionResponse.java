package com.mrrorigin.tracking;

import java.util.List;

public record EventIngestionResponse(String batchId, List<EventResult> events) {
    public record EventResult(String eventId, Status status) {}

    public enum Status {
        ACCEPTED,
        DUPLICATE
    }
}
