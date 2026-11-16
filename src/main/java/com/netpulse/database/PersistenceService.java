package com.netpulse.database;

import com.netpulse.model.AlertSettings;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TrafficAlert;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Queues bounded, sequential JDBC work. Snapshot collection never waits for SQLite. */
public class PersistenceService implements AutoCloseable {
    public record StartupData(AlertSettings settings, List<TrafficAlert> alerts) { }
    public record SavedHistory(List<NetworkHistoryRepository.Entry> network, List<TrafficAlert> alerts,
                               List<TrafficSummaryRepository.Summary> summaries) { }
    private record Batch(NetworkStatisticsSnapshot snapshot, Instant time, LocalDate date,
                         long receivedDelta, long sentDelta) { }

    private final DatabaseManager database;
    private final NetworkHistoryRepository network;
    private final AlertRepository alerts;
    private final AlertSettingsRepository settings;
    private final TrafficSummaryRepository summaries;
    private final Clock clock;
    private final LongSupplier nanoClock;
    private final int retentionDays;
    private Instant lastRetentionRun;
    private final AtomicInteger failedOperations = new AtomicInteger();
    private final BiConsumer<Boolean, String> status;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256), task -> new Thread(task, "netpulse-database"));
    private volatile boolean closed;
    private NetworkStatisticsSnapshot previousSnapshot;
    private long previousReceived;
    private long previousSent;
    private long pendingReceived;
    private long pendingSent;
    private Batch pending;
    private boolean hasSaved;
    private long lastSaveNanos;

    public PersistenceService(Path path, BiConsumer<Boolean, String> status) {
        this(path, status, Clock.systemDefaultZone(), System::nanoTime, configuredRetentionDays());
    }

    PersistenceService(Path path, BiConsumer<Boolean, String> status, Clock clock, LongSupplier nanoClock) {
        this(path, status, clock, nanoClock, 0);
    }

    PersistenceService(Path path, BiConsumer<Boolean, String> status, Clock clock, LongSupplier nanoClock,
                       int retentionDays) {
        if (retentionDays < 0 || retentionDays > 36_500) {
            throw new IllegalArgumentException("Retention days must be 0 (disabled) or 1 to 36500.");
        }
        database = new DatabaseManager(path);
        network = new NetworkHistoryRepository(database);
        alerts = new AlertRepository(database);
        settings = new AlertSettingsRepository(database);
        summaries = new TrafficSummaryRepository(database);
        this.status = status;
        this.clock = clock;
        this.nanoClock = nanoClock;
        this.retentionDays = retentionDays;
    }

    private static int configuredRetentionDays() {
        // Deletion is opt-in. Invalid configuration also preserves all existing readings.
        if (!Boolean.getBoolean("netpulse.history.retention.enabled")) {
            return 0;
        }
        try {
            int days = Integer.parseInt(System.getProperty("netpulse.history.retention.days", "30"));
            if (days >= 1 && days <= 36_500) {
                return days;
            }
        } catch (NumberFormatException ignored) {
            // A malformed property must never accidentally enable deletion.
        }
        System.err.println("Network history retention disabled: days must be an integer from 1 to 36500.");
        return 0;
    }

    public void initialize(Consumer<StartupData> loaded) {
        enqueue(() -> loaded.accept(new StartupData(settings.load(), alerts.recent(Instant.EPOCH, 100))),
                () -> loaded.accept(new StartupData(AlertSettings.defaults(), List.of())));
    }

    public synchronized void beginSession() {
        flush();
        if (pending != null) {
            // A full queue must not attribute the previous adapter's unsaved bytes to a new session.
            failedOperations.incrementAndGet();
            status.accept(false, "Storage queue full: the previous session's pending reading was not saved.");
            pending = null;
            pendingReceived = 0;
            pendingSent = 0;
        }
        previousSnapshot = null;
        previousReceived = 0;
        previousSent = 0;
        hasSaved = false;
    }

    public synchronized void recordSnapshot(NetworkStatisticsSnapshot snapshot) {
        if (closed || snapshot == previousSnapshot || !NetworkHistoryRepository.isValid(snapshot)) {
            return;
        }
        previousSnapshot = snapshot;
        Instant now = clock.instant();
        LocalDate date = now.atZone(clock.getZone()).toLocalDate();
        if (pending != null && !pending.date().equals(date)) {
            flush();
            if (pending != null) {
                return; // Keep the previous day's batch intact if the bounded queue is full.
            }
        }
        pendingReceived = Math.addExact(pendingReceived, snapshot.totalReceivedBytes() >= previousReceived
                ? snapshot.totalReceivedBytes() - previousReceived : snapshot.totalReceivedBytes());
        pendingSent = Math.addExact(pendingSent, snapshot.totalSentBytes() >= previousSent
                ? snapshot.totalSentBytes() - previousSent : snapshot.totalSentBytes());
        previousReceived = snapshot.totalReceivedBytes();
        previousSent = snapshot.totalSentBytes();
        pending = new Batch(snapshot, now, date, pendingReceived, pendingSent);
        long elapsed = nanoClock.getAsLong() - lastSaveNanos;
        if (!hasSaved || elapsed >= TimeUnit.SECONDS.toNanos(5)) {
            flush();
        }
    }

    public synchronized void flush() {
        if (pending == null || closed) {
            return;
        }
        Batch batch = pending;
        if (enqueue(() -> database.transaction(() -> {
            if (network.save(batch.snapshot(), batch.time())) {
                summaries.add(batch.snapshot().adapterName(), batch.date(), batch.receivedDelta(), batch.sentDelta());
            }
        }), () -> { })) {
            pending = null;
            pendingReceived = 0;
            pendingSent = 0;
            hasSaved = true;
            lastSaveNanos = nanoClock.getAsLong();
        }
    }

    public void saveAlerts(List<TrafficAlert> generated) {
        List<TrafficAlert> copy = List.copyOf(generated);
        if (!copy.isEmpty()) {
            enqueue(() -> database.transaction(() -> alerts.saveAll(copy)), () -> { });
        }
    }

    public void saveSettings(AlertSettings value) {
        enqueue(() -> settings.save(value), () -> { });
    }

    public void clearAlerts(Consumer<Boolean> completed) {
        enqueue(() -> {
            database.transaction(alerts::clear);
            completed.accept(true);
        }, () -> completed.accept(false));
    }

    public void loadHistory(Instant from, Consumer<SavedHistory> loaded, Runnable failed) {
        enqueue(() -> loaded.accept(new SavedHistory(network.recent(from, 500), alerts.recent(from, 500),
                summaries.recent(from.atZone(clock.getZone()).toLocalDate()))), failed);
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private boolean enqueue(Work work, Runnable failed) {
        if (closed) {
            failed.run();
            return false;
        }
        try {
            worker.execute(() -> {
                try {
                    // Reattempt initialization after a temporary startup/path failure.
                    database.initialize();
                    maintainRetention();
                    work.run();
                    String earlierFailures = failedOperations.get() == 0 ? ""
                            : " | Earlier storage failures: " + failedOperations.get()
                                    + "; failed writes were not replayed.";
                    status.accept(true, "History ready: " + database.getPath()
                            + " | Network retention: " + (retentionDays == 0 ? "off" : retentionDays + " days")
                            + earlierFailures);
                } catch (Exception exception) {
                    failedOperations.incrementAndGet();
                    status.accept(false, "Storage unavailable: " + exception.getMessage()
                            + ". Live monitoring continues; failed writes are not saved.");
                    failed.run();
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException exception) {
            failedOperations.incrementAndGet();
            status.accept(false, "Storage queue is full or stopped. Some data has not been saved.");
            failed.run();
            return false;
        }
    }

    private void maintainRetention() throws java.sql.SQLException {
        if (retentionDays == 0) {
            return;
        }
        Instant now = clock.instant();
        if (lastRetentionRun == null || !now.isBefore(lastRetentionRun.plus(1, ChronoUnit.HOURS))) {
            network.deleteBefore(now.minus(retentionDays, ChronoUnit.DAYS));
            lastRetentionRun = now;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            flush();
            closed = true;
            worker.shutdown();
        }
        boolean wasInterrupted = Thread.interrupted();
        try {
            if (!worker.awaitTermination(8, TimeUnit.SECONDS)) {
                int skipped = worker.shutdownNow().size();
                status.accept(false, "Shutdown discarded " + skipped + " pending storage operations.");
                if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Database worker did not terminate.");
                }
            }
            database.close();
        } catch (InterruptedException exception) {
            worker.shutdownNow();
            wasInterrupted = true;
            try {
                if (!worker.awaitTermination(3, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Interrupted database shutdown did not complete.");
                }
                database.close();
            } catch (InterruptedException | java.sql.SQLException cleanupError) {
                throw new IllegalStateException("Interrupted database cleanup failed.", cleanupError);
            }
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Could not close the database.", exception);
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
