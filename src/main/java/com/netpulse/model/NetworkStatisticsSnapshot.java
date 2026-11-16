package com.netpulse.model;

import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/** Adapter counters and session totals, separate from NetPulse TCP payload measurements. */
public record NetworkStatisticsSnapshot(
        NetworkAdapter adapter, String adapterName, String adapterStatus,
        OptionalLong receivedBytes, OptionalLong sentBytes,
        long totalReceivedBytes, long totalSentBytes, long intervalNanos,
        OptionalDouble receiveBytesPerSecond, OptionalDouble sendBytesPerSecond,
        State state, String message) {

    public enum State {
        STARTING, BASELINE, VALID, COUNTER_RESET, INVALID_INTERVAL, UNAVAILABLE, STOPPED
    }

    public NetworkStatisticsSnapshot {
        Objects.requireNonNull(adapter);
        Objects.requireNonNull(adapterName);
        Objects.requireNonNull(adapterStatus);
        Objects.requireNonNull(receivedBytes);
        Objects.requireNonNull(sentBytes);
        Objects.requireNonNull(receiveBytesPerSecond);
        Objects.requireNonNull(sendBytesPerSecond);
        Objects.requireNonNull(state);
        Objects.requireNonNull(message);
        if (totalReceivedBytes < 0 || totalSentBytes < 0 || intervalNanos < 0
                || (receivedBytes.isPresent() && receivedBytes.getAsLong() < 0)
                || (sentBytes.isPresent() && sentBytes.getAsLong() < 0)) {
            throw new IllegalArgumentException("Byte counts and intervals cannot be negative.");
        }
        validateRate(receiveBytesPerSecond);
        validateRate(sendBytesPerSecond);
    }

    public OptionalDouble receiveMbps() {
        return scale(receiveBytesPerSecond, 8.0 / 1_000_000);
    }

    public OptionalDouble sendMbps() {
        return scale(sendBytesPerSecond, 8.0 / 1_000_000);
    }

    public OptionalDouble receiveMBps() {
        return scale(receiveBytesPerSecond, 1.0 / 1_000_000);
    }

    public OptionalDouble sendMBps() {
        return scale(sendBytesPerSecond, 1.0 / 1_000_000);
    }

    private static OptionalDouble scale(OptionalDouble rate, double factor) {
        return rate.isPresent() ? OptionalDouble.of(rate.getAsDouble() * factor) : OptionalDouble.empty();
    }

    private static void validateRate(OptionalDouble rate) {
        if (rate.isPresent() && (!Double.isFinite(rate.getAsDouble()) || rate.getAsDouble() < 0)) {
            throw new IllegalArgumentException("Speeds must be finite and nonnegative.");
        }
    }
}
