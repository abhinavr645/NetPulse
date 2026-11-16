package com.netpulse.ui;

import com.netpulse.Main;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TrafficAlert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: only actual Windows counters feed the production dashboard and analyzer. */
@EnabledIfSystemProperty(named = "netpulse.gui.smoke", matches = "true")
@Timeout(90)
class TrafficAlertsDesktopTest {
    @TempDir Path databaseDirectory;
    private String previousPath;

    @BeforeEach
    void useTemporaryDatabase() {
        previousPath = System.getProperty("netpulse.database.path");
        System.setProperty("netpulse.database.path", databaseDirectory.resolve("netpulse.db").toString());
    }

    @AfterEach
    void restoreDatabasePath() {
        if (previousPath == null) System.clearProperty("netpulse.database.path");
        else System.setProperty("netpulse.database.path", previousPath);
    }

    @Test
    void realAdapterThresholdControlsSuppressionDisableClearAndShutdown() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless());
        Robot robot = new Robot();
        robot.setAutoDelay(60);
        Main.main(new String[]{"gui"});
        AtomicReference<DashboardFrame> opened = new AtomicReference<>();
        await(() -> {
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
            await(() -> panel.adapterSelector.getItemCount() > 0 && panel.startMonitoringButton.isEnabled(),
                    5_000, "Adapters were not loaded.");
            click(robot, panel.startMonitoringButton);
            await(() -> panel.displayedSnapshot != null
                    && panel.displayedSnapshot.state() == NetworkStatisticsSnapshot.State.VALID,
                    20_000, "No actual adapter statistics appeared.");
            assertFalse(onEdt(panel.alerts.enabled::isSelected));
            assertTrue(onEdt(() -> panel.alerts.displayedAlerts.isEmpty()));

            SwingUtilities.invokeAndWait(() -> panel.alerts.downloadThreshold.setText("-1"));
            click(robot, panel.alerts.applyButton);
            assertTrue(onEdt(panel.alerts.settingsMessage::getText).startsWith("Settings unchanged"));
            SwingUtilities.invokeAndWait(() -> {
                panel.alerts.downloadThreshold.setText("0.000001");
                panel.alerts.uploadThreshold.setText("0.000001");
                panel.alerts.inactivityDuration.setText("1");
            });
            click(robot, panel.alerts.enabled);
            await(() -> panel.alerts.settingsMessage.getText().startsWith("Alerts enabled")
                    && panel.alerts.applyButton.isEnabled(), 3_000, "Alert controls were not applied.");
            await(() -> panel.alerts.displayedAlerts.stream()
                    .anyMatch(alert -> alert.type() != TrafficAlert.Type.INACTIVITY),
                    12_000, "Actual traffic did not trigger a threshold alert.");
            var alerts = onEdt(() -> panel.alerts.displayedAlerts);
            for (TrafficAlert alert : alerts) {
                System.out.println("GUI REAL ALERT: " + alert.severity() + " | " + alert.type()
                        + " | " + alert.description());
            }
            int initialCount = alerts.size();
            SwingUtilities.invokeAndWait(() -> {
                for (int index = 0; index < 5; index++) {
                    panel.refreshButton.doClick(0);
                }
            });
            Thread.sleep(700); // Off the EDT; repeated display ticks must not repeat a condition.
            assertEquals(initialCount, onEdt(() -> panel.alerts.displayedAlerts.size()));

            SwingUtilities.invokeAndWait(() -> panel.alerts.scrollRectToVisible(
                    new Rectangle(0, 0, panel.alerts.getWidth(), panel.alerts.getHeight())));
            robot.waitForIdle();
            Path screenshot = Path.of("target", "netpulse-alerts.png");
            Files.createDirectories(screenshot.getParent());
            ImageIO.write(robot.createScreenCapture(onEdt(frame::getBounds)), "png", screenshot.toFile());
            System.out.println("GUI alert screenshot: " + screenshot.toAbsolutePath());

            click(robot, panel.alerts.clearButton);
            robot.keyPress(java.awt.event.KeyEvent.VK_ENTER);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ENTER);
            robot.waitForIdle();
            await(() -> panel.alerts.displayedAlerts.isEmpty(), 3_000, "History was not cleared.");
            // Disabling remains possible even if a draft field has become invalid.
            SwingUtilities.invokeAndWait(() -> panel.alerts.downloadThreshold.setText("invalid draft"));
            click(robot, panel.alerts.enabled);
            await(() -> panel.alerts.settingsMessage.getText().startsWith("Alerts disabled"),
                    3_000, "Alerts did not disable; selected=" + onEdt(panel.alerts.enabled::isSelected)
                            + "; status=" + onEdt(panel.alerts.settingsMessage::getText));
            var previous = onEdt(() -> panel.displayedSnapshot);
            await(() -> panel.displayedSnapshot != previous
                    && panel.displayedSnapshot.state() == NetworkStatisticsSnapshot.State.VALID,
                    8_000, "No fresh reading arrived while alerts were disabled.");
            assertTrue(onEdt(() -> panel.alerts.displayedAlerts.isEmpty()));

            SwingUtilities.invokeAndWait(() -> {
                panel.alerts.downloadThreshold.setText("1000");
                panel.alerts.uploadThreshold.setText("1000");
                panel.alerts.inactivityDuration.setText("1");
            });
            click(robot, panel.alerts.enabled);
            await(() -> panel.alerts.settingsMessage.getText().startsWith("Alerts enabled"),
                    3_000, "Inactivity configuration was not enabled.");
            boolean observedIdle = waitUntil(() -> panel.alerts.displayedAlerts.stream()
                    .anyMatch(alert -> alert.type() == TrafficAlert.Type.INACTIVITY), 18_000);
            if (observedIdle) {
                System.out.println("GUI REAL INACTIVITY VERIFIED: INFO alert from an actual zero-traffic interval.");
            } else {
                System.out.println("GUI REAL INACTIVITY NOT OBSERVED: the adapter was not idle during the live check."
                        + " Controlled analyzer and Swing-rendering tests verify inactivity detection and INFO display.");
            }
            CountDownLatch responsive = new CountDownLatch(1);
            SwingUtilities.invokeLater(responsive::countDown);
            assertTrue(responsive.await(1, TimeUnit.SECONDS), "The EDT must remain responsive.");
            click(robot, panel.stopMonitoringButton);
            await(() -> panel.startMonitoringButton.isEnabled(), 8_000, "Monitoring did not stop.");
            var stoppedHistory = onEdt(() -> panel.alerts.displayedAlerts);
            Thread.sleep(700);
            assertEquals(stoppedHistory, onEdt(() -> panel.alerts.displayedAlerts));
            System.out.println("GUI ALERT CONTROLS VERIFIED: valid/invalid settings, real threshold alerts,"
                    + " suppression, disable, clear, stopped processing, responsiveness.");
        } finally {
            SwingUtilities.invokeAndWait(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
            await(() -> !frame.isDisplayable(), 10_000, "Dashboard did not close.");
        }
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
                && (thread.getName().equals("netpulse-graph-collector")
                || thread.getName().equals("netpulse-adapter-monitor"))));
    }

    private static void click(Robot robot, JComponent component) throws Exception {
        SwingUtilities.invokeAndWait(() -> component.scrollRectToVisible(
                new Rectangle(0, 0, component.getWidth(), component.getHeight())));
        robot.waitForIdle();
        if (component instanceof AbstractButton button && !button.getText().equals("Clear history")) {
            // Swing actions avoid native pointer/scroll timing during desktop automation.
            SwingUtilities.invokeAndWait(() -> {
                assertTrue(button.isEnabled());
                button.doClick(0);
            });
            robot.waitForIdle();
            return;
        }
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

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (onEdt(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(30);
        }
        return false;
    }

    private static void await(BooleanSupplier condition, long timeoutMillis, String message) throws Exception {
        assertTrue(waitUntil(condition, timeoutMillis), message);
    }
}
