package com.netpulse.database;

import com.netpulse.model.AlertSettings;
import com.netpulse.model.NetworkStatisticsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
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

    private NetworkStatisticsSnapshot snapshot(long received, long sent) {
        var base = DatabaseRepositoryTest.snapshot();
        return new NetworkStatisticsSnapshot(base.adapter(), base.adapterName(), base.adapterStatus(),
                base.receivedBytes(), base.sentBytes(), received, sent, base.intervalNanos(),
                base.receiveBytesPerSecond(), base.sendBytesPerSecond(), base.state(), base.message());
    }
}
