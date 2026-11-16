package com.netpulse.network;

import com.netpulse.model.TransferResult;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
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
        String operation = TransferProtocol.validateRequest(mode, size);
        ScheduledExecutorService deadline = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> timeout = null;

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

            long start = System.nanoTime();
            long transferred;
            if (operation.equals("UPLOAD")) {
                TransferProtocol.sendBytes(output, size);
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
                transferred = TransferProtocol.receiveBytes(input, size);
                output.writeLong(transferred);
                output.flush();
            }
            long elapsed = Math.max(1, System.nanoTime() - start);
            return new TransferResult(operation, transferred, elapsed);
        } finally {
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
