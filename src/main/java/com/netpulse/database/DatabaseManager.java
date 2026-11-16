package com.netpulse.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** One reusable connection, owned by the persistence worker rather than the Swing EDT. */
public class DatabaseManager implements AutoCloseable {
    private final Path path;
    private Connection connection;
    private boolean closed;

    public DatabaseManager(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public static Path defaultPath() {
        // Tests override this property with a temporary location.
        return Path.of(System.getProperty("netpulse.database.path", "data/netpulse.db"));
    }

    public Path getPath() {
        return path;
    }

    public void initialize() throws IOException, SQLException {
        if (closed) {
            throw new SQLException("The database manager is closed.");
        }
        if (connection != null && !connection.isClosed()) {
            return;
        }
        Files.createDirectories(path.getParent());
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=1000");
            }
            transaction(() -> {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS network_history (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                adapter_name TEXT NOT NULL,
                                timestamp INTEGER NOT NULL,
                                download_mbps REAL NOT NULL CHECK(download_mbps >= 0),
                                upload_mbps REAL NOT NULL CHECK(upload_mbps >= 0),
                                received_bytes INTEGER NOT NULL CHECK(received_bytes >= 0),
                                sent_bytes INTEGER NOT NULL CHECK(sent_bytes >= 0),
                                UNIQUE(adapter_name, timestamp)
                            )
                            """);
                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS traffic_summary (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                adapter_name TEXT NOT NULL,
                                date TEXT NOT NULL,
                                total_received_bytes INTEGER NOT NULL CHECK(total_received_bytes >= 0),
                                total_sent_bytes INTEGER NOT NULL CHECK(total_sent_bytes >= 0),
                                UNIQUE(adapter_name, date)
                            )
                            """);
                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS alerts (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                timestamp INTEGER NOT NULL,
                                alert_type TEXT NOT NULL,
                                severity TEXT NOT NULL,
                                description TEXT NOT NULL,
                                UNIQUE(timestamp, alert_type, severity, description)
                            )
                            """);
                    statement.executeUpdate("""
                            CREATE TABLE IF NOT EXISTS alert_settings (
                                id INTEGER PRIMARY KEY CHECK(id = 1),
                                download_threshold REAL NOT NULL CHECK(download_threshold > 0),
                                upload_threshold REAL NOT NULL CHECK(upload_threshold > 0),
                                inactivity_seconds INTEGER NOT NULL CHECK(inactivity_seconds BETWEEN 1 AND 86400),
                                enabled INTEGER NOT NULL CHECK(enabled IN (0, 1))
                            )
                            """);
                    statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_history_time ON network_history(timestamp)");
                    statement.executeUpdate("CREATE INDEX IF NOT EXISTS alerts_time ON alerts(timestamp)");
                }
            });
        } catch (SQLException exception) {
            connection.close();
            connection = null;
            throw exception;
        }
    }

    Connection connection() throws SQLException {
        if (closed || connection == null || connection.isClosed()) {
            throw new SQLException("The database is not open.");
        }
        return connection;
    }

    @FunctionalInterface
    public interface SqlWork {
        void run() throws SQLException;
    }

    public void transaction(SqlWork work) throws SQLException {
        Connection current = connection();
        current.setAutoCommit(false);
        try {
            work.run();
            current.commit();
        } catch (SQLException | RuntimeException exception) {
            current.rollback();
            throw exception;
        } finally {
            current.setAutoCommit(true);
        }
    }

    public boolean isOpen() throws SQLException {
        return !closed && connection != null && !connection.isClosed();
    }

    @Override
    public void close() throws SQLException {
        closed = true;
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }
}
