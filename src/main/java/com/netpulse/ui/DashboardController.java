package com.netpulse.ui;

import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TransferResult;
import com.netpulse.model.AlertSettings;
import com.netpulse.model.TrafficAlert;
import com.netpulse.monitoring.NetworkAdapterMonitor;
import com.netpulse.monitoring.TrafficAnalyzer;
import com.netpulse.network.BandwidthClient;

import javax.swing.*;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;

/** Coordinates existing services; every Swing update runs on the EDT. */
final class DashboardController {
    private final DashboardPanel view;
    private final BandwidthClient client;
    private final TrafficAnalyzer analyzer = new TrafficAnalyzer();
    private AlertSettings appliedAlertSettings = AlertSettings.defaults();
    private volatile boolean alertMonitoringActive;
    private boolean alertSettingsBusy;
    // Virtual threads make the existing Socket I/O interruptible during window shutdown.
    private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
    private final Object monitorLock = new Object();
    private final Timer displayTimer;
    private final ScheduledExecutorService graphCollector = Executors.newSingleThreadScheduledExecutor(
            task -> new Thread(task, "netpulse-graph-collector"));
    // These two fields belong only to the collector thread.
    private NetworkAdapterMonitor graphMonitor;
    private NetworkStatisticsSnapshot lastGraphSnapshot;
    private volatile NetworkAdapterMonitor monitor;
    private volatile boolean closing;
    private boolean adaptersBusy;
    private boolean monitoringBusy;
    private boolean testBusy;

    DashboardController(DashboardPanel view) {
        this(view, new BandwidthClient());
    }

    DashboardController(DashboardPanel view, BandwidthClient client) {
        this.view = view;
        this.client = client;
        view.refreshAdaptersButton.addActionListener(event -> refreshAdapters());
        view.adapterSelector.addActionListener(event -> view.showSelection());
        view.startMonitoringButton.addActionListener(event -> startMonitoring());
        view.stopMonitoringButton.addActionListener(event -> stopMonitoring());
        view.refreshButton.addActionListener(event -> refreshSnapshot());
        view.startTestButton.addActionListener(event -> startTest());
        view.alerts.applyButton.addActionListener(event -> applyAlertSettings());
        view.alerts.enabled.addActionListener(event -> applyAlertSettings());
        view.alerts.clearButton.addActionListener(event -> clearAlertHistory());
        displayTimer = new Timer(500, event -> {
            refreshSnapshot();
            view.repaintGraphs();
        });
        displayTimer.start();
        graphCollector.scheduleWithFixedDelay(this::collectGraphSample, 0, 200, TimeUnit.MILLISECONDS);
        refreshAdapters();
    }

    private void refreshAdapters() {
        if (closing || adaptersBusy || monitor != null || monitoringBusy) {
            return;
        }
        NetworkAdapter selected = (NetworkAdapter) view.adapterSelector.getSelectedItem();
        Integer selectedIndex = selected == null ? null : selected.index();
        adaptersBusy = true;
        view.adapterDetails.setText("Refreshing network adapters...");
        updateControls();
        tasks.submit(() -> {
            try {
                List<NetworkAdapter> adapters = NetworkAdapterMonitor.listAdapters();
                onEdt(() -> {
                    view.setAdapters(adapters, selectedIndex);
                    adaptersBusy = false;
                    updateControls();
                });
            } catch (Exception exception) {
                onEdt(() -> {
                    adaptersBusy = false;
                    view.adapterDetails.setText("Could not list adapters: " + exception.getMessage());
                    updateControls();
                });
            }
        });
    }

    private void startMonitoring() {
        if (closing || monitoringBusy || monitor != null) {
            return;
        }
        NetworkAdapter selected = (NetworkAdapter) view.adapterSelector.getSelectedItem();
        if (selected == null) {
            return;
        }
        monitoringBusy = true;
        alertMonitoringActive = true;
        view.startGraphs();
        view.monitoringStatus.setText("Starting...");
        view.monitorMessage.setText("Starting a new adapter monitoring session...");
        updateControls();
        tasks.submit(() -> {
            NetworkAdapterMonitor created = new NetworkAdapterMonitor(selected);
            try {
                synchronized (monitorLock) {
                    if (closing) {
                        created.close();
                        return;
                    }
                    monitor = created;
                    created.start();
                }
                onEdt(() -> {
                    monitoringBusy = false;
                    view.monitoringStatus.setText("Running");
                    view.monitoringStatus.setForeground(DashboardStyle.GREEN);
                    refreshSnapshot();
                    updateControls();
                });
            } catch (Exception exception) {
                alertMonitoringActive = false;
                created.close();
                synchronized (monitorLock) {
                    if (monitor == created) {
                        monitor = null;
                    }
                }
                onEdt(() -> {
                    monitoringBusy = false;
                    view.stopGraphs();
                    view.monitoringStatus.setText("Stopped");
                    view.monitorMessage.setText("Could not start monitoring: " + exception.getMessage());
                    view.monitorMessage.setForeground(DashboardStyle.ERROR);
                    updateControls();
                });
            }
        });
    }

