package com.netpulse.model;

import java.time.Instant;
import java.util.Objects;

/** An in-app alert only; this phase does not send or persist notifications. */
public record TrafficAlert(Instant timestamp, Type type, Severity severity, String description) {
    public enum Severity { INFO, WARNING, CRITICAL }
    public enum Type { DOWNLOAD_THRESHOLD, UPLOAD_THRESHOLD, INACTIVITY }

    public TrafficAlert {
        Objects.requireNonNull(timestamp);
        Objects.requireNonNull(type);
        Objects.requireNonNull(severity);
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("An alert description is required.");
        }
    }
}
