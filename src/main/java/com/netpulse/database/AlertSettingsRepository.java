package com.netpulse.database;

import com.netpulse.model.AlertSettings;

import java.sql.SQLException;

public class AlertSettingsRepository {
    private final DatabaseManager database;

    public AlertSettingsRepository(DatabaseManager database) {
        this.database = database;
    }

    public AlertSettings load() throws SQLException {
        try (var statement = database.connection().prepareStatement("SELECT * FROM alert_settings WHERE id=1");
             var rows = statement.executeQuery()) {
            if (!rows.next()) {
                return AlertSettings.defaults();
            }
            return new AlertSettings(rows.getInt("enabled") != 0, rows.getDouble("download_threshold"),
                    rows.getDouble("upload_threshold"), rows.getLong("inactivity_seconds"));
        }
    }

    public void save(AlertSettings settings) throws SQLException {
        try (var statement = database.connection().prepareStatement("""
                INSERT INTO alert_settings(id, download_threshold, upload_threshold, inactivity_seconds, enabled)
                VALUES (1, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET download_threshold=excluded.download_threshold,
                    upload_threshold=excluded.upload_threshold, inactivity_seconds=excluded.inactivity_seconds,
                    enabled=excluded.enabled
                """)) {
            statement.setDouble(1, settings.downloadThresholdMbps());
            statement.setDouble(2, settings.uploadThresholdMbps());
            statement.setLong(3, settings.inactivitySeconds());
            statement.setInt(4, settings.enabled() ? 1 : 0);
            statement.executeUpdate();
        }
    }
}
