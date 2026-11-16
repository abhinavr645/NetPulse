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
            }

            click(robot, panel.stopMonitoringButton);
            waitFor(() -> panel.monitoringStatus.getText().equals("Stopped")
                    && panel.startMonitoringButton.isEnabled(), 8_000, "Monitoring did not stop.");
            assertEquals(NetworkStatisticsSnapshot.State.STOPPED, onEdt(() -> panel.displayedSnapshot.state()));

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
                .noneMatch(thread -> thread.isAlive() && thread.getName().equals("netpulse-adapter-monitor")));
        System.out.println("GUI VERIFIED: adapter selection, timer updates, unavailable adapter, TCP errors, both modes, and shutdown.");
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
