package com.netpulse.model;

public record AlertSettings(boolean enabled, double downloadThresholdMbps,
                            double uploadThresholdMbps, long inactivitySeconds) {
    public AlertSettings {
        if (!Double.isFinite(downloadThresholdMbps) || downloadThresholdMbps <= 0
                || !Double.isFinite(uploadThresholdMbps) || uploadThresholdMbps <= 0) {
            throw new IllegalArgumentException("Download and upload thresholds must be positive, finite Mbps values.");
        }
        if (inactivitySeconds < 1 || inactivitySeconds > 86_400) {
            throw new IllegalArgumentException("Inactivity duration must be a whole number from 1 to 86400 seconds.");
        }
    }

    public static AlertSettings defaults() {
        return new AlertSettings(false, 100, 50, 30);
    }
}
