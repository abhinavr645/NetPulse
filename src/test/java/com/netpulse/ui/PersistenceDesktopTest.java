package com.netpulse.ui;

import com.netpulse.Main;
import com.netpulse.database.*;
import com.netpulse.model.AlertSettings;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "netpulse.gui.smoke", matches = "true")
@Timeout(75)
class PersistenceDesktopTest {
    @TempDir Path directory;
    private String previousPath;

    @BeforeEach
    void rememberPath() {
        previousPath = System.getProperty("netpulse.database.path");
    }

    @AfterEach
    void restorePath() {
        if (previousPath == null) System.clearProperty("netpulse.database.path");
        else System.setProperty("netpulse.database.path", previousPath);
    }

    @Test
    void actualReadingsAlertsSettingsAndHistorySurviveRestartAndConfirmedClear() throws Exception {
        Path path = directory.resolve("data/netpulse.db");
        System.setProperty("netpulse.database.path", path.toString());
        AlertSettings expected = new AlertSettings(true, 0.000001, 0.000001, 7);
        DashboardFrame first = open();
        try {
            DashboardPanel panel = first.dashboard;
            SwingUtilities.invokeAndWait(() -> {
                panel.alerts.downloadThreshold.setText("0.000001");
                panel.alerts.uploadThreshold.setText("0.000001");
                panel.alerts.inactivityDuration.setText("7");
                panel.alerts.enabled.doClick(0);
            });
            await(() -> panel.alerts.applyButton.isEnabled() && panel.alerts.enabled.isSelected(), 3_000, "Settings not applied.");
            SwingUtilities.invokeAndWait(() -> panel.startMonitoringButton.doClick(0));
            await(() -> panel.graphHistory.getWindow().samples().stream()
                    .filter(sample -> sample.downloadMbps().isPresent()).count() >= 3,
                    20_000, "No real adapter readings were collected.");
            await(() -> !panel.alerts.displayedAlerts.isEmpty(), 10_000, "Actual traffic did not generate an alert.");
            SwingUtilities.invokeAndWait(() -> panel.stopMonitoringButton.doClick(0));
            await(panel.startMonitoringButton::isEnabled, 8_000, "Monitor did not stop.");
        } finally {
            close(first);
        }
        int savedReadings;
        int savedAlerts;
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            var readings = new NetworkHistoryRepository(database).recent(Instant.EPOCH, 500);
            var alerts = new AlertRepository(database).recent(Instant.EPOCH, 100);
            var totals = new TrafficSummaryRepository(database).recent(LocalDate.of(2000, 1, 1));
            savedReadings = readings.size();
            savedAlerts = alerts.size();
            assertTrue(savedReadings >= 2);
            assertTrue(savedAlerts >= 1);
            assertFalse(totals.isEmpty());
            assertEquals(expected, new AlertSettingsRepository(database).load());
            System.out.println("SQLITE REAL DATA VERIFIED: " + savedReadings + " network readings, " + savedAlerts
                    + " alerts, totals=" + totals.getFirst().totalReceivedBytes() + "/"
                    + totals.getFirst().totalSentBytes() + " bytes; settings=" + expected);
        }

