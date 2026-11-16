package com.netpulse.monitoring;

import com.netpulse.model.BandwidthSnapshot;
import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.NetworkStatisticsSnapshot.State;
import com.netpulse.monitoring.WindowsAdapterStatistics.Counters;

import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Polls one selected adapter and publishes immutable snapshots through a volatile field. */
public class NetworkAdapterMonitor implements AutoCloseable {
    private final NetworkAdapter adapter;
    private final WindowsAdapterStatistics source;
    private final LongSupplier clock;
    private final long pollIntervalMillis;
    private final Object sampleLock = new Object();
    private volatile NetworkStatisticsSnapshot latest;
    private volatile boolean stopped;
    private ScheduledExecutorService executor;
    private Counters previous;
    private long previousNanos;
    private long totalReceived;
    private long totalSent;

    public NetworkAdapterMonitor(NetworkAdapter adapter) {
        this(adapter, new WindowsAdapterStatistics(), System::nanoTime, 2_000);
    }

    // Inject sample counters and a monotonic clock for tests; production uses Windows readings.
    NetworkAdapterMonitor(NetworkAdapter adapter, WindowsAdapterStatistics source,
                          LongSupplier clock, long pollIntervalMillis) {
        this.adapter = Objects.requireNonNull(adapter);
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
        if (pollIntervalMillis <= 0) {
            throw new IllegalArgumentException("Polling interval must be positive.");
        }
        this.pollIntervalMillis = pollIntervalMillis;
        latest = snapshot(null, 0, OptionalDouble.empty(), OptionalDouble.empty(),
                State.STARTING, "Waiting for the first counter reading.");
    }

    public static List<NetworkAdapter> listAdapters() throws SocketException {
        List<NetworkAdapter> adapters = new ArrayList<>();
        for (NetworkInterface network : NetworkInterface.networkInterfaces().toList()) {
            if (network.getIndex() > 0) {
                String displayName = network.getDisplayName();
                adapters.add(new NetworkAdapter(network.getIndex(), network.getName(),
                        displayName == null ? network.getName() : displayName,
                        network.isUp(), network.isLoopback()));
            }
        }
        adapters.sort(Comparator.comparingInt(NetworkAdapter::index));
        return List.copyOf(adapters);
    }

    public synchronized void start() {
        if (stopped || executor != null) {
            throw new IllegalStateException("Use a new monitor for each monitoring session.");
        }
        executor = Executors.newSingleThreadScheduledExecutor(
                task -> new Thread(task, "netpulse-adapter-monitor"));
        executor.scheduleWithFixedDelay(this::sampleOnce, 0, pollIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public NetworkStatisticsSnapshot getSnapshot() {
        return latest;
    }

    // One read at a time; also called directly by deterministic tests without starting a thread.
    void sampleOnce() {
        synchronized (sampleLock) {
            if (stopped) {
                return;
            }
            try {
                Counters current = source.read(adapter.index());
                long now = clock.getAsLong();
                validate(current);
                if (!current.status().equalsIgnoreCase("Up")) {
                    previous = null;
                    publish(snapshot(current, 0, OptionalDouble.empty(), OptionalDouble.empty(),
                            State.UNAVAILABLE, "Adapter is not Up; speeds are unavailable."));
                } else if (previous == null) {
                    baseline(current, now);
                    publish(snapshot(current, 0, OptionalDouble.empty(), OptionalDouble.empty(),
                            State.BASELINE, "Baseline recorded; waiting for the next valid reading."));
                } else if (!current.interfaceId().equals(previous.interfaceId())
                        || current.receivedBytes() < previous.receivedBytes()
                        || current.sentBytes() < previous.sentBytes()) {
                    baseline(current, now);
                    publish(snapshot(current, 0, OptionalDouble.empty(), OptionalDouble.empty(),
                            State.COUNTER_RESET, "Counters reset or adapter identity changed; baseline renewed."));
                } else {
                    long elapsed = now - previousNanos;
                    if (elapsed <= 0) {
                        publish(snapshot(current, 0, OptionalDouble.empty(), OptionalDouble.empty(),
                                State.INVALID_INTERVAL, "Nonpositive interval; retaining the previous baseline."));
                    } else {
                        long receivedDelta = current.receivedBytes() - previous.receivedBytes();
                        long sentDelta = current.sentBytes() - previous.sentBytes();
                        long nextReceived = Math.addExact(totalReceived, receivedDelta);
                        long nextSent = Math.addExact(totalSent, sentDelta);
                        totalReceived = nextReceived;
                        totalSent = nextSent;
                        baseline(current, now);
                        publish(snapshot(current, elapsed,
                                OptionalDouble.of(BandwidthSnapshot.bytesPerSecond(receivedDelta, elapsed)),
                                OptionalDouble.of(BandwidthSnapshot.bytesPerSecond(sentDelta, elapsed)),
                                State.VALID, "Windows adapter traffic; totals cover valid observed intervals."));
                    }
                }
            } catch (IOException | RuntimeException exception) {
                previous = null; // Do not invent speeds or count traffic across a failed reading.
                publish(snapshot(null, 0, OptionalDouble.empty(), OptionalDouble.empty(),
                        State.UNAVAILABLE, "Adapter reading unavailable: " + exception.getMessage()));
            }
        }
    }

    private void validate(Counters counters) throws IOException {
        if (counters == null || counters.interfaceId() == null || counters.interfaceId().isBlank()
                || counters.name() == null || counters.name().isBlank()
                || counters.status() == null || counters.status().isBlank()
                || counters.receivedBytes() < 0 || counters.sentBytes() < 0) {
            throw new IOException("Missing adapter metadata or invalid byte counters.");
        }
    }

    private void baseline(Counters counters, long now) {
        previous = counters;
        previousNanos = now;
    }

    private NetworkStatisticsSnapshot snapshot(Counters counters, long interval,
            OptionalDouble receiveRate, OptionalDouble sendRate, State state, String message) {
        String name = latest == null ? adapter.name() : latest.adapterName();
        if (counters != null) {
            name = counters.name();
        }
        return new NetworkStatisticsSnapshot(adapter, name, counters == null ? "Unavailable" : counters.status(),
                counters == null ? OptionalLong.empty() : OptionalLong.of(counters.receivedBytes()),
                counters == null ? OptionalLong.empty() : OptionalLong.of(counters.sentBytes()),
                totalReceived, totalSent, interval, receiveRate, sendRate, state, message);
    }

    private synchronized void publish(NetworkStatisticsSnapshot snapshot) {
        if (!stopped) {
            latest = snapshot;
        }
    }

    public void stop() {
        ScheduledExecutorService worker;
        synchronized (this) {
            if (stopped) {
                return;
            }
            stopped = true;
            worker = executor;
            if (worker != null) {
                worker.shutdownNow();
            }
        }
        source.close(); // Kill an active PowerShell query instead of waiting for its timeout.
        if (worker != null) {
            try {
                if (!worker.awaitTermination(6, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Adapter monitoring worker did not terminate.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        NetworkStatisticsSnapshot last = latest;
        latest = new NetworkStatisticsSnapshot(adapter, last.adapterName(), last.adapterStatus(),
                last.receivedBytes(), last.sentBytes(), last.totalReceivedBytes(), last.totalSentBytes(),
                0, OptionalDouble.empty(), OptionalDouble.empty(), State.STOPPED, "Monitoring stopped.");
    }

    @Override
    public void close() {
        stop();
    }
}
