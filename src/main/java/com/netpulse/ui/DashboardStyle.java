package com.netpulse.ui;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;

/** Shared colors and small component factories; no external look-and-feel library. */
final class DashboardStyle {
    static final Color BACKGROUND = new Color(13, 18, 26);
    static final Color SURFACE = new Color(23, 31, 43);
    static final Color BORDER = new Color(45, 58, 75);
    static final Color TEXT = new Color(236, 242, 250);
    static final Color MUTED = new Color(158, 175, 196);
    static final Color GREEN = new Color(65, 218, 174);
    static final Color BLUE = new Color(103, 170, 255);
    static final Color ERROR = new Color(255, 148, 148);

    private DashboardStyle() { }

    static void apply() {
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Button.disabledText", MUTED);
        UIManager.put("ComboBox.background", SURFACE);
        UIManager.put("ComboBox.foreground", TEXT);
        UIManager.put("ComboBox.selectionBackground", BORDER);
        UIManager.put("ComboBox.selectionForeground", TEXT);
        UIManager.put("ComboBox.disabledForeground", MUTED);
        UIManager.put("ComboBox.disabledBackground", SURFACE);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("ToolTip.background", SURFACE);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ScrollBar.background", BACKGROUND);
        UIManager.put("ScrollBar.track", BACKGROUND);
        UIManager.put("ScrollBar.thumb", BORDER);
        UIManager.put("ScrollBar.thumbHighlight", BORDER);
        UIManager.put("ScrollBar.thumbShadow", BORDER);
        UIManager.put("ScrollBar.thumbDarkShadow", BORDER);
    }

    static JLabel label(String text, int size, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(new Font("Segoe UI", Font.PLAIN, size));
        label.setForeground(color);
        return label;
    }

    static JButton button(String text, boolean primary) {
        JButton button = new JButton(text);
        button.setFont(new Font("Segoe UI", Font.BOLD, 13));
        styleButton(button, primary);
        button.addPropertyChangeListener("enabled", event -> styleButton(button, primary));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    private static void styleButton(JButton button, boolean primary) {
        boolean active = button.isEnabled();
        button.setBackground(active && primary ? GREEN : SURFACE);
        button.setForeground(active ? (primary ? BACKGROUND : TEXT) : MUTED);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(active && primary ? GREEN : BORDER),
                BorderFactory.createEmptyBorder(10, 16, 10, 16)));
    }

    static Border padding(int pixels) {
        return BorderFactory.createEmptyBorder(pixels, pixels, pixels, pixels);
    }

    static JPanel section() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBackground(SURFACE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), padding(18)));
        return panel;
    }
}
