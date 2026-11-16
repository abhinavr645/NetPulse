package com.netpulse.ui;

import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TransferResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.*;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class DashboardPanelTest {
    @ParameterizedTest
    @CsvSource({"10,10000000", "1.25,1250000", "0.000001,1", "1073.741824,1073741824"})
    void convertsDecimalMegabytesExactly(String input, long expected) {
        assertEquals(expected, DashboardController.parseTransferBytes(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "NaN", "0", "-1", "0.0000001", "1073.741825", "1e999999"})
    void rejectsInvalidTransferSizes(String input) {
        assertThrows(IllegalArgumentException.class, () -> DashboardController.parseTransferBytes(input));
    }

    @Test
    void rendersActualSnapshotFieldsAndKeepsUnavailableRatesDistinctFromZero() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DashboardPanel panel = new DashboardPanel();
            NetworkAdapter adapter = new NetworkAdapter(6, "wlan0", "Wi-Fi adapter", true, false);
            NetworkStatisticsSnapshot valid = new NetworkStatisticsSnapshot(adapter, "Wi-Fi", "Up",
                    OptionalLong.of(5_000_000), OptionalLong.of(3_000_000), 2_000_000, 1_000_000,
                    2_000_000_000L, OptionalDouble.of(1_000_000), OptionalDouble.of(500_000),
                    NetworkStatisticsSnapshot.State.VALID, "Measured adapter traffic.");
            panel.showSnapshot(valid);
            assertSame(valid, panel.displayedSnapshot);
            assertEquals("8.000000", panel.downloadValue.getText());
            assertEquals("4.000000", panel.uploadValue.getText());
            assertEquals("2.000 MB", panel.receivedValue.getText());
            assertEquals("1.000 MB", panel.sentValue.getText());
            assertTrue(panel.counterDetails.getText().contains("5000000 bytes"));

            panel.showSnapshot(new NetworkStatisticsSnapshot(adapter, "Wi-Fi", "Unavailable",
                    OptionalLong.empty(), OptionalLong.empty(), 2_000_000, 1_000_000, 0,
                    OptionalDouble.empty(), OptionalDouble.empty(),
                    NetworkStatisticsSnapshot.State.UNAVAILABLE, "PowerShell access denied."));
            assertEquals("--", panel.downloadValue.getText());
            assertTrue(panel.monitorMessage.getText().contains("access denied"));
            assertEquals("0.000000", DashboardPanel.rate(OptionalDouble.of(0)));
        });
    }

    @Test
    void adapterSelectionIsPreservedAndChangingItClearsOldReadings() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DashboardPanel panel = new DashboardPanel();
            NetworkAdapter loopback = new NetworkAdapter(1, "lo", "Loopback", true, true);
            NetworkAdapter ethernet = new NetworkAdapter(5, "eth0", "Ethernet", false, false);
            NetworkAdapter wifi = new NetworkAdapter(6, "wlan0", "Wi-Fi", true, false);
            List<NetworkAdapter> adapters = List.of(loopback, ethernet, wifi);
            panel.setAdapters(adapters, null);
            assertEquals(wifi, panel.adapterSelector.getSelectedItem());
            panel.setAdapters(adapters, 5);
            assertEquals(ethernet, panel.adapterSelector.getSelectedItem());
            assertEquals("--", panel.downloadValue.getText());
            panel.setAdapters(List.of(), null);
            assertNull(panel.adapterSelector.getSelectedItem());
            assertTrue(panel.adapterDetails.getText().contains("No network adapters"));
        });
    }

    @Test
    void showsTransferResultAndClearsOldResultsBeforeTheNextTest() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DashboardPanel panel = new DashboardPanel();
            TransferResult result = new TransferResult("DOWNLOAD", 10_000_000, 2_000_000_000L);
            panel.showResult(result);
            assertSame(result, panel.displayedResult);
            assertEquals("40.000 Mbps", panel.testThroughput.getText());
            assertEquals("2.000000 seconds", panel.testElapsed.getText());
            assertEquals("10,000,000 bytes", panel.testBytes.getText());
            panel.clearTestResult();
            assertNull(panel.displayedResult);
            assertEquals("-- Mbps", panel.testThroughput.getText());
        });
    }
}
