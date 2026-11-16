package com.netpulse.ui;

import com.netpulse.model.BandwidthGraphHistory;
import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class BandwidthGraphPanelTest {
    @Test
    void paintsEmptyZeroAndUnavailableReadingsAtDifferentSizesOnTheEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BandwidthGraphHistory history = new BandwidthGraphHistory();
            BandwidthGraphPanel download = new BandwidthGraphPanel(history, true);
            BandwidthGraphPanel upload = new BandwidthGraphPanel(history, false);
            paint(download, 480, 240);
            history.start();
            history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID));
            paint(download, 600, 260);
            paint(upload, 300, 210);
            history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.UNAVAILABLE));
            paint(download, 40, 40); // Tiny resize must not create negative plot dimensions.
            history.stop();
            var frozen = history.getWindow();
            paint(upload, 480, 240);
            assertEquals(frozen, history.getWindow());
        });
    }

    private void paint(BandwidthGraphPanel panel, int width, int height) {
        panel.setSize(width, height);
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            panel.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }

    private NetworkStatisticsSnapshot snapshot(NetworkStatisticsSnapshot.State state) {
        return new NetworkStatisticsSnapshot(new NetworkAdapter(6, "wlan0", "Wi-Fi", true, false),
                "Wi-Fi", "Up", OptionalLong.of(100), OptionalLong.of(200), 0, 0, 1_000_000_000L,
                OptionalDouble.of(0), OptionalDouble.of(0), state, "Test reading");
    }
}
