package com.netpulse;

import com.netpulse.model.TransferResult;
import com.netpulse.network.BandwidthClient;
import com.netpulse.network.BandwidthServer;

import java.io.IOException;
import java.util.Locale;

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        System.out.println("NetPulse - Network Bandwidth Monitoring and Analysis System");
        System.out.println("Application Started Successfully");

        if (args.length == 0) {
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("server")) {
            runServer();
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
                                + "Throughput: %.3f Mbps%nTransfer speed: %.3f MB/s%n",
                        result.getMode(), result.getTotalBytes(), result.getElapsedSeconds(),
                        result.getThroughputMbps(), result.getMegabytesPerSecond());
            } catch (IOException exception) {
                System.err.println("Transfer failed: " + exception.getMessage());
                throw exception;
            }
        } else {
            throw new IllegalArgumentException(
                    "Usage: server | client UPLOAD|DOWNLOAD <bytes>");
        }
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
