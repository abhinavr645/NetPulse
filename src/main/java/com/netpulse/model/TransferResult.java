package com.netpulse.model;

public final class TransferResult {
    private final String mode;
    private final long totalBytes;
    private final long elapsedNanos;
    private final double throughputMbps;
    private final double megabytesPerSecond;

    public TransferResult(String mode, long totalBytes, long elapsedNanos) {
        if (!"UPLOAD".equals(mode) && !"DOWNLOAD".equals(mode)) {
            throw new IllegalArgumentException("Transfer mode must be UPLOAD or DOWNLOAD.");
        }
        if (totalBytes <= 0 || elapsedNanos <= 0) {
            throw new IllegalArgumentException("Byte count and elapsed time must be positive.");
        }
        this.mode = mode;
        this.totalBytes = totalBytes;
        this.elapsedNanos = elapsedNanos;
        double seconds = elapsedNanos / 1_000_000_000.0;
        this.megabytesPerSecond = totalBytes / seconds / 1_000_000.0;
        this.throughputMbps = megabytesPerSecond * 8;
    }

    public String getMode() {
        return mode;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getElapsedNanos() {
        return elapsedNanos;
    }

    public double getElapsedSeconds() {
        return elapsedNanos / 1_000_000_000.0;
    }

    public double getThroughputMbps() {
        return throughputMbps;
    }

    public double getMegabytesPerSecond() {
        return megabytesPerSecond;
    }
}
