package com.netpulse.ui;

import com.netpulse.model.BandwidthGraphHistory;
import com.netpulse.model.BandwidthGraphHistory.Sample;
import com.netpulse.model.BandwidthGraphHistory.Window;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.Locale;
import java.util.OptionalDouble;

/** Draws measured adapter speeds; unavailable samples break the line instead of becoming zero. */
public class BandwidthGraphPanel extends JPanel {
    private final BandwidthGraphHistory history;
    private final boolean download;
    private final Color lineColor;

    public BandwidthGraphPanel(BandwidthGraphHistory history, boolean download) {
        this.history = history;
        this.download = download;
        lineColor = download ? DashboardStyle.GREEN : DashboardStyle.BLUE;
        setBackground(DashboardStyle.SURFACE);
        setPreferredSize(new Dimension(480, 240));
        setMinimumSize(new Dimension(300, 210));
        setBorder(BorderFactory.createLineBorder(DashboardStyle.BORDER));
        setToolTipText("Measured adapter traffic in Mbps. Gaps indicate unavailable readings.");
        getAccessibleContext().setAccessibleName(download ? "Download speed graph" : "Upload speed graph");
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Window window = history.getWindow();
            g.setFont(new Font("Segoe UI", Font.BOLD, 14));
            g.setColor(DashboardStyle.TEXT);
            g.drawString(download ? "Download Speed (Mbps)" : "Upload Speed (Mbps)", 16, 25);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 11));
            g.setColor(DashboardStyle.MUTED);
            g.drawString("Latest 60 seconds · adapter traffic", 16, 43);
            String state = window.running() ? "LIVE" : window.started() ? "STOPPED" : "";
            g.setColor(window.running() ? lineColor : DashboardStyle.MUTED);
            g.drawString(state, Math.max(16, getWidth() - 18 - g.getFontMetrics().stringWidth(state)), 25);

            int left = 72;
            int top = 60;
            int width = getWidth() - left - 20;
            int height = getHeight() - top - 35;
            if (width <= 0 || height <= 0) {
                return;
            }
            double maximum = window.samples().stream().map(this::speed)
                    .filter(OptionalDouble::isPresent).mapToDouble(OptionalDouble::getAsDouble).max().orElse(0);
            double upper = axisMaximum(maximum);
            paintAxes(g, window, left, top, width, height, upper);

            boolean hasReading = window.samples().stream().anyMatch(sample -> speed(sample).isPresent());
            if (!hasReading) {
                String message = !window.started() ? "Start monitoring to view adapter traffic"
                        : window.samples().isEmpty() && window.running() ? "Waiting for adapter readings..."
                        : "No speed readings available";
                g.setColor(DashboardStyle.MUTED);
                int textWidth = g.getFontMetrics().stringWidth(message);
                g.drawString(message, left + Math.max(0, (width - textWidth) / 2), top + height / 2);
                return;
            }

            Shape oldClip = g.getClip();
            g.clipRect(left - 3, top - 3, width + 6, height + 6);
            g.setColor(lineColor);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D path = new Path2D.Double();
            boolean connected = false;
            for (Sample sample : window.samples()) {
                OptionalDouble speed = speed(sample);
                if (speed.isEmpty()) {
                    connected = false;
                    continue;
                }
                double fraction = 1.0 - (window.endNanos() - sample.elapsedNanos())
                        / (double) BandwidthGraphHistory.WINDOW_NANOS;
                double x = left + fraction * width;
                double y = top + height - speed.getAsDouble() / upper * height;
                if (connected) {
                    path.lineTo(x, y);
                } else {
                    path.moveTo(x, y);
                }
                connected = true;
                g.fillOval((int) Math.round(x) - 2, (int) Math.round(y) - 2, 4, 4);
            }
            g.draw(path);
            g.setClip(oldClip);
        } finally {
            g.dispose();
        }
    }

    private void paintAxes(Graphics2D g, Window window, int left, int top, int width, int height, double upper) {
        for (int index = 0; index <= 4; index++) {
            int y = top + height - index * height / 4;
            g.setColor(DashboardStyle.BORDER);
            g.drawLine(left, y, left + width, y);
            g.setColor(DashboardStyle.MUTED);
            String label = axisLabel(upper * index / 4);
            g.drawString(label, left - 8 - g.getFontMetrics().stringWidth(label), y + 4);
        }
        for (int index = 0; index <= 4; index++) {
            int x = left + index * width / 4;
            g.setColor(DashboardStyle.BORDER);
            g.drawLine(x, top, x, top + height);
            g.setColor(DashboardStyle.MUTED);
            String label = index == 4 ? (window.running() ? "Now" : "End")
                    : (60 - index * 15) + "s ago";
            int textWidth = g.getFontMetrics().stringWidth(label);
            g.drawString(label, Math.max(left, Math.min(x - textWidth / 2, left + width - textWidth)),
                    top + height + 21);
        }
    }

    private OptionalDouble speed(Sample sample) {
        return download ? sample.downloadMbps() : sample.uploadMbps();
    }

    private static double axisMaximum(double maximum) {
        if (maximum <= 0) {
            return 1.0; // A zero-speed line remains visible without a division by zero.
        }
        double wanted = Math.max(0.000001, maximum * 1.1);
        double magnitude = Math.pow(10, Math.floor(Math.log10(wanted)));
        double scaled = wanted / magnitude;
        double step = scaled <= 1 ? 1 : scaled <= 2 ? 2 : scaled <= 5 ? 5 : 10;
        return step * magnitude;
    }

    private static String axisLabel(double value) {
        if (value == 0) {
            return "0";
        }
        if (value < 0.0001 || value >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1e", value);
        }
        return String.format(Locale.ROOT, value < 1 ? "%.6f" : "%.2f", value)
                .replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
