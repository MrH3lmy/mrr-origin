package com.mrrorigin.tracking;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record EventIngestionRequest(
        @NotNull Integer version,
        @NotBlank @Size(max = 160) String batchId,
        @NotEmpty @Size(max = 100) List<@Valid Event> events) {

    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unknown ingestion envelope property: " + name);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Event(
            @NotBlank @Size(max = 160) String eventId,
            @NotBlank @Size(max = 160) String visitorId,
            @Size(max = 160) String sessionId,
            @NotBlank @Size(max = 80)
                    @Pattern(regexp = "[a-z][a-z0-9_]*") String type,
            @NotNull OffsetDateTime occurredAt,
            @NotNull Map<String, Object> payload) {

        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("Unknown ingestion event property: " + name);
        }
    }
}
