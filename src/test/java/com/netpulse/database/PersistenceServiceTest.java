package com.netpulse.database;

import com.netpulse.model.AlertSettings;
import com.netpulse.model.NetworkStatisticsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class PersistenceServiceTest {
    @TempDir Path directory;

    @Test
    void downsamplingPreservesAllObservedSummaryBytesAndIgnoresDuplicateCallbacks() throws Exception {
        AtomicLong nanos = new AtomicLong();
        Path path = directory.resolve("netpulse.db");
        try (PersistenceService service = service(path, nanos)) {
            service.beginSession();
            var first = snapshot(100, 50);
            service.recordSnapshot(first);
            service.recordSnapshot(first);
            nanos.set(TimeUnit.SECONDS.toNanos(2));
            service.recordSnapshot(snapshot(200, 100));
            nanos.set(TimeUnit.SECONDS.toNanos(4));
            service.recordSnapshot(snapshot(300, 150));
            nanos.set(TimeUnit.SECONDS.toNanos(6));
            service.recordSnapshot(snapshot(400, 200));
        }
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            assertEquals(2, new NetworkHistoryRepository(database).recent(Instant.EPOCH, 100).size());
            var summary = new TrafficSummaryRepository(database).recent(java.time.LocalDate.of(2000, 1, 1)).getFirst();
            assertEquals(400, summary.totalReceivedBytes());
            assertEquals(200, summary.totalSentBytes());
        }
    }

    @Test
    void shutdownFlushesTheLastPartialIntervalAndNewSessionsResetTheDeltaBaseline() throws Exception {
        AtomicLong nanos = new AtomicLong();
        Path path = directory.resolve("netpulse.db");
        try (PersistenceService service = service(path, nanos)) {
            service.recordSnapshot(snapshot(100, 50));
            nanos.set(TimeUnit.SECONDS.toNanos(2));
            service.recordSnapshot(snapshot(200, 100));
            service.beginSession();
            nanos.set(TimeUnit.SECONDS.toNanos(3));
            service.recordSnapshot(snapshot(20, 10));
        }
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            assertEquals(3, new NetworkHistoryRepository(database).recent(Instant.EPOCH, 100).size());
            var totals = new TrafficSummaryRepository(database).recent(java.time.LocalDate.of(2000, 1, 1)).getFirst();
            assertEquals(220, totals.totalReceivedBytes());
            assertEquals(110, totals.totalSentBytes());
        }
    }

    @Test
    void settingsAndRecentAlertsLoadOnRestartAndClearingDeletesSavedAlerts() throws Exception {
        Path path = directory.resolve("netpulse.db");
        AlertSettings expected = new AlertSettings(true, 0.1, 0.2, 3);
        var alert = DatabaseRepositoryTest.alert(Instant.parse("2026-11-17T00:00:00Z"));
        try (PersistenceService service = service(path, new AtomicLong())) {
            service.saveSettings(expected);
            service.saveAlerts(List.of(alert));
            service.saveAlerts(List.of(alert));
        }
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch cleared = new CountDownLatch(1);
        try (PersistenceService service = service(path, new AtomicLong())) {
            AtomicReference<PersistenceService.StartupData> result = new AtomicReference<>();
            service.initialize(data -> { result.set(data); loaded.countDown(); });
            assertTrue(loaded.await(3, TimeUnit.SECONDS));
            assertEquals(expected, result.get().settings());
            assertEquals(List.of(alert), result.get().alerts());
            service.clearAlerts(success -> { assertTrue(success); cleared.countDown(); });
            assertTrue(cleared.await(3, TimeUnit.SECONDS));
        }
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            assertTrue(new AlertRepository(database).recent(Instant.EPOCH, 100).isEmpty());
            assertEquals(expected, new AlertSettingsRepository(database).load());
        }
    }

    @Test
    void sqliteLockDoesNotBlockTheCollectorAndLaterOperationsRecover() throws Exception {
        Path path = directory.resolve("netpulse.db");
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch error = new CountDownLatch(1);
        try (PersistenceService service = new PersistenceService(path, (available, message) -> {
            if (!available) error.countDown();
        })) {
            service.initialize(data -> ready.countDown());
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            try (var lock = DriverManager.getConnection("jdbc:sqlite:" + path);
                 var statement = lock.createStatement()) {
                statement.execute("BEGIN EXCLUSIVE");
                service.saveSettings(new AlertSettings(true, 1, 2, 3));
                long start = System.nanoTime();
                service.recordSnapshot(snapshot(100, 50));
                assertTrue(System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500));
                assertTrue(error.await(4, TimeUnit.SECONDS));
                statement.execute("ROLLBACK");
            }
            service.saveSettings(new AlertSettings(true, 7, 8, 9));
        }
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            assertEquals(new AlertSettings(true, 7, 8, 9), new AlertSettingsRepository(database).load());
        }
    }

    @Test
    void failedStartupUsesDefaultsAndCanRecoverAfterThePathIsFixed() throws Exception {
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "This is a file, not a directory.");
        Path path = blocked.resolve("netpulse.db");
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<PersistenceService.StartupData> startup = new AtomicReference<>();
        try (PersistenceService service = new PersistenceService(path, (ok, message) -> { })) {
            service.initialize(data -> { startup.set(data); ready.countDown(); });
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertEquals(AlertSettings.defaults(), startup.get().settings());
            Files.delete(blocked);
            service.saveSettings(new AlertSettings(true, 12, 13, 14));
        }
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            assertEquals(new AlertSettings(true, 12, 13, 14), new AlertSettingsRepository(database).load());
        }
    }

    private PersistenceService service(Path path, AtomicLong nanos) {
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.parse("2026-11-17T00:00:00Z").plusNanos(nanos.get()); }
        };
        return new PersistenceService(path, (ok, message) -> { }, clock, nanos::get);
    }

    @Test
    void aFullQueueCannotMixPendingBytesFromDifferentSessions() throws Exception {
        Path path = directory.resolve("queue.db");
        AtomicLong nanos = new AtomicLong();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(257); // Startup + first row + 255 settings writes.
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.parse("2026-11-17T00:00:00Z").plusNanos(nanos.get()); }
        };
        try (PersistenceService service = new PersistenceService(path,
                (ok, message) -> { if (ok) drained.countDown(); }, clock, nanos::get)) {
            try {
                service.initialize(data -> {
                    blocked.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test worker not released.");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                });
                assertTrue(blocked.await(3, TimeUnit.SECONDS));
                service.recordSnapshot(snapshot(100, 50));
                nanos.set(TimeUnit.SECONDS.toNanos(2));
                service.recordSnapshot(snapshot(200, 100));
                for (int index = 0; index < 255; index++) service.saveSettings(AlertSettings.defaults());
                service.beginSession(); // Its flush is rejected while the queue is full.
                nanos.set(TimeUnit.SECONDS.toNanos(3));
                var next = snapshot(20, 10);
                service.recordSnapshot(new NetworkStatisticsSnapshot(next.adapter(), "Other adapter", next.adapterStatus(),
                        next.receivedBytes(), next.sentBytes(), next.totalReceivedBytes(), next.totalSentBytes(),
                        next.intervalNanos(), next.receiveBytesPerSecond(), next.sendBytesPerSecond(), next.state(), next.message()));
            } finally {
                release.countDown();
            }
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            service.flush();
        }
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            var totals = new TrafficSummaryRepository(database).recent(LocalDate.of(2000, 1, 1));
            assertEquals(2, totals.size());
            assertEquals(20, totals.stream().filter(row -> row.adapterName().equals("Other adapter"))
                    .findFirst().orElseThrow().totalReceivedBytes());
            assertEquals(100, totals.stream().filter(row -> !row.adapterName().equals("Other adapter"))
                    .findFirst().orElseThrow().totalReceivedBytes());
        }
    }

    @Test
    void optInRetentionDeletesOnlyOldNetworkRowsAndPreservesTheCutoffAlertsAndSummaries() throws Exception {
        Path path = directory.resolve("retention.db");
        Instant now = Instant.parse("2026-11-17T00:00:00Z");
        Instant cutoff = now.minus(30, java.time.temporal.ChronoUnit.DAYS);
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            var history = new NetworkHistoryRepository(database);
            history.save(snapshot(100, 50), cutoff.minusMillis(1));
            history.save(snapshot(200, 100), cutoff);
            new AlertRepository(database).saveAll(List.of(DatabaseRepositoryTest.alert(cutoff.minusSeconds(1))));
            new TrafficSummaryRepository(database).add("Wi-Fi", LocalDate.of(2000, 1, 1), 10, 20);
        }
        for (int days : new int[]{0, 30}) {
            CountDownLatch ready = new CountDownLatch(1);
            try (PersistenceService service = new PersistenceService(path, (ok, message) -> { },
                    Clock.fixed(now, ZoneOffset.UTC), System::nanoTime, days)) {
                service.initialize(data -> ready.countDown());
                assertTrue(ready.await(3, TimeUnit.SECONDS));
            }
            try (DatabaseManager database = new DatabaseManager(path)) {
                database.initialize();
                var rows = new NetworkHistoryRepository(database).recent(Instant.EPOCH, 500);
                assertEquals(days == 0 ? 2 : 1, rows.size());
                assertEquals(cutoff, rows.getFirst().timestamp());
                assertEquals(1, new AlertRepository(database).recent(Instant.EPOCH, 100).size());
                assertEquals(1, new TrafficSummaryRepository(database).recent(LocalDate.of(2000, 1, 1)).size());
            }
        }
    }

    private NetworkStatisticsSnapshot snapshot(long received, long sent) {
        var base = DatabaseRepositoryTest.snapshot();
        return new NetworkStatisticsSnapshot(base.adapter(), base.adapterName(), base.adapterStatus(),
                base.receivedBytes(), base.sentBytes(), received, sent, base.intervalNanos(),
                base.receiveBytesPerSecond(), base.sendBytesPerSecond(), base.state(), base.message());
    }

    @ParameterizedTest
    @CsvSource({"false,30,2", "true,30,1", "true,invalid,2", "true,-1,2"})
    void retentionPropertiesRequireValidExplicitOptIn(String enabled, String days, int expectedRows) throws Exception {
        Path path = directory.resolve("configured-retention.db");
        Instant now = Instant.now();
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            var history = new NetworkHistoryRepository(database);
            history.save(snapshot(100, 50), now.minus(31, java.time.temporal.ChronoUnit.DAYS));
            history.save(snapshot(200, 100), now.minus(1, java.time.temporal.ChronoUnit.DAYS));
        }
        String previousEnabled = System.getProperty("netpulse.history.retention.enabled");
        String previousDays = System.getProperty("netpulse.history.retention.days");
        try {
            System.setProperty("netpulse.history.retention.enabled", enabled);
            System.setProperty("netpulse.history.retention.days", days);
            CountDownLatch ready = new CountDownLatch(1);
            try (PersistenceService service = new PersistenceService(path, (ok, message) -> { })) {
                service.initialize(data -> ready.countDown());
                assertTrue(ready.await(3, TimeUnit.SECONDS));
            }
        } finally {
            if (previousEnabled == null) System.clearProperty("netpulse.history.retention.enabled");
            else System.setProperty("netpulse.history.retention.enabled", previousEnabled);
            if (previousDays == null) System.clearProperty("netpulse.history.retention.days");
            else System.setProperty("netpulse.history.retention.days", previousDays);
        }
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            assertEquals(expectedRows, new NetworkHistoryRepository(database).recent(Instant.EPOCH, 500).size());
        }
    }
}
