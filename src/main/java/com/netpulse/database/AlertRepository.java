package com.netpulse.database;

import com.netpulse.model.TrafficAlert;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AlertRepository {
    private final DatabaseManager database;

    public AlertRepository(DatabaseManager database) {
        this.database = database;
    }

    public void saveAll(List<TrafficAlert> alerts) throws SQLException {
        try (var statement = database.connection().prepareStatement("""
                INSERT OR IGNORE INTO alerts(timestamp, alert_type, severity, description) VALUES (?, ?, ?, ?)
                """)) {
            for (TrafficAlert alert : alerts) {
                statement.setLong(1, alert.timestamp().toEpochMilli());
                statement.setString(2, alert.type().name());
                statement.setString(3, alert.severity().name());
                statement.setString(4, alert.description());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public List<TrafficAlert> recent(Instant from, int limit) throws SQLException {
        List<TrafficAlert> alerts = new ArrayList<>();
        try (var statement = database.connection().prepareStatement(
                "SELECT * FROM alerts WHERE timestamp >= ? ORDER BY timestamp DESC, id DESC LIMIT ?")) {
            statement.setLong(1, from.toEpochMilli());
            statement.setInt(2, Math.max(1, Math.min(1000, limit)));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    alerts.add(new TrafficAlert(Instant.ofEpochMilli(rows.getLong("timestamp")),
                            TrafficAlert.Type.valueOf(rows.getString("alert_type")),
                            TrafficAlert.Severity.valueOf(rows.getString("severity")), rows.getString("description")));
                }
            }
        }
        return List.copyOf(alerts);
    }

    public void clear() throws SQLException {
        try (var statement = database.connection().prepareStatement("DELETE FROM alerts")) {
            statement.executeUpdate();
        }
    }
}
