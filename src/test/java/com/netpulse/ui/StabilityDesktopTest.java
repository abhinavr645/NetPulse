package com.netpulse.ui;

import com.netpulse.Main;
import com.netpulse.database.*;
import com.netpulse.model.*;
import com.netpulse.network.BandwidthServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Additional integration checks; fixtures stay in temporary databases and are labelled as such. */
@EnabledIfSystemProperty(named = "netpulse.gui.smoke", matches = "true")
@Timeout(90)
class StabilityDesktopTest {
    @TempDir Path directory;
    private String previousPath;
    private String previousRetention;

    @BeforeEach
    void isolateDatabase() {
        previousPath = System.getProperty("netpulse.database.path");
        previousRetention = System.getProperty("netpulse.history.retention.enabled");
        System.setProperty("netpulse.database.path", directory.resolve("netpulse.db").toString());
        System.setProperty("netpulse.history.retention.enabled", "false");
    }

    @AfterEach
    void restoreProperties() {
        restore("netpulse.database.path", previousPath);
        restore("netpulse.history.retention.enabled", previousRetention);
    }

    private void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    @Test
    void separateServerProcessSupportsGuiTransfersAndCloseDuringActiveWork() throws Exception {
        // Refuse to interfere with an independently owned listener.
        assertPortAvailable();
        DashboardFrame frame = open();
        Process server = null;
        Path log = directory.resolve("server.log");
        try {
            DashboardPanel panel = frame.dashboard;
            for (String invalid : new String[]{"-1", "not a number", "1074", "0.0000001"}) {
                SwingUtilities.invokeAndWait(() -> {
                    panel.transferSize.setText(invalid);
                    panel.startTestButton.doClick(0);
                    assertTrue(panel.startTestButton.isEnabled());
                    assertNull(panel.displayedResult);
                    assertTrue(panel.testMessage.getText().startsWith("Enter"));
                });
            }
            SwingUtilities.invokeAndWait(() -> {
                panel.transferSize.setText("10");
                panel.startTestButton.doClick(0);
            });
            await(() -> panel.startTestButton.isEnabled() && panel.testMessage.getText().contains("Cannot connect"),
                    5_000, "Missing server did not produce an error.");

            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            server = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", classpath, ServerProcess.class.getName())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.readString(log).contains("Server listening") && server.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(30);
            }
            assertTrue(server.isAlive(), Files.readString(log));
            assertTrue(Files.readString(log).contains("127.0.0.1:5001"));
            System.out.println("SEPARATE TCP SERVER PID: " + server.pid());

            for (String mode : new String[]{"Upload", "Download"}) {
                SwingUtilities.invokeAndWait(() -> {
                    panel.transferMode.setSelectedItem(mode);
                    panel.startTestButton.doClick(0);
                });
                await(() -> panel.startTestButton.isEnabled() && panel.displayedResult != null,
                        10_000, mode + " failed with the separate server process.");
                TransferResult result = onEdt(() -> panel.displayedResult);
                assertEquals(10_000_000, result.getTotalBytes());
                assertEquals(mode.toUpperCase(java.util.Locale.ROOT), result.getMode());
                assertTrue(result.getElapsedNanos() > 0);
                double expectedMbps = result.getTotalBytes() * 8.0 / result.getElapsedSeconds() / 1_000_000;
                assertEquals(expectedMbps, result.getThroughputMbps(), expectedMbps * 1e-12);
                System.out.printf(java.util.Locale.ROOT, "SEPARATE PROCESS GUI TCP: %s, %d bytes, %.6f s, %.3f Mbps%n",
                        result.getMode(), result.getTotalBytes(), result.getElapsedSeconds(), result.getThroughputMbps());
            }
            SwingUtilities.invokeAndWait(() -> panel.startMonitoringButton.doClick(0));
            await(() -> panel.displayedSnapshot != null
                    && panel.displayedSnapshot.state() == NetworkStatisticsSnapshot.State.VALID,
                    20_000, "Real adapter monitoring did not produce a reading.");
            SwingUtilities.invokeAndWait(() -> {
                panel.transferMode.setSelectedItem("Download");
                panel.transferSize.setText("1000");
                panel.startTestButton.doClick(0);
                assertFalse(panel.startTestButton.isEnabled());
            });
            long transferDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.readString(log).contains("Starting DOWNLOAD: 1000000000 bytes")
                    && System.nanoTime() < transferDeadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.readString(log).contains("Starting DOWNLOAD: 1000000000 bytes"),
                    "The final real TCP transfer did not start.");
            close(frame); // Close during or immediately after the transfer, with monitoring active.
            assertTrue(server.isAlive(), "Closing a dashboard must leave its separate server running.");
            server.getOutputStream().write('\n'); // Ask the test server process to close its real server gracefully.
            server.getOutputStream().flush();
            assertTrue(server.waitFor(8, TimeUnit.SECONDS), "Separate server did not shut down.");
            assertEquals(0, server.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("SERVER PROCESS STOPPED"));
            assertPortAvailable();
            System.out.println("ACTIVE-WORK SHUTDOWN VERIFIED: GUI workers stopped; separate server closed; port 5001 reusable.");
        } finally {
            if (onEdt(frame::isDisplayable)) close(frame);
            if (server != null && server.isAlive()) {
                server.destroyForcibly();
                assertTrue(server.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void emptyAndLargeHistoryFiltersStayResponsiveAndLockedClearingPreservesAlerts() throws Exception {
        DashboardFrame empty = open();
        try {
            SwingUtilities.invokeAndWait(() -> empty.dashboard.viewHistoryButton.doClick(0));
            await(() -> empty.controller.historyDialog.refreshButton.isEnabled(), 5_000, "Empty history did not load.");
            assertEquals(0, onEdt(() -> empty.controller.historyDialog.network.getRowCount()));
            assertEquals(0, onEdt(() -> empty.controller.historyDialog.alerts.getRowCount()));
        } finally {
            close(empty);
        }
        Path path = Path.of(System.getProperty("netpulse.database.path"));
        Instant now = Instant.now();
        var adapter = new NetworkAdapter(600, "fixture", "History fixture", true, false);
        var sample = new NetworkStatisticsSnapshot(adapter, "History fixture", "Up", OptionalLong.of(1000),
                OptionalLong.of(2000), 100, 50, 1_000_000_000, OptionalDouble.of(100), OptionalDouble.of(50),
                NetworkStatisticsSnapshot.State.VALID, "History test fixture, not a live reading");
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            database.transaction(() -> {
                var network = new NetworkHistoryRepository(database);
                for (int index = 0; index < 1000; index++) {
                    long days = index < 600 ? 60 : index < 900 ? 3 : 0;
                    network.save(sample, now.minus(days, ChronoUnit.DAYS).minusSeconds(index));
                }
                List<TrafficAlert> fixtures = new ArrayList<>();
                for (long days : new long[]{60, 3, 0}) {
                    Instant time = now.minus(days, ChronoUnit.DAYS);
                    fixtures.add(new TrafficAlert(time, TrafficAlert.Type.INACTIVITY, TrafficAlert.Severity.INFO,
                            "History fixture: " + days + " days old; not a live alert."));
                    new TrafficSummaryRepository(database).add("History fixture",
                            time.atZone(java.time.ZoneId.systemDefault()).toLocalDate(), 100, 50);
                }
                new AlertRepository(database).saveAll(fixtures);
            });
        }
        DashboardFrame frame = open();
        try {
            assertEquals(3, onEdt(() -> frame.dashboard.alerts.displayedAlerts.size()));
            SwingUtilities.invokeAndWait(() -> frame.dashboard.viewHistoryButton.doClick(0));
            HistoryDialog history = frame.controller.historyDialog;
            for (int range = 0; range < 3; range++) {
                int selected = range;
                SwingUtilities.invokeAndWait(() -> history.range.setSelectedIndex(selected));
                int expected = range == 0 ? 100 : range == 1 ? 400 : 500;
                await(() -> history.refreshButton.isEnabled() && history.network.getRowCount() == expected,
                        5_000, "History range returned incorrect rows.");
                assertEquals(range + 1, onEdt(() -> history.alerts.getRowCount()));
                assertEquals(range + 1, onEdt(() -> history.summaries.getRowCount()));
            }
            SwingUtilities.invokeAndWait(() -> {
                frame.setSize(860, 600);
                history.setSize(860, 420);
                history.validate();
                assertTrue(history.network.getRowCount() <= 500);
                assertTrue(frame.dashboard.downloadGraph.getWidth() > 0);
            });
            try (var lock = DriverManager.getConnection("jdbc:sqlite:" + path);
                 var statement = lock.createStatement()) {
                statement.execute("BEGIN EXCLUSIVE");
                SwingUtilities.invokeAndWait(() -> history.refreshButton.doClick(0));
                CountDownLatch ping = new CountDownLatch(1);
                SwingUtilities.invokeLater(ping::countDown);
                assertTrue(ping.await(1, TimeUnit.SECONDS), "SQLite lock blocked the EDT.");
                await(() -> history.refreshButton.isEnabled() && history.status.getText().startsWith("Could not load"),
                        5_000, "Locked history query did not report its error.");
                SwingUtilities.invokeLater(() -> frame.dashboard.alerts.clearButton.doClick(0));
                await(() -> confirmPane(JOptionPane.OK_OPTION), 3_000, "Clear confirmation not displayed.");
                await(() -> frame.dashboard.alerts.clearButton.isEnabled(), 5_000, "Locked deletion did not finish.");
                assertEquals(3, onEdt(() -> frame.dashboard.alerts.displayedAlerts.size()));
                statement.execute("ROLLBACK");
            }
            SwingUtilities.invokeAndWait(() -> history.refreshButton.doClick(0));
            await(() -> history.refreshButton.isEnabled() && history.status.getText().startsWith("500 readings"),
                    5_000, "History did not recover after releasing the lock.");
            assertEquals(3, onEdt(() -> history.alerts.getRowCount()));
        } finally {
            close(frame);
        }
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            assertEquals(1000, new NetworkHistoryRepository(database).recent(Instant.EPOCH, 1000).size());
            assertEquals(3, new AlertRepository(database).recent(Instant.EPOCH, 100).size());
        }
        System.out.println("HISTORY STABILITY VERIFIED: empty history, 1000 fixture rows, 24h/7d/all filters, 500-row cap, resizing, lock errors, failed-clear preservation and recovery.");
    }

    @Test
    void closingWhileClearConfirmationIsOpenDoesNotSubmitWorkAfterShutdown() throws Exception {
        DashboardFrame frame = open();
        AtomicReference<Throwable> edtFailure = new AtomicReference<>();
        AtomicReference<Thread.UncaughtExceptionHandler> previous = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            previous.set(Thread.currentThread().getUncaughtExceptionHandler());
            Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> edtFailure.set(failure));
        });
        try {
            SwingUtilities.invokeLater(() -> frame.dashboard.alerts.clearButton.doClick(0));
            await(() -> clearPane() != null, 3_000, "Clear confirmation did not appear.");
            SwingUtilities.invokeAndWait(() -> {
                JOptionPane pane = clearPane();
                frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
                pane.setValue(JOptionPane.OK_OPTION);
            });
            await(() -> !frame.isDisplayable(), 12_000, "Close with confirmation open did not finish.");
            SwingUtilities.invokeAndWait(() -> { });
            assertNull(edtFailure.get(), "A modal callback submitted work after executor shutdown.");
        } finally {
            SwingUtilities.invokeAndWait(() -> Thread.currentThread().setUncaughtExceptionHandler(previous.get()));
            if (onEdt(frame::isDisplayable)) close(frame);
        }
        System.out.println("MODAL SHUTDOWN VERIFIED: no rejected work or EDT exception after closing the confirmation's owner.");
    }

    private static JOptionPane clearPane() {
        for (Window window : Window.getWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing() && dialog.getTitle().equals("Clear alert history")) {
                if (dialog.getContentPane() instanceof JOptionPane pane) return pane;
                for (Component component : dialog.getContentPane().getComponents()) {
                    if (component instanceof JOptionPane pane) return pane;
                }
            }
        }
        return null;
    }

    private static boolean confirmPane(int option) {
        JOptionPane pane = clearPane();
        if (pane == null) return false;
        pane.setValue(option);
        return true;
    }

    private static void assertPortAvailable() throws Exception {
        try (ServerSocket probe = new ServerSocket(5001, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(5001, probe.getLocalPort());
        }
    }

    private static DashboardFrame open() throws Exception {
        Main.main(new String[]{"gui"});
        AtomicReference<DashboardFrame> opened = new AtomicReference<>();
        await(() -> {
            for (Frame candidate : Frame.getFrames()) {
                if (candidate instanceof DashboardFrame frame && frame.isShowing()
                        && frame.dashboard.alerts.applyButton.isEnabled() && frame.dashboard.startMonitoringButton.isEnabled()) {
                    opened.set(frame);
                    return true;
                }
            }
            return false;
        }, 8_000, "Dashboard startup did not finish.");
        return opened.get();
    }

    private static void close(DashboardFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        await(() -> !frame.isDisplayable(), 12_000, "Dashboard did not close.");
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
                && (thread.getName().equals("netpulse-database") || thread.getName().equals("netpulse-graph-collector")
                || thread.getName().equals("netpulse-adapter-monitor"))));
    }

    private static <T> T onEdt(Supplier<T> supplier) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(supplier.get()));
        return result.get();
    }

    private static void await(BooleanSupplier condition, long millis, String message) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < end) {
            if (onEdt(condition::getAsBoolean)) return;
            Thread.sleep(30);
        }
        fail(message);
    }

    /** Real BandwidthServer in its own JVM; stdin is only a test lifecycle control. */
    public static class ServerProcess {
        public static void main(String[] args) throws Exception {
            try (BandwidthServer server = new BandwidthServer()) {
                server.start();
                System.in.read();
            }
            System.out.println("SERVER PROCESS STOPPED");
        }
    }
}
