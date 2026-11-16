package com.netpulse;

import com.netpulse.model.TransferResult;
import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.monitoring.NetworkAdapterMonitor;
import com.netpulse.network.BandwidthClient;
import com.netpulse.network.BandwidthServer;

import java.io.IOException;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("NetPulse - Network Bandwidth Monitoring and Analysis System");
        System.out.println("Application Started Successfully");

        if (args.length == 0) {
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("server")) {
            runServer();
        } else if (args.length == 1 && args[0].equalsIgnoreCase("adapters")) {
            for (NetworkAdapter adapter : NetworkAdapterMonitor.listAdapters()) {
                System.out.printf("Index: %d | Java name: %s | %s | %s | Loopback: %s%n",
                        adapter.index(), adapter.name(), adapter.displayName(),
                        adapter.up() ? "Up" : "Down", adapter.loopback());
            }
        } else if ((args.length == 2 || args.length == 3) && args[0].equalsIgnoreCase("monitor")) {
            runAdapterMonitor(args);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("client")) {
            long size;
            try {
                size = Long.parseLong(args[2]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Transfer size must be a whole number of bytes.");
            }
            try {
                TransferResult result = new BandwidthClient().transfer(args[1], size);
                System.out.printf(Locale.ROOT,
                        "Mode: %s%nTotal bytes transferred: %d%nElapsed time: %.6f seconds%n"
                                + "Throughput (average): %.3f Mbps%nTransfer speed (average): %.3f MB/s%n"
                                + "Current throughput: %.3f Mbps (%.3f MB/s)%n"
                                + "Peak throughput: %.3f Mbps (%.3f MB/s)%nProgress: %.2f%%%n",
                        result.getMode(), result.getTotalBytes(), result.getElapsedSeconds(),
                        result.getThroughputMbps(), result.getMegabytesPerSecond(),
                        result.getMeasurement().currentMbps(), result.getMeasurement().currentMBps(),
                        result.getMeasurement().peakMbps(), result.getMeasurement().peakMBps(),
                        result.getMeasurement().progressPercent().orElseThrow());
            } catch (IOException exception) {
                System.err.println("Transfer failed: " + exception.getMessage());
                throw exception;
            }
        } else {
            throw new IllegalArgumentException(
                    "Usage: server | client UPLOAD|DOWNLOAD <bytes> | adapters | monitor <index> [samples]");
        }
    }

    private static void runAdapterMonitor(String[] args) throws IOException, InterruptedException {
        int index;
        int samples;
        try {
            index = Integer.parseInt(args[1]);
            samples = args.length == 3 ? Integer.parseInt(args[2]) : 5;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Adapter index and sample count must be whole numbers.");
        }
        if (index <= 0 || samples < 1 || samples > 60) {
            throw new IllegalArgumentException("Use a positive adapter index and between 1 and 60 samples.");
        }
        NetworkAdapter selected = NetworkAdapterMonitor.listAdapters().stream()
                .filter(adapter -> adapter.index() == index).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No Java network interface has index " + index));

        try (NetworkAdapterMonitor monitor = new NetworkAdapterMonitor(selected)) {
            Thread shutdownHook = new Thread(monitor::stop, "netpulse-adapter-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                monitor.start();
                NetworkStatisticsSnapshot previous = null;
                int printed = 0;
                while (printed < samples) {
                    NetworkStatisticsSnapshot snapshot = monitor.getSnapshot();
                    if (snapshot != previous && snapshot.state() != NetworkStatisticsSnapshot.State.STARTING) {
                        printAdapterSnapshot(snapshot);
                        previous = snapshot;
                        printed++;
                    }
                    if (printed < samples) {
                        Thread.sleep(100);
                    }
                }
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown has already started.
                }
            }
        }
    }

    private static void printAdapterSnapshot(NetworkStatisticsSnapshot snapshot) {
        System.out.printf(Locale.ROOT,
                "Adapter traffic: %s | index=%d | status=%s%nReading: %s | interval=%.3f seconds%n"
                        + "OS counters: received=%s bytes, sent=%s bytes%n"
                        + "Receive: %s Mbps / %s MB/s%nSend: %s Mbps / %s MB/s%n"
                        + "Session totals: received=%d bytes, sent=%d bytes%n%s%n%n",
                snapshot.adapterName(), snapshot.adapter().index(), snapshot.adapterStatus(), snapshot.state(),
                snapshot.intervalNanos() / 1_000_000_000.0,
                formatCounter(snapshot.receivedBytes()), formatCounter(snapshot.sentBytes()),
                formatRate(snapshot.receiveMbps()), formatRate(snapshot.receiveMBps()),
                formatRate(snapshot.sendMbps()), formatRate(snapshot.sendMBps()),
                snapshot.totalReceivedBytes(), snapshot.totalSentBytes(), snapshot.message());
    }

    private static String formatCounter(OptionalLong counter) {
        return counter.isPresent() ? Long.toString(counter.getAsLong()) : "N/A";
    }

    private static String formatRate(OptionalDouble rate) {
        return rate.isPresent() ? String.format(Locale.ROOT, "%.6f", rate.getAsDouble()) : "N/A";
    }

    private static void runServer() throws IOException, InterruptedException {
        try (BandwidthServer server = new BandwidthServer()) {
            Thread shutdownHook = new Thread(server::close, "netpulse-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                server.start();
                server.awaitTermination();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown has already started and will run the hook.
                }
            }
        }
    }
}
