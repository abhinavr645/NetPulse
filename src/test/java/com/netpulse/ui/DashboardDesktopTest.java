package com.netpulse.ui;

import com.netpulse.Main;
import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TransferResult;
import com.netpulse.network.BandwidthServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.WindowEvent;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in verification: opens a real window and reads real Windows adapter counters. */
@EnabledIfSystemProperty(named = "netpulse.gui.smoke", matches = "true")
@Timeout(75)
class DashboardDesktopTest {
    @Test
    void realWindowsDashboardMonitoringTransfersAndShutdown() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "An interactive Windows desktop is required.");
        // Do not connect to or shut down a server owned by somebody else.
        try (ServerSocket probe = new ServerSocket(5001, 1, InetAddress.getByName("127.0.0.1"))) {
            assertEquals(5001, probe.getLocalPort());
        }
        Robot robot = new Robot();
        robot.setAutoDelay(60);
        Main.main(new String[]{"gui"});
        AtomicReference<DashboardFrame> opened = new AtomicReference<>();
        waitFor(() -> {
            for (Frame frame : Frame.getFrames()) {
                if (frame instanceof DashboardFrame dashboard && frame.isShowing()) {
                    opened.set(dashboard);
                    return true;
                }
            }
            return false;
        }, 5_000, "Dashboard did not open.");
        DashboardFrame frame = opened.get();
        DashboardPanel panel = frame.dashboard;
        try {
            waitFor(() -> panel.adapterSelector.getItemCount() > 0
                    && panel.refreshAdaptersButton.isEnabled(), 5_000, "Adapters did not load.");
            NetworkAdapter active = onEdt(() -> {
                for (int index = 0; index < panel.adapterSelector.getItemCount(); index++) {
                    NetworkAdapter adapter = panel.adapterSelector.getItemAt(index);
                    if (adapter.up() && !adapter.loopback()) {
                        panel.adapterSelector.setSelectedItem(adapter);
                        return adapter;
                    }
                }
                throw new AssertionError("No active non-loopback adapter is available.");
            });
            click(robot, panel.refreshAdaptersButton);
            waitFor(() -> panel.refreshAdaptersButton.isEnabled(), 5_000, "Adapter refresh did not finish.");
            assertEquals(active.index(), onEdt(() -> ((NetworkAdapter) panel.adapterSelector.getSelectedItem()).index()));
            click(robot, panel.startMonitoringButton);
            waitFor(() -> panel.displayedSnapshot != null
                    && panel.displayedSnapshot.state() == NetworkStatisticsSnapshot.State.VALID,
                    20_000, "No real adapter speed sample appeared.");
            NetworkStatisticsSnapshot snapshot = onEdt(() -> panel.displayedSnapshot);
            assertEquals("Running", onEdt(panel.monitoringStatus::getText));
            assertFalse(onEdt(panel.adapterSelector::isEnabled));
            assertEquals(DashboardPanel.rate(snapshot.receiveMbps()), onEdt(panel.downloadValue::getText));
            assertEquals(DashboardPanel.rate(snapshot.sendMbps()), onEdt(panel.uploadValue::getText));
            System.out.printf(Locale.ROOT,
                    "GUI ADAPTER VERIFIED: %s [%d] | receive=%.6f Mbps | send=%.6f Mbps | totals=%d/%d bytes%n",
                    snapshot.adapterName(), active.index(), snapshot.receiveMbps().orElseThrow(),
                    snapshot.sendMbps().orElseThrow(), snapshot.totalReceivedBytes(), snapshot.totalSentBytes());

            // Generate real adapter traffic by reading a public Java API page, never by inserting graph values.
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/net/NetworkInterface.html"))
                        .timeout(Duration.ofSeconds(10)).GET().build();
                var response = http.send(request, HttpResponse.BodyHandlers.discarding());
                System.out.println("Real HTTP adapter traffic source returned status " + response.statusCode());
            } catch (java.io.IOException exception) {
                System.out.println("HTTP traffic source unavailable; observing existing adapter traffic: " + exception.getMessage());
            }
            waitFor(() -> panel.graphHistory.getWindow().samples().stream()
                    .filter(sample -> sample.downloadMbps().isPresent()).count() >= 3,
                    15_000, "Graphs did not collect successive real adapter readings.");
            SwingUtilities.invokeAndWait(() -> {
                var window = panel.graphHistory.getWindow();
                assertTrue(window.running());
                assertTrue(window.samples().stream().anyMatch(sample -> sample.downloadMbps().orElse(0) > 0
                        || sample.uploadMbps().orElse(0) > 0), "A real network activity sample is required.");
                System.out.println("GUI GRAPHS VERIFIED: " + window.samples().size()
                        + " distinct adapter readings; zero/absent readings are not fabricated.");
            });
            Dimension originalSize = onEdt(frame::getSize);
            int originalGraphWidth = onEdt(panel.downloadGraph::getWidth);
            SwingUtilities.invokeAndWait(() -> frame.setSize(860, 640));
            robot.waitForIdle();
            waitFor(() -> panel.downloadGraph.getWidth() < originalGraphWidth,
                    3_000, "Graph width did not adapt to a smaller window.");
            assertTrue(onEdt(panel.downloadGraph::getWidth) >= 250);
            SwingUtilities.invokeAndWait(() -> frame.setSize(originalSize));
            robot.waitForIdle();

            click(robot, panel.startTestButton);
            waitFor(() -> panel.startTestButton.isEnabled() && panel.testMessage.getText().contains("Cannot connect"),
                    5_000, "Missing TCP server did not produce a clear error.");
            assertNull(onEdt(() -> panel.displayedResult));

            try (BandwidthServer server = new BandwidthServer()) {
                server.start();
                for (String mode : new String[]{"Upload", "Download"}) {
                    SwingUtilities.invokeAndWait(() -> {
                        panel.transferMode.setSelectedItem(mode);
                        panel.transferSize.setText("10");
                    });
                    click(robot, panel.startTestButton);
                    waitFor(() -> panel.displayedResult != null && panel.startTestButton.isEnabled(),
                            10_000, mode + " did not finish.");
                    TransferResult result = onEdt(() -> panel.displayedResult);
                    assertEquals(mode.toUpperCase(Locale.ROOT), result.getMode());
                    assertEquals(10_000_000, result.getTotalBytes());
                    assertTrue(result.getElapsedNanos() > 0);
                    System.out.printf(Locale.ROOT,
                            "GUI TCP VERIFIED: %s | bytes=%d | elapsed=%.6f s | %.3f Mbps%n",
                            result.getMode(), result.getTotalBytes(), result.getElapsedSeconds(), result.getThroughputMbps());
                }
                SwingUtilities.invokeAndWait(() -> panel.scrollRectToVisible(new Rectangle(0, 0, 1, 1)));
                robot.waitForIdle();
                Path screenshot = Path.of("target", "netpulse-dashboard.png");
                Files.createDirectories(screenshot.getParent());
                ImageIO.write(robot.createScreenCapture(onEdt(frame::getBounds)), "png", screenshot.toFile());
                System.out.println("GUI screenshot: " + screenshot.toAbsolutePath());
                SwingUtilities.invokeAndWait(() -> panel.downloadGraph.scrollRectToVisible(
                        new Rectangle(0, 0, panel.downloadGraph.getWidth(), panel.downloadGraph.getHeight())));
                robot.waitForIdle();
                Path graphScreenshot = Path.of("target", "netpulse-graphs.png");
                ImageIO.write(robot.createScreenCapture(onEdt(frame::getBounds)), "png", graphScreenshot.toFile());
                System.out.println("GUI graph screenshot: " + graphScreenshot.toAbsolutePath());
            }

            click(robot, panel.stopMonitoringButton);
            waitFor(() -> panel.monitoringStatus.getText().equals("Stopped")
                    && panel.startMonitoringButton.isEnabled(), 8_000, "Monitoring did not stop.");
            assertEquals(NetworkStatisticsSnapshot.State.STOPPED, onEdt(() -> panel.displayedSnapshot.state()));
            var frozenGraph = onEdt(panel.graphHistory::getWindow);
            assertFalse(frozenGraph.running());
            assertFalse(frozenGraph.samples().isEmpty());
            Thread.sleep(1_200); // Off the EDT: allow multiple display ticks to verify the graph remains frozen.
            assertEquals(frozenGraph, onEdt(panel.graphHistory::getWindow));

            // Exercise an unavailable Windows interface through the actual dashboard.
            NetworkAdapter loopback = onEdt(() -> {
                for (int i = 0; i < panel.adapterSelector.getItemCount(); i++) {
                    NetworkAdapter adapter = panel.adapterSelector.getItemAt(i);
                    if (adapter.loopback()) {
                        panel.adapterSelector.setSelectedItem(adapter);
                        return adapter;
                    }
                }
                throw new AssertionError("Loopback adapter was not listed.");
            });
            click(robot, panel.startMonitoringButton);
            waitFor(() -> panel.displayedSnapshot != null
                    && panel.displayedSnapshot.state() == NetworkStatisticsSnapshot.State.UNAVAILABLE,
                    10_000, "Unavailable adapter state did not appear.");
            assertEquals(loopback.index(), onEdt(() -> panel.displayedSnapshot.adapter().index()));
            assertEquals("--", onEdt(panel.downloadValue::getText));
            assertTrue(onEdt(panel.monitorMessage::getText).contains("PowerShell"));
            waitFor(() -> panel.graphHistory.getWindow().samples().stream()
                    .anyMatch(sample -> sample.downloadMbps().isEmpty() && sample.uploadMbps().isEmpty()),
                    3_000, "Unavailable graph reading was not preserved as a gap.");
            click(robot, panel.stopMonitoringButton);
            waitFor(panel.startMonitoringButton::isEnabled, 8_000, "Unavailable monitor did not stop.");

            // Closing with monitoring active must stop the worker and any active PowerShell query.
            SwingUtilities.invokeAndWait(() -> panel.adapterSelector.setSelectedItem(active));
            click(robot, panel.startMonitoringButton);
            waitFor(() -> panel.monitoringStatus.getText().equals("Running"), 5_000, "Restart failed.");
        } finally {
            SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
            waitFor(() -> !frame.isDisplayable(), 10_000, "Dashboard did not close cleanly.");
        }
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && (thread.getName().equals("netpulse-adapter-monitor")
                        || thread.getName().equals("netpulse-graph-collector"))));
        System.out.println("GUI VERIFIED: live graphs, graph freeze, resizing, adapter controls, TCP tests, and shutdown.");
    }

    private static void click(Robot robot, JComponent component) throws Exception {
        SwingUtilities.invokeAndWait(() -> component.scrollRectToVisible(
                new Rectangle(0, 0, component.getWidth(), component.getHeight())));
        robot.waitForIdle();
        Point point = onEdt(component::getLocationOnScreen);
        Dimension size = onEdt(component::getSize);
        robot.mouseMove(point.x + size.width / 2, point.y + size.height / 2);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.waitForIdle();
    }

    private static <T> T onEdt(Supplier<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }

    private static void waitFor(BooleanSupplier condition, long timeoutMillis, String message) throws Exception {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (onEdt(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(30);
        }
        fail(message);
    }
}
