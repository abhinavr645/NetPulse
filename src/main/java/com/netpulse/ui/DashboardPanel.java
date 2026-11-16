package com.netpulse.ui;

import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TransferResult;
import com.netpulse.model.BandwidthGraphHistory;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;

import static com.netpulse.ui.DashboardStyle.*;

/** Swing components and rendering only. Background work belongs to the controller. */
public class DashboardPanel extends JPanel implements Scrollable {
    final AlertPanel alerts = new AlertPanel();
    final BandwidthGraphHistory graphHistory = new BandwidthGraphHistory();
    final BandwidthGraphPanel downloadGraph = new BandwidthGraphPanel(graphHistory, true);
    final BandwidthGraphPanel uploadGraph = new BandwidthGraphPanel(graphHistory, false);
    final JComboBox<NetworkAdapter> adapterSelector = new JComboBox<>();
    final JButton refreshAdaptersButton = button("Refresh adapters", false);
    final JButton startMonitoringButton = button("Start Monitoring", true);
    final JButton stopMonitoringButton = button("Stop Monitoring", false);
    final JButton refreshButton = button("Refresh", false);
    final JComboBox<String> transferMode = new JComboBox<>(new String[]{"Upload", "Download"});
    final JTextField transferSize = new JTextField("10", 8);
    final JButton startTestButton = button("Start Test", true);

    final JLabel monitoringStatus = label("Stopped", 13, MUTED);
    final JLabel adapterDetails = label("Loading network adapters...", 13, MUTED);
    final JLabel downloadValue = label("--", 32, GREEN);
    final JLabel uploadValue = label("--", 32, BLUE);
    final JLabel receivedValue = label("--", 30, TEXT);
    final JLabel sentValue = label("--", 30, TEXT);
    final JLabel downloadDetail = label("Waiting for a reading", 12, MUTED);
    final JLabel uploadDetail = label("Waiting for a reading", 12, MUTED);
    final JLabel receivedDetail = label("This monitoring session", 12, MUTED);
    final JLabel sentDetail = label("This monitoring session", 12, MUTED);
    final JTextArea monitorMessage = messageArea("Choose an adapter, then start monitoring.");
    final JLabel counterDetails = label("Adapter counters are separate from TCP test payload bytes.", 12, MUTED);
    final JLabel testThroughput = label("-- Mbps", 24, TEXT);
    final JLabel testElapsed = label("-- seconds", 24, TEXT);
    final JLabel testBytes = label("-- bytes", 24, TEXT);
    final JTextArea testMessage = messageArea("Start the local TCP server before running a test.");

    // These retain exactly the immutable models rendered on screen.
    NetworkStatisticsSnapshot displayedSnapshot;
    TransferResult displayedResult;

