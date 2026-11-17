package com.netpulse.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class DashboardFrame extends JFrame {
    final DashboardPanel dashboard;
    final DashboardController controller;
    private final Thread shutdownHook;

    public DashboardFrame() {
        super("NetPulse - Network Bandwidth Dashboard");
        var icon = DashboardFrame.class.getResource("netpulse.png");
        if (icon != null) setIconImage(new ImageIcon(icon).getImage());
        DashboardStyle.apply();
        dashboard = new DashboardPanel();
        controller = new DashboardController(dashboard);
        JScrollPane scroll = new JScrollPane(dashboard);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        setContentPane(scroll);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setMinimumSize(new Dimension(Math.min(860, screen.width), Math.min(600, screen.height)));
        setSize(Math.min(1120, screen.width), Math.min(820, screen.height));
        setLocationRelativeTo(null);
        shutdownHook = new Thread(controller::stopBackgroundWork, "netpulse-gui-shutdown-hook");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                controller.shutdown(() -> {
                    try {
                        Runtime.getRuntime().removeShutdownHook(shutdownHook);
                    } catch (IllegalStateException ignored) {
                        // JVM shutdown is already in progress.
                    }
                    dispose();
                });
            }
        });
    }
}