    private void stopMonitoring() {
        if (closing || monitoringBusy || monitor == null) {
            return;
        }
        monitoringBusy = true;
        alertMonitoringActive = false;
        view.stopGraphs();
        view.monitoringStatus.setText("Stopping...");
        updateControls();
        tasks.submit(() -> {
            NetworkAdapterMonitor stopped;
            synchronized (monitorLock) {
                stopped = monitor;
                monitor = null;
            }
            try {
                stopped.stop();
                onEdt(() -> view.showSnapshot(stopped.getSnapshot()));
            } catch (Exception exception) {
                onEdt(() -> view.monitorMessage.setText("Could not stop monitoring: " + exception.getMessage()));
            } finally {
                onEdt(() -> {
                    monitoringBusy = false;
                    view.monitoringStatus.setText("Stopped");
                    view.monitoringStatus.setForeground(DashboardStyle.MUTED);
                    updateControls();
                });
            }
        });
    }

    private void refreshSnapshot() {
        NetworkAdapterMonitor active = monitor;
        if (!closing && active != null) {
            NetworkStatisticsSnapshot snapshot = active.getSnapshot();
            view.showSnapshot(snapshot);
        }
    }

    private void collectGraphSample() {
        NetworkAdapterMonitor active = monitor;
        if (closing) {
            return;
        }
        if (active != graphMonitor) {
            graphMonitor = active;
            lastGraphSnapshot = null;
            analyzer.resetSession();
        }
        if (active != null) {
            NetworkStatisticsSnapshot snapshot = active.getSnapshot();
            if (snapshot != lastGraphSnapshot) {
                lastGraphSnapshot = snapshot;
                if (alertMonitoringActive && monitor == active) {
                    List<TrafficAlert> generated = analyzer.analyze(snapshot);
                    if (!generated.isEmpty()) {
                        List<TrafficAlert> history = analyzer.getHistory();
                        onEdt(() -> view.alerts.showHistory(history));
                    }
                }
                onEdt(() -> {
                    if (monitor == active && view.graphHistory.isRunning()) {
                        view.addGraphSnapshot(snapshot);
                    }
                });
            }
        }
    }

    private void startTest() {
        if (closing || testBusy) {
            return;
        }
        long bytes;
        try {
            bytes = parseTransferBytes(view.transferSize.getText());
        } catch (IllegalArgumentException exception) {
            view.clearTestResult();
            view.testMessage.setForeground(DashboardStyle.ERROR);
            view.testMessage.setText(exception.getMessage());
            return;
        }
        String mode = ((String) view.transferMode.getSelectedItem()).toUpperCase(Locale.ROOT);
        testBusy = true;
        view.clearTestResult();
        view.testMessage.setForeground(DashboardStyle.MUTED);
        view.testMessage.setText("Running " + mode.toLowerCase(Locale.ROOT) + " test on 127.0.0.1:5001...");
        updateControls();
        tasks.submit(() -> {
            try {
                TransferResult result = client.transfer(mode, bytes);
                onEdt(() -> view.showResult(result));
            } catch (ConnectException exception) {
                onEdt(() -> showTestError("Cannot connect to 127.0.0.1:5001. Start the NetPulse server and try again."));
            } catch (SocketTimeoutException exception) {
                onEdt(() -> showTestError("The TCP server timed out. Check that it is running, then try again."));
            } catch (Exception exception) {
                onEdt(() -> showTestError("Test failed: " + exception.getMessage()));
            } finally {
                onEdt(() -> {
                    testBusy = false;
                    updateControls();
                });
            }
        });
    }

