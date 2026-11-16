package com.netpulse.monitoring;

import com.netpulse.model.AlertSettings;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TrafficAlert;
import com.netpulse.model.TrafficAlert.Severity;
import com.netpulse.model.TrafficAlert.Type;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Small, thread-safe analysis of real adapter snapshots, independent of Swing. */
public class TrafficAnalyzer {
    public static final int MAX_ALERTS = 100;

    private final ArrayDeque<TrafficAlert> history = new ArrayDeque<>();
    private final Clock wallClock;
    private AlertSettings settings = AlertSettings.defaults();
    private NetworkStatisticsSnapshot previousSnapshot;
    private int adapterIndex = -1;
    private boolean downloadActive;
    private boolean uploadActive;
    private boolean inactivityActive;
    private long idleNanos;

    public TrafficAnalyzer() {
        this(Clock.systemUTC());
    }

    TrafficAnalyzer(Clock wallClock) {
        this.wallClock = Objects.requireNonNull(wallClock);
    }

    public synchronized void configure(AlertSettings settings) {
        Objects.requireNonNull(settings);
        if (!this.settings.equals(settings)) {
            this.settings = settings;
            resetConditions();
        }
    }

    public synchronized AlertSettings getSettings() {
        return settings;
    }

    public synchronized void resetSession() {
        resetConditions();
        previousSnapshot = null;
        adapterIndex = -1;
    }

    public synchronized List<TrafficAlert> analyze(NetworkStatisticsSnapshot snapshot) {
        Objects.requireNonNull(snapshot);
        if (snapshot == previousSnapshot) {
            return List.of();
        }
        previousSnapshot = snapshot;
        if (snapshot.adapter().index() != adapterIndex) {
            resetConditions();
            adapterIndex = snapshot.adapter().index();
        }
        if (!settings.enabled()) {
            resetConditions();
            return List.of();
        }
        if (snapshot.state() != NetworkStatisticsSnapshot.State.VALID
                || !snapshot.adapterStatus().equalsIgnoreCase("Up") || snapshot.intervalNanos() <= 0
                || snapshot.receiveBytesPerSecond().isEmpty() || snapshot.sendBytesPerSecond().isEmpty()) {
            resetInactivity();
            // Unknown traffic does not prove an active threshold has cleared.
            return List.of();
        }

        List<TrafficAlert> alerts = new ArrayList<>();
        double download = snapshot.receiveMbps().orElseThrow();
        double upload = snapshot.sendMbps().orElseThrow();
        String adapter = snapshot.adapterName() + " [" + snapshot.adapter().index() + "]";
        boolean aboveDownload = download > settings.downloadThresholdMbps();
        boolean aboveUpload = upload > settings.uploadThresholdMbps();
        if (aboveDownload && !downloadActive) {
            alerts.add(thresholdAlert(adapter, Type.DOWNLOAD_THRESHOLD, download, settings.downloadThresholdMbps()));
        }
        if (aboveUpload && !uploadActive) {
            alerts.add(thresholdAlert(adapter, Type.UPLOAD_THRESHOLD, upload, settings.uploadThresholdMbps()));
        }
        downloadActive = aboveDownload;
        uploadActive = aboveUpload;

        boolean idle = snapshot.receiveBytesPerSecond().getAsDouble() == 0
                && snapshot.sendBytesPerSecond().getAsDouble() == 0;
        if (idle) {
            // Intervals come from the monitor's System.nanoTime() measurements, not UI timer ticks.
            long limit = TimeUnit.SECONDS.toNanos(settings.inactivitySeconds());
            idleNanos = Math.min(limit, idleNanos + Math.min(limit, snapshot.intervalNanos()));
            if (idleNanos >= limit && !inactivityActive) {
                alerts.add(new TrafficAlert(wallClock.instant(), Type.INACTIVITY, Severity.INFO,
                        adapter + ": both directions observed at zero for at least " + settings.inactivitySeconds()
                                + " seconds of valid samples. Idle traffic does not imply Internet disconnection."));
                inactivityActive = true;
            }
        } else {
            resetInactivity();
        }
        for (TrafficAlert alert : alerts) {
            history.addFirst(alert);
            while (history.size() > MAX_ALERTS) {
                history.removeLast();
            }
        }
        return List.copyOf(alerts);
    }

    public synchronized List<TrafficAlert> getHistory() {
        return List.copyOf(history);
    }

    public synchronized void clearHistory() {
        history.clear(); // Clearing the display must not rearm an already active condition.
    }

    private TrafficAlert thresholdAlert(String adapter, Type type, double speed, double threshold) {
        Severity severity = speed / threshold >= 2 ? Severity.CRITICAL : Severity.WARNING;
        String direction = type == Type.DOWNLOAD_THRESHOLD ? "download" : "upload";
        return new TrafficAlert(wallClock.instant(), type, severity,
                String.format(Locale.ROOT, "%s: %s %.6g Mbps exceeds the %.6g Mbps threshold.",
                        adapter, direction, speed, threshold));
    }

    private void resetConditions() {
        downloadActive = false;
        uploadActive = false;
        resetInactivity();
    }

    private void resetInactivity() {
        idleNanos = 0;
        inactivityActive = false;
    }
}