        DashboardFrame second = open();
        try {
            DashboardPanel panel = second.dashboard;
            assertEquals(expected, onEdt(panel.alerts::readSettings));
            assertEquals(savedAlerts, onEdt(() -> panel.alerts.displayedAlerts.size()));
            SwingUtilities.invokeAndWait(() -> panel.viewHistoryButton.doClick(0));
            await(() -> second.controller.historyDialog != null
                    && second.controller.historyDialog.network.getRowCount() == savedReadings,
                    5_000, "Saved network history was not visible after restart.");
            HistoryDialog history = second.controller.historyDialog;
            assertEquals(savedAlerts, onEdt(() -> history.alerts.getRowCount()));
            assertTrue(onEdt(() -> history.summaries.getRowCount()) > 0);
            Robot robot = new Robot();
            SwingUtilities.invokeAndWait(() -> {
                history.toFront();
                history.requestFocus();
            });
            robot.waitForIdle();
            Thread.sleep(300); // Let the Windows compositor present the separate history window.
            Path screenshot = Path.of("target", "netpulse-history.png");
            Files.createDirectories(screenshot.getParent());
            ImageIO.write(robot.createScreenCapture(onEdt(history::getBounds)), "png", screenshot.toFile());
            System.out.println("Saved-history screenshot: " + screenshot.toAbsolutePath());

            confirmClear(panel, JOptionPane.CANCEL_OPTION);
            assertEquals(savedAlerts, onEdt(() -> panel.alerts.displayedAlerts.size()));
            confirmClear(panel, JOptionPane.OK_OPTION);
            await(() -> panel.alerts.displayedAlerts.isEmpty() && panel.alerts.clearButton.isEnabled(),
                    5_000, "Confirmed clear did not clear displayed and saved alerts.");
            SwingUtilities.invokeAndWait(() -> history.refreshButton.doClick(0));
            await(() -> history.refreshButton.isEnabled() && history.alerts.getRowCount() == 0,
                    5_000, "Saved alert history was not cleared.");
            assertEquals(savedReadings, onEdt(() -> history.network.getRowCount()));
        } finally {
            close(second);
        }
        try (DatabaseManager database = new DatabaseManager(path)) {
            database.initialize();
            assertTrue(new AlertRepository(database).recent(Instant.EPOCH, 100).isEmpty());
            assertEquals(expected, new AlertSettingsRepository(database).load());
        }
        System.out.println("SQLITE RESTART VERIFIED: settings and alert/history restoration, history viewer, cancel/confirm clear, file cleanup.");
    }

    @Test
    void unavailableDatabaseLeavesGuiResponsiveAndRefreshCanRecover() throws Exception {
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "Not a directory");
        Path path = blocked.resolve("netpulse.db");
        System.setProperty("netpulse.database.path", path.toString());
        DashboardFrame frame = open();
        try {
            DashboardPanel panel = frame.dashboard;
            assertTrue(onEdt(panel.storageStatus::getText).startsWith("Storage unavailable"));
            CountDownLatch ping = new CountDownLatch(1);
            SwingUtilities.invokeLater(ping::countDown);
            assertTrue(ping.await(1, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> panel.viewHistoryButton.doClick(0));
            await(() -> frame.controller.historyDialog.status.getText().startsWith("Could not load"),
                    5_000, "Read failure was not reported.");
            Files.delete(blocked);
            SwingUtilities.invokeAndWait(() -> frame.controller.historyDialog.refreshButton.doClick(0));
            await(() -> panel.storageStatus.getText().startsWith("History ready")
                    && frame.controller.historyDialog.refreshButton.isEnabled(), 5_000, "Storage did not recover.");
        } finally {
            close(frame);
        }
        Files.move(path, blocked.resolve("closed.db"));
        System.out.println("SQLITE FAILURE VERIFIED: visible error, responsive EDT, retry after path recovery, closed database file.");
    }

    private void confirmClear(DashboardPanel panel, int choice) throws Exception {
        SwingUtilities.invokeLater(() -> panel.alerts.clearButton.doClick(0));
        await(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                        && dialog.getTitle().equals("Clear alert history")) {
                    JOptionPane pane = findPane(dialog);
                    assertNotNull(pane);
                    pane.setValue(choice);
                    return true;
                }
            }
            return false;
        }, 3_000, "The clear-history confirmation did not appear.");
    }

    private JOptionPane findPane(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JOptionPane pane) return pane;
            if (component instanceof Container child) {
                JOptionPane pane = findPane(child);
                if (pane != null) return pane;
            }
        }
        return null;
    }

    private DashboardFrame open() throws Exception {
        Main.main(new String[]{"gui"});
        AtomicReference<DashboardFrame> frame = new AtomicReference<>();
        await(() -> {
            for (Frame candidate : Frame.getFrames()) {
                if (candidate instanceof DashboardFrame dashboard && dashboard.isShowing()
                        && dashboard.dashboard.alerts.applyButton.isEnabled()
                        && dashboard.dashboard.startMonitoringButton.isEnabled()) {
                    frame.set(dashboard);
                    return true;
                }
            }
            return false;
        }, 8_000, "Dashboard did not finish startup.");
        return frame.get();
    }

    private void close(DashboardFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
        await(() -> !frame.isDisplayable(), 12_000, "Dashboard/database did not shut down.");
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().equals("netpulse-database")));
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
}
