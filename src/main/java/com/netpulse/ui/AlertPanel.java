package com.netpulse.ui;

import com.netpulse.model.AlertSettings;
import com.netpulse.model.TrafficAlert;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Controls and a bounded-history view. Detection logic stays in TrafficAnalyzer. */
final class AlertPanel extends JPanel {
    final JCheckBox enabled = new JCheckBox("Enable alerts");
    final JTextField downloadThreshold = new JTextField("100", 6);
    final JTextField uploadThreshold = new JTextField("50", 6);
    final JTextField inactivityDuration = new JTextField("30", 5);
    final JButton applyButton = DashboardStyle.button("Apply settings", false);
    final JButton clearButton = DashboardStyle.button("Clear history", false);
    final JLabel settingsMessage = DashboardStyle.label("Alerts disabled", 12, DashboardStyle.MUTED);
    final JLabel historyCount = DashboardStyle.label("0 / 100 recent alerts", 12, DashboardStyle.MUTED);
    final DefaultTableModel tableModel = new DefaultTableModel(
            new Object[]{"Time (local)", "Severity", "Type", "Description"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    final JTable historyTable = new JTable(tableModel) {
        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAtPoint(event.getPoint());
            return row < 0 ? null : displayedAlerts.get(convertRowIndexToModel(row)).description();
        }
    };
    List<TrafficAlert> displayedAlerts = List.of();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    AlertPanel() {
        super(new BorderLayout(0, 12));
        setBackground(DashboardStyle.SURFACE);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(DashboardStyle.BORDER),
                DashboardStyle.padding(18)));
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.add(DashboardStyle.label("Traffic analysis & alerts", 20, DashboardStyle.TEXT), BorderLayout.WEST);
        heading.add(historyCount, BorderLayout.EAST);
        add(heading, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 5));
        controls.setOpaque(false);
        controls.setAlignmentX(LEFT_ALIGNMENT);
        enabled.setBackground(DashboardStyle.SURFACE);
        enabled.setForeground(DashboardStyle.TEXT);
        enabled.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        controls.add(enabled);
        controls.add(DashboardStyle.label("Download Mbps", 12, DashboardStyle.MUTED));
        controls.add(downloadThreshold);
        controls.add(DashboardStyle.label("Upload Mbps", 12, DashboardStyle.MUTED));
        controls.add(uploadThreshold);
        controls.add(DashboardStyle.label("Idle seconds", 12, DashboardStyle.MUTED));
        controls.add(inactivityDuration);
        for (JTextField field : new JTextField[]{downloadThreshold, uploadThreshold, inactivityDuration}) {
            field.setFont(new Font("Segoe UI", Font.PLAIN, 13));
            field.setBackground(DashboardStyle.BACKGROUND);
            field.setForeground(DashboardStyle.TEXT);
            field.setCaretColor(DashboardStyle.TEXT);
            field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(DashboardStyle.BORDER),
                    BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        }
        downloadThreshold.getAccessibleContext().setAccessibleName("Download alert threshold in Mbps");
        uploadThreshold.getAccessibleContext().setAccessibleName("Upload alert threshold in Mbps");
        inactivityDuration.getAccessibleContext().setAccessibleName("Inactivity duration in seconds");
        body.add(controls);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        actions.setOpaque(false);
        actions.setAlignmentX(LEFT_ALIGNMENT);
        actions.add(applyButton);
        actions.add(clearButton);
        body.add(actions);
        settingsMessage.setAlignmentX(LEFT_ALIGNMENT);
        body.add(settingsMessage);
        body.add(Box.createVerticalStrut(12));

        historyTable.setBackground(DashboardStyle.SURFACE);
        historyTable.setForeground(DashboardStyle.TEXT);
        historyTable.setSelectionBackground(DashboardStyle.BORDER);
        historyTable.setSelectionForeground(DashboardStyle.TEXT);
        historyTable.setGridColor(DashboardStyle.BORDER);
        historyTable.setShowVerticalLines(false);
        historyTable.setRowHeight(30);
        historyTable.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        historyTable.setFillsViewportHeight(true);
        historyTable.getTableHeader().setReorderingAllowed(false);
        historyTable.getTableHeader().setBackground(DashboardStyle.BORDER);
        historyTable.getTableHeader().setForeground(DashboardStyle.TEXT);
        historyTable.getTableHeader().setFont(new Font("Segoe UI", Font.BOLD, 12));
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        historyTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        historyTable.getColumnModel().getColumn(2).setPreferredWidth(135);
        historyTable.getColumnModel().getColumn(3).setPreferredWidth(550);
        historyTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                setBackground(selected ? DashboardStyle.BORDER : DashboardStyle.SURFACE);
                setForeground(severityColor((TrafficAlert.Severity) value));
                setFont(getFont().deriveFont(Font.BOLD));
                return this;
            }
        });
        JScrollPane scroll = new JScrollPane(historyTable);
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        scroll.setPreferredSize(new Dimension(800, 175));
        scroll.setBorder(BorderFactory.createLineBorder(DashboardStyle.BORDER));
        body.add(scroll);
        body.add(Box.createVerticalStrut(8));
        body.add(DashboardStyle.label("INFO: idle traffic  ·  WARNING: above threshold  ·  CRITICAL: at least 2× threshold",
                11, DashboardStyle.MUTED));
        add(body, BorderLayout.CENTER);
        showSettings(AlertSettings.defaults());
    }

    AlertSettings readSettings() {
        try {
            return new AlertSettings(enabled.isSelected(), Double.parseDouble(downloadThreshold.getText().trim()),
                    Double.parseDouble(uploadThreshold.getText().trim()),
                    Long.parseLong(inactivityDuration.getText().trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Enter positive Mbps thresholds and a whole-number idle duration (1–86400 seconds).");
        }
    }

    void showSettings(AlertSettings settings) {
        enabled.setSelected(settings.enabled());
        settingsMessage.setForeground(DashboardStyle.MUTED);
        settingsMessage.setText(String.format(Locale.ROOT,
                "Alerts %s · download > %.6g Mbps · upload > %.6g Mbps · idle %d seconds",
                settings.enabled() ? "enabled" : "disabled", settings.downloadThresholdMbps(),
                settings.uploadThresholdMbps(), settings.inactivitySeconds()));
    }

    void loadSettings(AlertSettings settings) {
        downloadThreshold.setText(Double.toString(settings.downloadThresholdMbps()));
        uploadThreshold.setText(Double.toString(settings.uploadThresholdMbps()));
        inactivityDuration.setText(Long.toString(settings.inactivitySeconds()));
        showSettings(settings);
    }

    void showHistory(List<TrafficAlert> alerts) {
        // Defensive display bound as well as the analyzer's authoritative 100-alert bound.
        displayedAlerts = List.copyOf(alerts.subList(0, Math.min(100, alerts.size())));
        tableModel.setRowCount(0);
        for (TrafficAlert alert : displayedAlerts) {
            tableModel.addRow(new Object[]{TIMESTAMP.format(alert.timestamp()), alert.severity(),
                    typeLabel(alert.type()), alert.description()});
        }
        historyCount.setText(displayedAlerts.size() + " / 100 recent alerts");
    }

    static Color severityColor(TrafficAlert.Severity severity) {
        return switch (severity) {
            case INFO -> DashboardStyle.BLUE;
            case WARNING -> DashboardStyle.WARNING;
            case CRITICAL -> DashboardStyle.ERROR;
        };
    }

    private String typeLabel(TrafficAlert.Type type) {
        return switch (type) {
            case DOWNLOAD_THRESHOLD -> "Download threshold";
            case UPLOAD_THRESHOLD -> "Upload threshold";
            case INACTIVITY -> "Inactivity";
        };
    }
}