    private void applyAlertSettings() {
        if (closing || alertSettingsBusy) {
            return;
        }
        AlertSettings settings;
        try {
            // Disabling must remain possible even when an unapplied text field is invalid.
            settings = appliedAlertSettings.enabled() && !view.alerts.enabled.isSelected()
                    ? new AlertSettings(false, appliedAlertSettings.downloadThresholdMbps(),
                            appliedAlertSettings.uploadThresholdMbps(), appliedAlertSettings.inactivitySeconds())
                    : view.alerts.readSettings();
        } catch (IllegalArgumentException exception) {
            view.alerts.enabled.setSelected(appliedAlertSettings.enabled());
            view.alerts.settingsMessage.setForeground(DashboardStyle.ERROR);
            view.alerts.settingsMessage.setText("Settings unchanged: " + exception.getMessage());
            return;
        }
        alertSettingsBusy = true;
        updateControls();
        graphCollector.execute(() -> {
            analyzer.configure(settings);
            onEdt(() -> {
                appliedAlertSettings = settings;
                alertSettingsBusy = false;
                view.alerts.showSettings(settings);
                updateControls();
            });
        });
    }

    private void clearAlertHistory() {
        if (!closing) {
            graphCollector.execute(() -> {
                analyzer.clearHistory();
                onEdt(() -> view.alerts.showHistory(List.of()));
            });
        }
    }

    private void showTestError(String message) {
        view.testMessage.setText(message);
        view.testMessage.setForeground(DashboardStyle.ERROR);
    }

    private void updateControls() {
        boolean canChoose = !closing && !adaptersBusy && !monitoringBusy && monitor == null;
        view.adapterSelector.setEnabled(canChoose);
        view.refreshAdaptersButton.setEnabled(canChoose);
        view.startMonitoringButton.setEnabled(canChoose && view.adapterSelector.getSelectedItem() != null);
        view.stopMonitoringButton.setEnabled(!closing && !monitoringBusy && monitor != null);
        view.refreshButton.setEnabled(!closing && !monitoringBusy && monitor != null);
        view.startTestButton.setEnabled(!closing && !testBusy);
        view.transferMode.setEnabled(!closing && !testBusy);
        view.transferSize.setEnabled(!closing && !testBusy);
        view.alerts.enabled.setEnabled(!closing && !alertSettingsBusy);
        view.alerts.applyButton.setEnabled(!closing && !alertSettingsBusy);
        view.alerts.downloadThreshold.setEnabled(!closing && !alertSettingsBusy);
        view.alerts.uploadThreshold.setEnabled(!closing && !alertSettingsBusy);
        view.alerts.inactivityDuration.setEnabled(!closing && !alertSettingsBusy);
        view.alerts.clearButton.setEnabled(!closing);
    }

    private void onEdt(Runnable update) {
        SwingUtilities.invokeLater(() -> {
            if (!closing) {
                update.run();
            }
        });
    }

    void shutdown(Runnable finished) {
        if (closing) {
            return;
        }
        closing = true;
        alertMonitoringActive = false;
        displayTimer.stop();
        view.stopGraphs();
        updateControls();
        view.monitoringStatus.setText("Closing...");
        new Thread(() -> {
            try {
                stopBackgroundWork();
            } finally {
                SwingUtilities.invokeLater(finished);
            }
        }, "netpulse-dashboard-shutdown").start();
    }

    // Also safe for the JVM shutdown hook: this method never touches Swing components.
    void stopBackgroundWork() {
        closing = true;
        alertMonitoringActive = false;
        graphCollector.shutdownNow();
        tasks.shutdownNow();
        NetworkAdapterMonitor active;
        synchronized (monitorLock) {
            active = monitor;
            monitor = null;
        }
        if (active != null) {
            active.stop();
        }
        try {
            if (!graphCollector.awaitTermination(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Graph collector did not terminate.");
            }
            if (!tasks.awaitTermination(8, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Dashboard background tasks did not terminate.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    static long parseTransferBytes(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Enter a transfer size in MB.");
        }
        try {
            BigDecimal megabytes = new BigDecimal(text.trim());
            if (megabytes.signum() <= 0 || megabytes.compareTo(new BigDecimal("1073.741824")) > 0) {
                throw new IllegalArgumentException("Enter a size greater than 0 and at most 1073.741824 MB (1 GiB).");
            }
            long bytes = megabytes.multiply(BigDecimal.valueOf(1_000_000)).longValueExact();
            if (bytes < 1) {
                throw new ArithmeticException();
            }
            return bytes;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Enter a valid MB size that represents a whole number of bytes.");
        }
    }
}
