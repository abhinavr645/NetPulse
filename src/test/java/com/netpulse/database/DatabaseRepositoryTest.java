package com.netpulse.database;

import com.netpulse.model.AlertSettings;
import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TrafficAlert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseRepositoryTest {
    @TempDir Path directory;
    private final Instant time = Instant.parse("2026-11-17T00:00:00Z");

    @Test
    void createsDatabaseAndAllFourTablesAndReusesItsConnection() throws Exception {
        Path path = directory.resolve("data/netpulse.db");
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            assertTrue(Files.isRegularFile(path));
            var connection = database.connection();
            database.initialize();
            assertSame(connection, database.connection());
            for (String table : List.of("network_history", "traffic_summary", "alerts", "alert_settings")) {
                try (var query = connection.prepareStatement("SELECT name FROM sqlite_master WHERE type='table' AND name=?")) {
                    query.setString(1, table);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next(), table + " must exist");
                    }
                }
            }
        }
    }

    @Test
    void savesReadsAndDeduplicatesNetworkHistoryWithCorrectCounterValues() throws Exception {
        try (DatabaseManager database = open()) {
            NetworkHistoryRepository repository = new NetworkHistoryRepository(database);
            assertTrue(repository.save(snapshot(), time));
            assertFalse(repository.save(snapshot(), time));
            assertTrue(repository.save(snapshot(), time.plusSeconds(6)));
            var rows = repository.recent(Instant.EPOCH, 500);
            assertEquals(2, rows.size());
            assertEquals(time.plusSeconds(6), rows.getFirst().timestamp());
            assertEquals("Wi-Fi ' test", rows.getFirst().adapterName());
            assertEquals(8.0, rows.getFirst().downloadMbps());
            assertEquals(4.0, rows.getFirst().uploadMbps());
            assertEquals(5_000_000, rows.getFirst().receivedBytes());
            assertEquals(3_000_000, rows.getFirst().sentBytes());
            assertEquals(1, repository.recent(time.plusSeconds(1), 500).size());
        }
    }

    @Test
    void unavailableReadingsAreNotInsertedAsZeros() throws Exception {
        try (DatabaseManager database = open()) {
            var unavailable = new NetworkStatisticsSnapshot(snapshot().adapter(), "Wi-Fi", "Unavailable",
                    OptionalLong.empty(), OptionalLong.empty(), 0, 0, 0, OptionalDouble.empty(),
                    OptionalDouble.empty(), NetworkStatisticsSnapshot.State.UNAVAILABLE, "No counters");
            var repository = new NetworkHistoryRepository(database);
            assertFalse(repository.save(unavailable, time));
            assertTrue(repository.recent(Instant.EPOCH, 100).isEmpty());
        }
    }

    @Test
    void savesRetrievesAndDeduplicatesAlertsAndSupportsClearing() throws Exception {
        try (DatabaseManager database = open()) {
            AlertRepository repository = new AlertRepository(database);
            TrafficAlert first = alert(time);
            TrafficAlert second = alert(time.plusSeconds(1));
            database.transaction(() -> repository.saveAll(List.of(first, second)));
            repository.saveAll(List.of(first));
            assertEquals(List.of(second, first), repository.recent(Instant.EPOCH, 100));
            assertEquals(List.of(second), repository.recent(time.plusMillis(1), 100));
            repository.clear();
            assertTrue(repository.recent(Instant.EPOCH, 100).isEmpty());
        }
    }

    @Test
    void loadsDefaultsAndUpdatesTheSingleSettingsRowAcrossRestart() throws Exception {
        Path path = directory.resolve("netpulse.db");
        AlertSettings saved = new AlertSettings(true, 12.5, 3.25, 9);
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            var repository = new AlertSettingsRepository(database);
            assertEquals(AlertSettings.defaults(), repository.load());
            repository.save(new AlertSettings(false, 1, 2, 3));
            repository.save(saved);
            try (var statement = database.connection().createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM alert_settings")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1));
            }
        }
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            assertEquals(saved, new AlertSettingsRepository(database).load());
        }
    }

    @Test
    void dailyTotalsAccumulateDeltasWithoutAddingCumulativeCounters() throws Exception {
        try (DatabaseManager database = open()) {
            var repository = new TrafficSummaryRepository(database);
            LocalDate day = LocalDate.of(2026, 11, 17);
            repository.add("Wi-Fi", day, 100, 200);
            repository.add("Wi-Fi", day, 30, 40);
            assertEquals(List.of(new TrafficSummaryRepository.Summary("Wi-Fi", day, 130, 240)), repository.recent(day));
        }
    }

    @Test
    void failedMultiStepWriteRollsBackBothReadingAndSummary() throws Exception {
        try (DatabaseManager database = open()) {
            var history = new NetworkHistoryRepository(database);
            var summary = new TrafficSummaryRepository(database);
            assertThrows(SQLException.class, () -> database.transaction(() -> {
                history.save(snapshot(), time);
                summary.add("Wi-Fi", LocalDate.of(2026, 11, 17), 100, 200);
                throw new SQLException("Deliberate test failure");
            }));
            assertTrue(history.recent(Instant.EPOCH, 100).isEmpty());
            assertTrue(summary.recent(LocalDate.of(2000, 1, 1)).isEmpty());
        }
    }

    @Test
    void closingReleasesTheFileAndRejectsFurtherConnectionUse() throws Exception {
        Path path = directory.resolve("netpulse.db");
        DatabaseManager database = new DatabaseManager(path);
        database.initialize();
        database.close();
        database.close();
        assertFalse(database.isOpen());
        assertThrows(SQLException.class, database::connection);
        assertThrows(SQLException.class, database::initialize);
        Files.move(path, directory.resolve("closed.db"));
        assertTrue(Files.exists(directory.resolve("closed.db")));
    }

    private DatabaseManager open() throws Exception {
        DatabaseManager database = new DatabaseManager(directory.resolve("netpulse.db"));
        database.initialize();
        return database;
    }

    static TrafficAlert alert(Instant time) {
        return new TrafficAlert(time, TrafficAlert.Type.DOWNLOAD_THRESHOLD, TrafficAlert.Severity.WARNING,
                "Wi-Fi: measured download exceeds the configured threshold.");
    }

    static NetworkStatisticsSnapshot snapshot() {
        return new NetworkStatisticsSnapshot(new NetworkAdapter(6, "wlan0", "Wi-Fi", true, false),
                "Wi-Fi ' test", "Up", OptionalLong.of(5_000_000), OptionalLong.of(3_000_000),
                100, 50, 1_000_000_000L, OptionalDouble.of(1_000_000), OptionalDouble.of(500_000),
                NetworkStatisticsSnapshot.State.VALID, "Controlled test reading");
    }
}
