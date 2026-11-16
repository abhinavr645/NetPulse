package com.netpulse.monitoring;

import com.netpulse.model.BandwidthSnapshot;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

/** Tracks one transfer. Synchronized methods allow another thread to poll safely. */
public class BandwidthMeasurement {
    private static final long SAMPLE_INTERVAL_NANOS = 100_000_000L;

    private final OptionalLong expectedBytes;
    private final LongSupplier clock;
    private boolean started;
    private boolean finished;
    private long startNanos;
    private long totalBytes;
    private long elapsedNanos;
    private long sampleStartNanos;
    private long sampleStartBytes;
    private double currentBytesPerSecond;
    private double peakBytesPerSecond;

    public BandwidthMeasurement() {
        this(OptionalLong.empty(), System::nanoTime);
    }

    public BandwidthMeasurement(long expectedBytes) {
        this(OptionalLong.of(expectedBytes), System::nanoTime);
    }

    // A controllable monotonic clock makes calculation tests deterministic, without sleeps.
    BandwidthMeasurement(long expectedBytes, LongSupplier clock) {
        this(OptionalLong.of(expectedBytes), clock);
    }

    private BandwidthMeasurement(OptionalLong expectedBytes, LongSupplier clock) {
        if (expectedBytes.isPresent() && expectedBytes.getAsLong() < 0) {
            throw new IllegalArgumentException("Expected transfer size cannot be negative.");
        }
        this.expectedBytes = expectedBytes;
        this.clock = Objects.requireNonNull(clock, "Clock is required.");
    }

    public synchronized void start() {
        if (started) {
            throw new IllegalStateException("Use a new measurement for each transfer.");
        }
        startNanos = clock.getAsLong();
        started = true;
    }

    public synchronized void recordBytes(long bytes) {
        if (!started || finished) {
            throw new IllegalStateException("The measurement must be running to record bytes.");
        }
        if (bytes < 0) {
            throw new IllegalArgumentException("Recorded byte count cannot be negative.");
        }
        totalBytes = Math.addExact(totalBytes, bytes);
        update(false);
    }

    public synchronized BandwidthSnapshot getSnapshot() {
        if (started && !finished) {
            update(false);
        }
        return snapshot();
    }

    public synchronized BandwidthSnapshot finish() {
        if (!started) {
            throw new IllegalStateException("Start the measurement before finishing it.");
        }
        if (!finished) {
            update(true);
            finished = true;
        }
        return snapshot();
    }

    private void update(boolean finalSample) {
        // Ignore backward clock readings and never invent a positive duration.
        elapsedNanos = Math.max(elapsedNanos, Math.max(0, clock.getAsLong() - startNanos));
        long intervalNanos = elapsedNanos - sampleStartNanos;
        if (intervalNanos >= SAMPLE_INTERVAL_NANOS || (finalSample && intervalNanos > 0)) {
            currentBytesPerSecond = BandwidthSnapshot.bytesPerSecond(
                    totalBytes - sampleStartBytes, intervalNanos);
            peakBytesPerSecond = Math.max(peakBytesPerSecond, currentBytesPerSecond);
            sampleStartNanos = elapsedNanos;
            sampleStartBytes = totalBytes;
        }
    }

    private BandwidthSnapshot snapshot() {
        return new BandwidthSnapshot(totalBytes, elapsedNanos,
                currentBytesPerSecond, peakBytesPerSecond, expectedBytes);
    }
}
