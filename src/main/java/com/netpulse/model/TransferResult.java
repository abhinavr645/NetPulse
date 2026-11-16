package com.netpulse.model;

import java.util.Objects;
import java.util.OptionalLong;

public final class TransferResult {
    private final String mode;
    private final BandwidthSnapshot measurement;

    public TransferResult(String mode, long totalBytes, long elapsedNanos) {
        this(mode, completedMeasurement(totalBytes, elapsedNanos));
    }

    public TransferResult(String mode, BandwidthSnapshot measurement) {
        if (!"UPLOAD".equals(mode) && !"DOWNLOAD".equals(mode)) {
            throw new IllegalArgumentException("Transfer mode must be UPLOAD or DOWNLOAD.");
        }
        this.mode = mode;
        this.measurement = Objects.requireNonNull(measurement, "Measurement is required.");
    }

    public String getMode() {
        return mode;
    }

    public long getTotalBytes() {
        return measurement.totalBytes();
    }

    public long getElapsedNanos() {
        return measurement.elapsedNanos();
    }

    public double getElapsedSeconds() {
        return measurement.elapsedSeconds();
    }

    public double getThroughputMbps() {
        return measurement.averageMbps();
    }

    public double getMegabytesPerSecond() {
        return measurement.averageMBps();
    }

    public BandwidthSnapshot getMeasurement() {
        return measurement;
    }

    private static BandwidthSnapshot completedMeasurement(long bytes, long elapsedNanos) {
        // Preserve the original constructor's validation and average-only result API.
        if (bytes <= 0 || elapsedNanos <= 0) {
            throw new IllegalArgumentException("Byte count and elapsed time must be positive.");
        }
        double rate = BandwidthSnapshot.bytesPerSecond(bytes, elapsedNanos);
        return new BandwidthSnapshot(bytes, elapsedNanos, rate, rate, OptionalLong.of(bytes));
    }
}
