package com.netpulse.model;

import java.util.OptionalDouble;
import java.util.OptionalLong;

/** An immutable, consistent view of the measurement engine at one instant. */
public record BandwidthSnapshot(long totalBytes, long elapsedNanos,
                                double currentBytesPerSecond, double peakBytesPerSecond,
                                OptionalLong expectedBytes) {
    public BandwidthSnapshot {
        if (totalBytes < 0 || elapsedNanos < 0) {
            throw new IllegalArgumentException("Byte count and elapsed time cannot be negative.");
        }
        if (!Double.isFinite(currentBytesPerSecond) || currentBytesPerSecond < 0
                || !Double.isFinite(peakBytesPerSecond) || peakBytesPerSecond < 0) {
            throw new IllegalArgumentException("Throughput must be finite and nonnegative.");
        }
        if (expectedBytes == null || (expectedBytes.isPresent() && expectedBytes.getAsLong() < 0)) {
            throw new IllegalArgumentException("Expected size must be absent or nonnegative.");
        }
    }

    public double elapsedSeconds() {
        return elapsedNanos / 1_000_000_000.0;
    }

    public double averageBytesPerSecond() {
        return bytesPerSecond(totalBytes, elapsedNanos);
    }

    public double currentMbps() {
        return currentMBps() * 8;
    }

    public double averageMbps() {
        return averageMBps() * 8;
    }

    public double peakMbps() {
        return peakMBps() * 8;
    }

    public double currentMBps() {
        return currentBytesPerSecond / 1_000_000.0;
    }

    public double averageMBps() {
        return averageBytesPerSecond() / 1_000_000.0;
    }

    public double peakMBps() {
        return peakBytesPerSecond / 1_000_000.0;
    }

    public OptionalDouble progressPercent() {
        if (expectedBytes.isEmpty()) {
            return OptionalDouble.empty();
        }
        if (expectedBytes.getAsLong() == 0) {
            return OptionalDouble.of(100.0); // No payload is required for an empty transfer.
        }
        return OptionalDouble.of(Math.min(100.0, totalBytes / (double) expectedBytes.getAsLong() * 100));
    }

    public static double bytesPerSecond(long bytes, long elapsedNanos) {
        if (bytes < 0 || elapsedNanos < 0) {
            throw new IllegalArgumentException("Byte count and elapsed time cannot be negative.");
        }
        return elapsedNanos == 0 ? 0.0 : bytes / (elapsedNanos / 1_000_000_000.0);
    }
}
