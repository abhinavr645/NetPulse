package com.netpulse.model;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.function.LongSupplier;

/** A small graph model. The dashboard reads and updates it only on the Swing EDT. */
public class BandwidthGraphHistory {
    public static final long WINDOW_NANOS = 60_000_000_000L;
    public static final int MAX_SAMPLES = 600;

    public record Sample(long elapsedNanos, OptionalDouble downloadMbps, OptionalDouble uploadMbps) { }
    public record Window(long endNanos, boolean started, boolean running, List<Sample> samples) { }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private final LongSupplier clock;
    private NetworkStatisticsSnapshot previousSnapshot;
    private long startNanos;
    private long endNanos;
    private boolean started;
    private boolean running;

    public BandwidthGraphHistory() {
        this(System::nanoTime);
    }

    // Tests supply a clock so the full sixty-second window can be checked without waiting.
    BandwidthGraphHistory(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    public void start() {
        clear();
        startNanos = clock.getAsLong();
        started = true;
        running = true;
    }

    public void clear() {
        samples.clear();
        previousSnapshot = null;
        endNanos = 0;
        started = false;
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean addSnapshot(NetworkStatisticsSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (!running || snapshot == previousSnapshot
                || snapshot.state() == NetworkStatisticsSnapshot.State.STARTING
                || snapshot.state() == NetworkStatisticsSnapshot.State.STOPPED) {
            return false;
        }
        previousSnapshot = snapshot; // Identity matters: equal values can be new real readings.
        advanceWindow();
        boolean valid = snapshot.state() == NetworkStatisticsSnapshot.State.VALID;
        samples.addLast(new Sample(endNanos,
                valid ? snapshot.receiveMbps() : OptionalDouble.empty(),
                valid ? snapshot.sendMbps() : OptionalDouble.empty()));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
        return true;
    }

    public Window getWindow() {
        if (running) {
            advanceWindow();
        }
        return new Window(endNanos, started, running, List.copyOf(samples));
    }

    public void stop() {
        if (running) {
            advanceWindow();
            running = false;
        }
    }

    private void advanceWindow() {
        endNanos = Math.max(endNanos, Math.max(0, clock.getAsLong() - startNanos));
        long cutoff = endNanos - WINDOW_NANOS;
        while (!samples.isEmpty() && samples.getFirst().elapsedNanos() < cutoff) {
            samples.removeFirst();
        }
    }
}