    public DashboardPanel() {
        setLayout(new BorderLayout(0, 18));
        setBackground(BACKGROUND);
        setBorder(padding(26));

        JPanel header = new JPanel(new BorderLayout());
        JPanel titles = vertical(BACKGROUND);
        JLabel title = label("NetPulse", 34, TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        titles.add(title);
        titles.add(label("Network Bandwidth Monitoring and Analysis System", 14, MUTED));
        header.add(titles, BorderLayout.CENTER);
        monitoringStatus.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), padding(12)));
        header.add(monitoringStatus, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JPanel content = vertical(BACKGROUND);
        content.add(adapterSection());
        content.add(Box.createVerticalStrut(18));
        JPanel cards = new JPanel(new GridLayout(1, 4, 14, 0));
        cards.add(card("DOWNLOAD SPEED", "Mbps · adapter receive", downloadValue, downloadDetail, GREEN));
        cards.add(card("UPLOAD SPEED", "Mbps · adapter send", uploadValue, uploadDetail, BLUE));
        cards.add(card("TOTAL RECEIVED", "Observed adapter traffic", receivedValue, receivedDetail, BORDER));
        cards.add(card("TOTAL SENT", "Observed adapter traffic", sentValue, sentDetail, BORDER));
        content.add(cards);
        content.add(Box.createVerticalStrut(18));

        JPanel controls = new JPanel(new BorderLayout(0, 10));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        buttons.add(startMonitoringButton);
        buttons.add(stopMonitoringButton);
        buttons.add(refreshButton);
        refreshButton.setToolTipText("Display the latest available adapter reading");
        controls.add(buttons, BorderLayout.NORTH);
        JPanel notes = vertical(BACKGROUND);
        notes.add(monitorMessage);
        notes.add(Box.createVerticalStrut(5));
        notes.add(counterDetails);
        controls.add(notes, BorderLayout.CENTER);
        content.add(controls);
        content.add(Box.createVerticalStrut(18));
        JPanel graphs = new JPanel(new GridLayout(1, 2, 14, 0));
        graphs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));
        graphs.add(downloadGraph);
        graphs.add(uploadGraph);
        content.add(graphs);
        content.add(Box.createVerticalStrut(22));
        content.add(tcpSection());
        content.add(Box.createVerticalStrut(18));
        content.add(alerts);
        add(content, BorderLayout.CENTER);
        add(label("LOCALHOST TCP TESTS  /  WINDOWS ADAPTER MONITORING", 11, MUTED), BorderLayout.SOUTH);

        adapterSelector.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        adapterSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof NetworkAdapter adapter) {
                    setText(adapter.index() + " · " + adapter.displayName()
                            + " (" + (adapter.up() ? "Up" : "Down") + ")");
                }
                setBackground(selected ? BORDER : SURFACE);
                setForeground(TEXT);
                setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
                return this;
            }
        });
        transferMode.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        transferSize.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        transferSize.setBackground(BACKGROUND);
        transferSize.setForeground(TEXT);
        transferSize.setCaretColor(TEXT);
        transferSize.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(9, 10, 9, 10)));
        transferSize.setToolTipText("Decimal MB: 1 MB = 1,000,000 bytes");
        adapterSelector.getAccessibleContext().setAccessibleName("Network adapter");
        transferMode.getAccessibleContext().setAccessibleName("Transfer mode");
        transferSize.getAccessibleContext().setAccessibleName("Transfer size in MB");
    }

    private JPanel adapterSection() {
        JPanel section = section();
        section.add(label("NETWORK ADAPTER", 12, MUTED), BorderLayout.NORTH);
        JPanel selection = new JPanel(new BorderLayout(12, 0));
        selection.setOpaque(false);
        selection.add(adapterSelector, BorderLayout.CENTER);
        selection.add(refreshAdaptersButton, BorderLayout.EAST);
        section.add(selection, BorderLayout.CENTER);
        section.add(adapterDetails, BorderLayout.SOUTH);
        return section;
    }

    private JPanel tcpSection() {
        JPanel section = section();
        JPanel heading = vertical(SURFACE);
        heading.add(label("TCP bandwidth test", 20, TEXT));
        heading.add(label("Application payload · 127.0.0.1:5001 · separate from adapter traffic", 12, MUTED));
        section.add(heading, BorderLayout.NORTH);
        JPanel body = vertical(SURFACE);
        JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        form.setOpaque(false);
        form.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(label("Mode", 13, MUTED));
        form.add(transferMode);
        form.add(label("Size", 13, MUTED));
        form.add(transferSize);
        form.add(label("MB", 13, MUTED));
        form.add(startTestButton);
        body.add(form);
        body.add(Box.createVerticalStrut(12));
        JPanel results = new JPanel(new GridLayout(1, 3, 16, 0));
        results.setOpaque(false);
        results.setAlignmentX(Component.LEFT_ALIGNMENT);
        results.add(resultCell("THROUGHPUT", testThroughput));
        results.add(resultCell("ELAPSED TIME", testElapsed));
        results.add(resultCell("PAYLOAD TRANSFERRED", testBytes));
        body.add(results);
        body.add(Box.createVerticalStrut(12));
        body.add(testMessage);
        section.add(body, BorderLayout.CENTER);
        return section;
    }

    private JPanel card(String heading, String unit, JLabel value, JLabel detail, Color accent) {
        JPanel card = vertical(SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(3, 0, 0, 0, accent), padding(16)));
        card.add(label(heading, 11, MUTED));
        card.add(Box.createVerticalStrut(12));
        card.add(value);
        card.add(label(unit, 11, MUTED));
        card.add(Box.createVerticalStrut(10));
        card.add(detail);
        return card;
    }

    private JPanel resultCell(String heading, JLabel value) {
        JPanel panel = vertical(SURFACE);
        panel.add(label(heading, 11, MUTED));
        panel.add(Box.createVerticalStrut(6));
        panel.add(value);
        return panel;
    }

    private static JPanel vertical(Color background) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(background);
        return panel;
    }

    private static JTextArea messageArea(String message) {
        JTextArea area = new JTextArea(message, 2, 30);
        area.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        area.setForeground(MUTED);
        area.setOpaque(false);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFocusable(false);
        area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }

    void setAdapters(List<NetworkAdapter> adapters, Integer previousIndex) {
        adapterSelector.removeAllItems();
        NetworkAdapter preferred = null;
        for (NetworkAdapter adapter : adapters) {
            adapterSelector.addItem(adapter);
            if (previousIndex != null && adapter.index() == previousIndex) {
                preferred = adapter;
            }
        }
        if (preferred == null) {
            preferred = adapters.stream().filter(adapter -> adapter.up() && !adapter.loopback())
                    .findFirst().orElse(adapters.isEmpty() ? null : adapters.getFirst());
        }
        adapterSelector.setSelectedItem(preferred);
        showSelection();
    }

    void showSelection() {
        graphHistory.clear();
        repaintGraphs();
        displayedSnapshot = null;
        downloadValue.setText("--");
        uploadValue.setText("--");
        receivedValue.setText("--");
        sentValue.setText("--");
        downloadDetail.setText("Not monitoring");
        uploadDetail.setText("Not monitoring");
        receivedDetail.setText("This monitoring session");
        sentDetail.setText("This monitoring session");
        counterDetails.setText("Adapter counters are separate from TCP test payload bytes.");
        NetworkAdapter selected = (NetworkAdapter) adapterSelector.getSelectedItem();
        adapterDetails.setText(selected == null ? "No network adapters found. Refresh to try again."
                : selected.displayName() + " · index " + selected.index() + " · "
                        + (selected.up() ? "Up" : "Down") + (selected.loopback() ? " · Loopback" : ""));
    }

    void showSnapshot(NetworkStatisticsSnapshot snapshot) {
        displayedSnapshot = snapshot;
        adapterDetails.setText(snapshot.adapterName() + " · index " + snapshot.adapter().index()
                + " · " + snapshot.adapterStatus());
        downloadValue.setText(rate(snapshot.receiveMbps()));
        uploadValue.setText(rate(snapshot.sendMbps()));
        downloadDetail.setText(snapshot.receiveMBps().isPresent()
                ? String.format(Locale.ROOT, "%.6f MB/s", snapshot.receiveMBps().getAsDouble()) : "Reading unavailable");
        uploadDetail.setText(snapshot.sendMBps().isPresent()
                ? String.format(Locale.ROOT, "%.6f MB/s", snapshot.sendMBps().getAsDouble()) : "Reading unavailable");
        receivedValue.setText(dataSize(snapshot.totalReceivedBytes()));
        sentValue.setText(dataSize(snapshot.totalSentBytes()));
        receivedDetail.setText(String.format(Locale.ROOT, "%,d bytes · session", snapshot.totalReceivedBytes()));
        sentDetail.setText(String.format(Locale.ROOT, "%,d bytes · session", snapshot.totalSentBytes()));
        counterDetails.setText("Windows counters: received "
                + (snapshot.receivedBytes().isPresent() ? snapshot.receivedBytes().getAsLong() + " bytes" : "N/A")
                + "  |  sent " + (snapshot.sentBytes().isPresent() ? snapshot.sentBytes().getAsLong() + " bytes" : "N/A"));
        monitorMessage.setText(snapshot.message());
        monitorMessage.setForeground(snapshot.state() == NetworkStatisticsSnapshot.State.UNAVAILABLE
                ? DashboardStyle.ERROR : MUTED);
    }

    void showResult(TransferResult result) {
        displayedResult = result;
        testThroughput.setText(String.format(Locale.ROOT, "%.3f Mbps", result.getThroughputMbps()));
        testElapsed.setText(String.format(Locale.ROOT, "%.6f seconds", result.getElapsedSeconds()));
        testBytes.setText(String.format(Locale.ROOT, "%,d bytes", result.getTotalBytes()));
        testMessage.setForeground(GREEN);
        testMessage.setText(String.format(Locale.ROOT, "%s complete · %.3f MB/s · payload byte count verified.",
                result.getMode(), result.getMegabytesPerSecond()));
    }

    void clearTestResult() {
        displayedResult = null;
        testThroughput.setText("-- Mbps");
        testElapsed.setText("-- seconds");
        testBytes.setText("-- bytes");
    }

    void startGraphs() {
        graphHistory.start();
        repaintGraphs();
    }

    void stopGraphs() {
        graphHistory.stop();
        repaintGraphs();
    }

    void addGraphSnapshot(NetworkStatisticsSnapshot snapshot) {
        if (graphHistory.addSnapshot(snapshot)) {
            repaintGraphs();
        }
    }

    void repaintGraphs() {
        downloadGraph.repaint();
        uploadGraph.repaint();
    }

    static String rate(OptionalDouble value) {
        return value.isPresent() ? String.format(Locale.ROOT, "%.6f", value.getAsDouble()) : "--";
    }

    static String dataSize(long bytes) {
        return String.format(Locale.ROOT, "%.3f MB", bytes / 1_000_000.0);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 18;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(18, (orientation == SwingConstants.VERTICAL ? visible.height : visible.width) - 18);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
