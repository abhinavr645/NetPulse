package com.netpulse.ui;

import com.netpulse.network.BandwidthClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class DashboardControllerTest {
    @TempDir
    Path temporaryDirectory;
    @Test
    void alertsCanBeDisabledEvenWhenUnappliedThresholdTextIsInvalid() throws Exception {
        DashboardController[] controller = new DashboardController[1];
        DashboardPanel[] panel = new DashboardPanel[1];
        CountDownLatch closed = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                panel[0] = new DashboardPanel();
                controller[0] = new DashboardController(panel[0], new BandwidthClient(),
                        temporaryDirectory.resolve("netpulse.db"));
            });
            waitForReady(panel[0]);
            SwingUtilities.invokeAndWait(() -> panel[0].alerts.enabled.doClick(0));
            waitForSettings(panel[0], "Alerts enabled");
            SwingUtilities.invokeAndWait(() -> {
                panel[0].alerts.downloadThreshold.setText("invalid draft");
                panel[0].alerts.enabled.doClick(0);
            });
            waitForSettings(panel[0], "Alerts disabled");
        } finally {
            if (controller[0] != null) {
                SwingUtilities.invokeAndWait(() -> controller[0].shutdown(closed::countDown));
                assertTrue(closed.await(3, TimeUnit.SECONDS));
            }
        }
    }

    private void waitForSettings(DashboardPanel panel, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        java.util.concurrent.atomic.AtomicReference<String> text = new java.util.concurrent.atomic.AtomicReference<>();
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> text.set(panel.alerts.settingsMessage.getText()));
            if (text.get().startsWith(expected)) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Expected " + expected + " but saw " + text.get());
    }

    private void waitForReady(DashboardPanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> ready.set(panel.alerts.enabled.isEnabled()));
            if (ready.get()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("Database initialization did not release the controls.");
    }

    @Test
    void aBlockedTransferDoesNotBlockTheEdtAndIsCancelledOnShutdown() throws Exception {
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        DashboardController[] controller = new DashboardController[1];
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             var worker = Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(5_000);
            var peer = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5_000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    assertEquals("DOWNLOAD", input.readUTF());
                    assertEquals(1_000_000, input.readLong());
                    output.writeUTF("READY");
                    output.flush();
                    requestReceived.countDown();
                    // Send no payload. Shutdown must interrupt the client's ten-second read.
                    assertEquals(-1, input.read());
                }
                return null;
            });
            try {
                SwingUtilities.invokeAndWait(() -> {
                    DashboardPanel panel = new DashboardPanel();
                    controller[0] = new DashboardController(panel,
                            new BandwidthClient(listener.getLocalPort(), 10_000),
                            temporaryDirectory.resolve("netpulse.db"));
                    panel.transferMode.setSelectedItem("Download");
                    panel.transferSize.setText("1");
                    panel.startTestButton.doClick(0);
                    assertFalse(panel.startTestButton.isEnabled());
                });
                assertTrue(requestReceived.await(3, TimeUnit.SECONDS));
                CountDownLatch edtResponded = new CountDownLatch(1);
                SwingUtilities.invokeLater(edtResponded::countDown);
                assertTrue(edtResponded.await(1, TimeUnit.SECONDS), "The Swing EDT must remain responsive.");
            } finally {
                if (controller[0] != null) {
                    SwingUtilities.invokeAndWait(() -> controller[0].shutdown(closed::countDown));
                }
            }
            assertTrue(closed.await(3, TimeUnit.SECONDS), "Cleanup should interrupt socket I/O promptly.");
            peer.get(3, TimeUnit.SECONDS);
        }
    }
}
