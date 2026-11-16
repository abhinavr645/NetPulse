package com.netpulse.database;

import com.netpulse.model.NetworkStatisticsSnapshot;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class NetworkHistoryRepository {
    public record Entry(long id, String adapterName, Instant timestamp, double downloadMbps,
                        double uploadMbps, long receivedBytes, long sentBytes) { }

    private final DatabaseManager database;

    public NetworkHistoryRepository(DatabaseManager database) {
        this.database = database;
    }

    public static boolean isValid(NetworkStatisticsSnapshot snapshot) {
        return snapshot.state() == NetworkStatisticsSnapshot.State.VALID
                && snapshot.adapterStatus().equalsIgnoreCase("Up") && snapshot.intervalNanos() > 0
                && snapshot.receiveMbps().isPresent() && snapshot.sendMbps().isPresent()
                && snapshot.receivedBytes().isPresent() && snapshot.sentBytes().isPresent();
    }

    public boolean save(NetworkStatisticsSnapshot snapshot, Instant timestamp) throws SQLException {
        if (!isValid(snapshot)) {
            return false;
        }
        try (var statement = database.connection().prepareStatement("""
                INSERT OR IGNORE INTO network_history
                (adapter_name, timestamp, download_mbps, upload_mbps, received_bytes, sent_bytes)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, snapshot.adapterName());
            statement.setLong(2, timestamp.toEpochMilli());
            statement.setDouble(3, snapshot.receiveMbps().orElseThrow());
            statement.setDouble(4, snapshot.sendMbps().orElseThrow());
            statement.setLong(5, snapshot.receivedBytes().orElseThrow());
            statement.setLong(6, snapshot.sentBytes().orElseThrow());
            return statement.executeUpdate() == 1;
        }
    }

    public List<Entry> recent(Instant from, int limit) throws SQLException {
        List<Entry> entries = new ArrayList<>();
        try (var statement = database.connection().prepareStatement("""
                SELECT * FROM network_history WHERE timestamp >= ? ORDER BY timestamp DESC, id DESC LIMIT ?
                """)) {
            statement.setLong(1, from.toEpochMilli());
            statement.setInt(2, Math.max(1, Math.min(1000, limit)));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    entries.add(new Entry(rows.getLong("id"), rows.getString("adapter_name"),
                            Instant.ofEpochMilli(rows.getLong("timestamp")), rows.getDouble("download_mbps"),
                            rows.getDouble("upload_mbps"), rows.getLong("received_bytes"), rows.getLong("sent_bytes")));
                }
            }
        }
        return List.copyOf(entries);
    }
}
