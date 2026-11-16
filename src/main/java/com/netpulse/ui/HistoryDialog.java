package com.netpulse.ui;

import com.netpulse.database.PersistenceService;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/** A small, nonmodal history viewer. The controller loads its data off the EDT. */
final class HistoryDialog extends JDialog {
    final JComboBox<String> range = new JComboBox<>(new String[]{"Last 24 hours", "Last 7 days", "All time"});
    final JButton refreshButton = DashboardStyle.button("Refresh history", false);
    final JLabel status = DashboardStyle.label("Loading saved history...", 12, DashboardStyle.MUTED);
    final DefaultTableModel network = model("Time (local)", "Adapter", "Download Mbps", "Upload Mbps", "Received counter", "Sent counter");
    final DefaultTableModel alerts = model("Time (local)", "Type", "Severity", "Description");
    final DefaultTableModel summaries = model("Date", "Adapter", "Total received bytes", "Total sent bytes");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    HistoryDialog(Window owner) {
        super(owner, "NetPulse - Saved history", ModalityType.MODELESS);
        JPanel body = new JPanel(new BorderLayout(12, 12));
        body.setBackground(DashboardStyle.BACKGROUND);
        body.setBorder(DashboardStyle.padding(18));
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controls.add(DashboardStyle.label("Time range", 13, DashboardStyle.TEXT));
        controls.add(range);
        controls.add(refreshButton);
        body.add(controls, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Network readings", table(network));
        tabs.addTab("Saved alerts", table(alerts));
        tabs.addTab("Daily totals", table(summaries));
        body.add(tabs, BorderLayout.CENTER);
        body.add(status, BorderLayout.SOUTH);
        setContentPane(body);
        setSize(950, 450);
        setLocationRelativeTo(owner);
        setDefaultCloseOperation(HIDE_ON_CLOSE);
    }

    Instant from() {
        return switch (range.getSelectedIndex()) {
            case 0 -> Instant.now().minus(1, ChronoUnit.DAYS);
            case 1 -> Instant.now().minus(7, ChronoUnit.DAYS);
            default -> Instant.EPOCH;
        };
    }

    void showHistory(PersistenceService.SavedHistory history) {
        network.setRowCount(0);
        alerts.setRowCount(0);
        summaries.setRowCount(0);
        for (var entry : history.network()) {
            network.addRow(new Object[]{TIME.format(entry.timestamp()), entry.adapterName(),
                    String.format(Locale.ROOT, "%.6f", entry.downloadMbps()),
                    String.format(Locale.ROOT, "%.6f", entry.uploadMbps()), entry.receivedBytes(), entry.sentBytes()});
        }
        for (var alert : history.alerts()) {
            alerts.addRow(new Object[]{TIME.format(alert.timestamp()), alert.type(), alert.severity(), alert.description()});
        }
        for (var summary : history.summaries()) {
            summaries.addRow(new Object[]{summary.date(), summary.adapterName(), summary.totalReceivedBytes(), summary.totalSentBytes()});
        }
        status.setText(history.network().size() + " readings, " + history.alerts().size()
                + " alerts, " + history.summaries().size() + " daily summaries (up to 500 rows per tab).");
        refreshButton.setEnabled(true);
    }

    private DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
    }

    private JScrollPane table(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setBackground(DashboardStyle.SURFACE);
        table.setForeground(DashboardStyle.TEXT);
        table.setGridColor(DashboardStyle.BORDER);
        table.setRowHeight(28);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setBackground(DashboardStyle.BORDER);
        table.getTableHeader().setForeground(DashboardStyle.TEXT);
        return new JScrollPane(table);
    }
}
