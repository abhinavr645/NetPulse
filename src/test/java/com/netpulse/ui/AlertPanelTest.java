package com.netpulse.ui;

import com.netpulse.model.AlertSettings;
import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TrafficAlert;
import com.netpulse.monitoring.TrafficAnalyzer;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class AlertPanelTest {
    @Test
    void defaultsAndValidThresholdControlsMatchTheAnalyzerSettings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AlertPanel panel = new AlertPanel();
            assertEquals(AlertSettings.defaults(), panel.readSettings());
            panel.enabled.setSelected(true);
            panel.downloadThreshold.setText("0.01");
            panel.uploadThreshold.setText("0.02");
            panel.inactivityDuration.setText("5");
            assertEquals(new AlertSettings(true, 0.01, 0.02, 5), panel.readSettings());
        });
    }

    @Test
    void invalidControlsAreRejectedWithoutGeneratingAlerts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AlertPanel panel = new AlertPanel();
            panel.downloadThreshold.setText("NaN");
            assertThrows(IllegalArgumentException.class, panel::readSettings);
            panel.downloadThreshold.setText("100");
            panel.inactivityDuration.setText("1.5");
            assertThrows(IllegalArgumentException.class, panel::readSettings);
            assertEquals(0, panel.historyTable.getRowCount());
        });
    }

    @Test
    void allSeverityLevelsAndHistoryFieldsAreVisibleAndBounded() throws Exception {
        List<TrafficAlert> alerts = new ArrayList<>();
        for (int index = 0; index < 120; index++) {
            alerts.add(new TrafficAlert(Instant.parse("2026-11-17T00:00:00Z"), TrafficAlert.Type.DOWNLOAD_THRESHOLD,
                    TrafficAlert.Severity.values()[index % 3], "Test alert " + index));
        }
        SwingUtilities.invokeAndWait(() -> {
            AlertPanel panel = new AlertPanel();
            panel.showHistory(alerts);
            assertEquals(100, panel.historyTable.getRowCount());
            assertEquals(4, panel.historyTable.getColumnCount());
            assertEquals("Test alert 0", panel.historyTable.getValueAt(0, 3));
            assertNotEquals(AlertPanel.severityColor(TrafficAlert.Severity.INFO),
                    AlertPanel.severityColor(TrafficAlert.Severity.WARNING));
            assertNotEquals(AlertPanel.severityColor(TrafficAlert.Severity.WARNING),
                    AlertPanel.severityColor(TrafficAlert.Severity.CRITICAL));
            panel.showHistory(List.of());
            assertEquals(0, panel.historyTable.getRowCount());
        });
    }

    @Test
    void inactivityDetectionOutputRendersAsAnInformationalGuiAlert() throws Exception {
        // Analysis runs off the EDT; only rendering runs on it. These are explicitly controlled test samples.
        TrafficAnalyzer analyzer = new TrafficAnalyzer();
        analyzer.configure(new AlertSettings(true, 100, 50, 2));
        var zero = new NetworkStatisticsSnapshot(new NetworkAdapter(6, "test", "Test adapter", true, false),
                "Test adapter", "Up", OptionalLong.of(0), OptionalLong.of(0), 0, 0, 2_000_000_000L,
                OptionalDouble.of(0), OptionalDouble.of(0), NetworkStatisticsSnapshot.State.VALID,
                "Controlled zero-traffic test interval");
        analyzer.analyze(zero);
        var history = analyzer.getHistory();
        SwingUtilities.invokeAndWait(() -> {
            AlertPanel panel = new AlertPanel();
            panel.showHistory(history);
            assertEquals(TrafficAlert.Severity.INFO, panel.historyTable.getValueAt(0, 1));
            assertEquals("Inactivity", panel.historyTable.getValueAt(0, 2));
            assertTrue(panel.historyTable.getValueAt(0, 3).toString().contains("does not imply Internet disconnection"));
        });
    }
}
