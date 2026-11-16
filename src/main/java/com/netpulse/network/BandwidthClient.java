package com.netpulse.network;

import com.netpulse.model.TransferResult;
import com.netpulse.model.BandwidthSnapshot;
import com.netpulse.monitoring.BandwidthMeasurement;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class BandwidthClient {
    private final int port;
    private final int socketTimeoutMillis;

    public BandwidthClient() {
        this(TransferProtocol.PORT, TransferProtocol.SOCKET_TIMEOUT_MS);
    }

    // A custom local port and timeout let tests run without a manually started server.
    public BandwidthClient(int port, int socketTimeoutMillis) {
        if (port < 1 || port > 65535 || socketTimeoutMillis <= 0) {
            throw new IllegalArgumentException("A valid port and positive timeout are required.");
        }
        this.port = port;
        this.socketTimeoutMillis = socketTimeoutMillis;
    }

    public TransferResult transfer(String mode, long size) throws IOException {
        return transfer(mode, size, new BandwidthMeasurement(size));
    }

    // The caller can poll measurement.getSnapshot() from another thread during this call.
    public TransferResult transfer(String mode, long size, BandwidthMeasurement measurement) throws IOException {
        String operation = TransferProtocol.validateRequest(mode, size);
        Objects.requireNonNull(measurement, "Measurement is required.");
        BandwidthSnapshot initial = measurement.getSnapshot();
        if (initial.expectedBytes().isPresent() && initial.expectedBytes().getAsLong() != size) {
            throw new IllegalArgumentException("Measurement expected size must match the request.");
        }
        ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> timeout = null;
        boolean measurementStarted = false;

        try (Socket socket = new Socket()) {
            // SO_TIMEOUT limits reads; closing the socket also stops a blocked write.
            timeout = deadline.schedule(() -> closeSocket(socket),
                    TransferProtocol.TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            socket.connect(new InetSocketAddress(TransferProtocol.HOST, port),
                    TransferProtocol.CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(socketTimeoutMillis);

            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF(operation);
            output.writeLong(size);
            output.flush();

            String response = input.readUTF();
            if (!response.equals("READY")) {
                throw new IOException("Server rejected the request: " + response);
            }

            measurement.start();
            measurementStarted = true;
            long transferred;
            if (operation.equals("UPLOAD")) {
                TransferProtocol.sendBytes(output, size, measurement::recordBytes);
                socket.shutdownOutput();
                String status = input.readUTF();
                if (!status.equals("COMPLETE")) {
                    throw new IOException("Upload failed: " + status);
                }
                transferred = input.readLong();
                if (transferred != size) {
                    throw new IOException("Server confirmed an incorrect byte count: " + transferred);
                }
            } else {
                transferred = TransferProtocol.receiveBytes(input, size, measurement::recordBytes);
                output.writeLong(transferred);
                output.flush();
            }
            BandwidthSnapshot snapshot = measurement.finish();
            if (snapshot.totalBytes() != transferred) {
                throw new IOException("Measured payload count does not match the verified transfer.");
            }
            return new TransferResult(operation, snapshot);
        } finally {
            if (measurementStarted) {
                measurement.finish(); // Freeze partial statistics on failure; no result is returned.
            }
            if (timeout != null) {
                timeout.cancel(false);
            }
            deadline.shutdownNow();
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Closing is best-effort; the active transfer reports its own failure.
        }
    }
}
