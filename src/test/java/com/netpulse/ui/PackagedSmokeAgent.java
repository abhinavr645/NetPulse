package com.netpulse.ui;

import com.netpulse.database.*;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TransferResult;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.io.PrintWriter;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** External acceptance agent: compiled for tests, never copied into the release. */
public final class PackagedSmokeAgent {
    private PackagedSmokeAgent() { }

    public static void premain(String directory, Instrumentation instrumentation) {
        new Thread(() -> verify(Path.of(directory)), "netpulse-packaged-verification").start();
    }

    private static void verify(Path directory) {
        DashboardFrame frame = null;
        try {
            Files.createDirectories(directory);
            try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(directory.resolve("result.txt")))) {
                AtomicReference<DashboardFrame> opened = new AtomicReference<>();
                await(() -> {
                    for (Frame candidate : Frame.getFrames()) {
                        if (candidate instanceof DashboardFrame dashboard && dashboard.isShowing()
                                && dashboard.dashboard.startMonitoringButton.isEnabled()
                                && dashboard.dashboard.alerts.applyButton.isEnabled()) {
                            opened.set(dashboard);
                            return true;
                        }
                    }
                    return false;
                }, 20_000, "Packaged dashboard startup failed.");
                frame = opened.get();
                DashboardPanel panel = frame.dashboard;
                DashboardFrame actualFrame = frame;
                check(Boolean.getBoolean("netpulse.packaged"), "DesktopLauncher was not used.");
                check(System.getenv("JAVA_HOME") == null, "JAVA_HOME must be unset for this check.");
                Path runtime = Path.of(System.getProperty("java.home")).toRealPath();
                check(runtime.getFileName().toString().equals("runtime"), "The bundled runtime was not selected.");
                Path jar = Path.of(DashboardFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
                check(jar.getParent().equals(runtime.getParent().resolve("app")), "UI did not load from the packaged JAR.");
                Path databasePath = DatabaseManager.defaultPath().toAbsolutePath();
                check(databasePath.equals(Path.of(System.getenv("LOCALAPPDATA"), "NetPulse", "netpulse.db").toAbsolutePath()),
                        "SQLite is not using the writable per-user data directory.");
                check(Files.isRegularFile(databasePath), "Packaged startup did not create SQLite.");
                check(!Files.exists(runtime.getParent().resolve("data")), "The application wrote data into its image.");
                log.println("Bundled runtime: " + System.getProperty("java.runtime.version"));
                log.println("Actual packaged JAR, GUI default and LOCALAPPDATA database verified.");

                boolean restart = directory.getFileName().toString().equals("restart");
                if (restart) {
                    check(onEdt(() -> panel.alerts.enabled.isSelected()), "Enabled setting was not restored.");
                    check(onEdt(() -> panel.alerts.readSettings().downloadThresholdMbps()) == 0.000001,
                            "Threshold was not restored.");
                    check(onEdt(() -> !panel.alerts.displayedAlerts.isEmpty()), "Alerts were not restored.");
                    SwingUtilities.invokeAndWait(() -> panel.viewHistoryButton.doClick(0));
                    await(() -> actualFrame.controller.historyDialog != null
                            && actualFrame.controller.historyDialog.refreshButton.isEnabled()
                            && actualFrame.controller.historyDialog.network.getRowCount() >= 2,
                            5_000, "History not visible after packaged restart.");
                    log.println("Restart: alert settings, saved alerts and network history restored.");
                } else {
                    verifyServerControls(panel, log);
                    SwingUtilities.invokeAndWait(() -> {
                        panel.alerts.downloadThreshold.setText("0.000001");
                        panel.alerts.uploadThreshold.setText("0.000001");
                        panel.alerts.inactivityDuration.setText("7");
                        panel.alerts.enabled.doClick(0);
                    });
                    await(() -> panel.alerts.applyButton.isEnabled() && panel.alerts.enabled.isSelected(),
                            5_000, "Packaged alert settings did not apply.");
                    SwingUtilities.invokeAndWait(() -> panel.startMonitoringButton.doClick(0));
                    // Generate real adapter activity using a public Java reference page.
                    try {
                        var request = URI.create("https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/NetworkInterface.html")
                                .toURL().openConnection();
                        request.setConnectTimeout(5_000);
                        request.setReadTimeout(5_000);
                        try (var input = request.getInputStream()) { input.readNBytes(64 * 1024); }
                        log.println("Real HTTPS traffic generation completed.");
                    } catch (java.io.IOException exception) {
                        log.println("HTTPS unavailable; observing existing adapter activity: " + exception.getClass().getSimpleName());
                    }
                    await(() -> panel.displayedSnapshot != null
                            && panel.displayedSnapshot.state() == NetworkStatisticsSnapshot.State.VALID
                            && panel.graphHistory.getWindow().samples().stream().filter(sample -> sample.downloadMbps().isPresent()).count() >= 3,
                            25_000, "Packaged monitoring/graphs did not collect real adapter readings.");
                    await(() -> !panel.alerts.displayedAlerts.isEmpty(), 15_000, "No actual adapter traffic crossed the test thresholds.");
                    var snapshot = onEdt(() -> panel.displayedSnapshot);
                    log.printf(java.util.Locale.ROOT, "Real adapter %s: receive %.6f Mbps, send %.6f Mbps.%n",
                            snapshot.adapterName(), snapshot.receiveMbps().orElseThrow(), snapshot.sendMbps().orElseThrow());
                    log.println("Live graphs and actual threshold alerts verified.");
                    capture(frame, panel, directory.resolve("netpulse-dashboard.png"), false);
                    capture(frame, panel.downloadGraph, directory.resolve("netpulse-graphs.png"), false);
                    capture(frame, panel.alerts, directory.resolve("netpulse-alerts.png"), true);
                    SwingUtilities.invokeAndWait(() -> panel.stopMonitoringButton.doClick(0));
                    await(panel.startMonitoringButton::isEnabled, 10_000, "Packaged monitoring did not stop.");
                    var frozen = onEdt(panel.graphHistory::getWindow);
                    Thread.sleep(800);
                    check(frozen.equals(onEdt(panel.graphHistory::getWindow)), "Graphs continued updating after stop.");
                    SwingUtilities.invokeAndWait(() -> panel.viewHistoryButton.doClick(0));
                    await(() -> actualFrame.controller.historyDialog != null
                            && actualFrame.controller.historyDialog.refreshButton.isEnabled()
                            && actualFrame.controller.historyDialog.network.getRowCount() >= 2
                            && actualFrame.controller.historyDialog.alerts.getRowCount() >= 1
                            && actualFrame.controller.historyDialog.summaries.getRowCount() >= 1,
                            5_000, "Packaged history did not show saved readings, alerts and daily totals.");
                    HistoryDialog history = frame.controller.historyDialog;
                    SwingUtilities.invokeAndWait(() -> { history.toFront(); history.requestFocus(); });
                    new Robot().waitForIdle();
                    Thread.sleep(300);
                    ImageIO.write(new Robot().createScreenCapture(onEdt(history::getBounds)), "png",
                            directory.resolve("netpulse-history.png").toFile());
                    try (DatabaseManager database = new DatabaseManager(databasePath)) {
                        database.initialize();
                        int readings = new NetworkHistoryRepository(database).recent(Instant.EPOCH, 1000).size();
                        int alerts = new AlertRepository(database).recent(Instant.EPOCH, 1000).size();
                        var totals = new TrafficSummaryRepository(database).recent(LocalDate.of(2000, 1, 1)).getFirst();
                        log.printf("SQLite: %d readings, %d alerts, observed totals %d/%d bytes.%n",
                                readings, alerts, totals.totalReceivedBytes(), totals.totalSentBytes());
                        var stopped = onEdt(() -> panel.displayedSnapshot);
                        check(stopped.totalReceivedBytes() == totals.totalReceivedBytes(), "Received summary differs from observed session total.");
                        check(stopped.totalSentBytes() == totals.totalSentBytes(), "Sent summary differs from observed session total.");
                    }
                    SwingUtilities.invokeAndWait(() -> {
                        history.setVisible(false);
                        panel.startMonitoringButton.doClick(0);
                    });
                    await(panel.stopMonitoringButton::isEnabled, 5_000, "Monitoring restart failed before shutdown.");
                }
                SwingUtilities.invokeAndWait(() -> actualFrame.dispatchEvent(new WindowEvent(actualFrame, WindowEvent.WINDOW_CLOSING)));
                await(() -> !actualFrame.isDisplayable(), 15_000, "Packaged application did not close cleanly.");
                check(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
                        && (thread.getName().equals("netpulse-database") || thread.getName().equals("netpulse-adapter-monitor")
                        || thread.getName().equals("netpulse-graph-collector") || thread.getName().equals("netpulse-server"))),
                        "Packaged background worker leaked.");
                try (var probe = new ServerSocket(5001, 1, InetAddress.getByName("127.0.0.1"))) {
                    check(probe.isBound(), "Owned TCP server did not release port 5001.");
                }
                log.println("Shutdown: monitor, database, collector and owned server closed; port 5001 reusable.");
                log.println("PASS");
            }
        } catch (Throwable failure) {
            try {
                Files.createDirectories(directory);
                try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(directory.resolve("failure.txt")))) {
                    failure.printStackTrace(log);
                }
            } catch (Exception ignored) { failure.printStackTrace(); }
            if (frame != null) {
                DashboardFrame opened = frame;
                SwingUtilities.invokeLater(() -> opened.dispatchEvent(new WindowEvent(opened, WindowEvent.WINDOW_CLOSING)));
            }
            System.exit(2);
        }
    }

    private static void verifyServerControls(DashboardPanel panel, PrintWriter log) throws Exception {
        try (var occupied = new ServerSocket(5001, 1, InetAddress.getByName("127.0.0.1"))) {
            SwingUtilities.invokeAndWait(() -> panel.startServerButton.doClick(0));
            await(() -> panel.startServerButton.isEnabled() && panel.serverStatus.getText().startsWith("Port 5001 is in use"),
                    5_000, "Occupied-port error not shown.");
            check(!occupied.isClosed() && !onEdt(panel.stopServerButton::isEnabled), "Dashboard took ownership of an external server.");
        }
        SwingUtilities.invokeAndWait(() -> panel.startServerButton.doClick(0));
        await(() -> panel.stopServerButton.isEnabled() && panel.serverStatus.getText().contains("Running"),
                5_000, "Local server failed to start.");
        for (String mode : new String[]{"Upload", "Download"}) {
            SwingUtilities.invokeAndWait(() -> {
                panel.transferMode.setSelectedItem(mode);
                panel.transferSize.setText("10");
                panel.startTestButton.doClick(0);
            });
            await(() -> panel.startTestButton.isEnabled() && panel.displayedResult != null, 12_000, mode + " failed.");
            TransferResult result = onEdt(() -> panel.displayedResult);
            check(result.getTotalBytes() == 10_000_000 && result.getMode().equalsIgnoreCase(mode), "Payload byte count/mode incorrect.");
            double expected = result.getTotalBytes() * 8.0 / result.getElapsedSeconds() / 1_000_000;
            check(Math.abs(expected - result.getThroughputMbps()) < expected * 1e-12, "Throughput formula mismatch.");
            log.printf(java.util.Locale.ROOT, "Packaged TCP %s: %d bytes, %.6f seconds, %.3f Mbps.%n",
                    mode, result.getTotalBytes(), result.getElapsedSeconds(), result.getThroughputMbps());
        }
        SwingUtilities.invokeAndWait(() -> panel.stopServerButton.doClick(0));
        await(() -> panel.startServerButton.isEnabled() && panel.serverStatus.getText().equals("Local server: Stopped"),
                5_000, "Server stop failed.");
        try (var probe = new ServerSocket(5001, 1, InetAddress.getByName("127.0.0.1"))) {
            check(probe.isBound(), "Stop did not release the port.");
        }
        SwingUtilities.invokeAndWait(() -> panel.startServerButton.doClick(0));
        await(panel.stopServerButton::isEnabled, 5_000, "Server could not restart.");
        log.println("Owned server start, port conflict, stop and restart verified.");
    }

    private static void capture(DashboardFrame frame, JComponent section, Path file, boolean sectionOnly) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            frame.toFront();
        });
        Robot robot = new Robot();
        robot.waitForIdle();
        Thread.sleep(250); // Settle focus restoration before scrolling to the requested section.
        SwingUtilities.invokeAndWait(() -> {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
            JScrollPane scroll = (JScrollPane) frame.getContentPane();
            Point offset = SwingUtilities.convertPoint(section, 0, 0, frame.dashboard);
            int maximum = Math.max(0, frame.dashboard.getHeight() - scroll.getViewport().getExtentSize().height);
            scroll.getVerticalScrollBar().setValue(sectionOnly ? scroll.getVerticalScrollBar().getMaximum()
                    : Math.min(maximum, Math.max(0, offset.y)));
        });
        robot.waitForIdle();
        Thread.sleep(250);
        Rectangle bounds = onEdt(() -> {
            if (!sectionOnly) return frame.getBounds();
            JViewport viewport = ((JScrollPane) frame.getContentPane()).getViewport();
            return new Rectangle(section.getLocationOnScreen(), section.getSize()).intersection(
                    new Rectangle(viewport.getLocationOnScreen(), viewport.getSize()));
        });
        bounds = bounds.intersection(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        if (sectionOnly) check(bounds.height >= 250, "Alert screenshot was not fully visible: " + bounds
                + onEdt(() -> " view=" + ((JScrollPane) frame.getContentPane()).getViewport().getViewPosition()
                        + " root=" + frame.dashboard.getSize() + " preferred=" + frame.dashboard.getPreferredSize()
                        + " section=" + section.getBounds()));
        ImageIO.write(robot.createScreenCapture(bounds), "png", file.toFile());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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
        throw new AssertionError(message);
    }
}
