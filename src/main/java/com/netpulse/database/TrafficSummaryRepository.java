package com.netpulse.database;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class TrafficSummaryRepository {
    public record Summary(String adapterName, LocalDate date, long totalReceivedBytes, long totalSentBytes) { }

    private final DatabaseManager database;

    public TrafficSummaryRepository(DatabaseManager database) {
        this.database = database;
    }

    public void add(String adapterName, LocalDate date, long receivedDelta, long sentDelta) throws SQLException {
        if (receivedDelta < 0 || sentDelta < 0) {
            throw new IllegalArgumentException("Summary deltas cannot be negative.");
        }
        try (var statement = database.connection().prepareStatement("""
                INSERT INTO traffic_summary(adapter_name, date, total_received_bytes, total_sent_bytes)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(adapter_name, date) DO UPDATE SET
                    total_received_bytes=traffic_summary.total_received_bytes + excluded.total_received_bytes,
                    total_sent_bytes=traffic_summary.total_sent_bytes + excluded.total_sent_bytes
                """)) {
            statement.setString(1, adapterName);
            statement.setString(2, date.toString());
            statement.setLong(3, receivedDelta);
            statement.setLong(4, sentDelta);
            statement.executeUpdate();
        }
    }

    public List<Summary> recent(LocalDate from) throws SQLException {
        List<Summary> summaries = new ArrayList<>();
        try (var statement = database.connection().prepareStatement(
                "SELECT * FROM traffic_summary WHERE date >= ? ORDER BY date DESC, id DESC LIMIT 500")) {
            statement.setString(1, from.toString());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    summaries.add(new Summary(rows.getString("adapter_name"), LocalDate.parse(rows.getString("date")),
                            rows.getLong("total_received_bytes"), rows.getLong("total_sent_bytes")));
                }
            }
        }
        return List.copyOf(summaries);
    }
}
