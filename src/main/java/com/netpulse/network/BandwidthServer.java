package com.netpulse.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BandwidthServer implements AutoCloseable {
    private final ServerSocket serverSocket;
    private final ThreadPoolExecutor clients;
    private final ScheduledExecutorService deadlines;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Thread acceptThread;

    public BandwidthServer() throws IOException {
        this(TransferProtocol.PORT);
    }

    // Port 0 asks Windows to choose an unused local port for an automated test.
    public BandwidthServer(int port) throws IOException {
        serverSocket = new ServerSocket(port, 50, InetAddress.getByName(TransferProtocol.HOST));
        clients = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4));
        deadlines = Executors.newSingleThreadScheduledExecutor();
    }

    public synchronized void start() {
        if (closed.get() || acceptThread != null) {
            throw new IllegalStateException("The server is closed or has already been started.");
        }
        acceptThread = new Thread(this::acceptConnections, "netpulse-server");
        acceptThread.start();
        System.out.println("Server listening on " + TransferProtocol.HOST + ":" + getPort());
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    public void awaitTermination() throws InterruptedException {
        if (acceptThread == null) {
            throw new IllegalStateException("Start the server first.");
        }
        acceptThread.join();
    }

    private void acceptConnections() {
        try (ServerSocket listener = serverSocket) {
            while (!closed.get()) {
                Socket socket = listener.accept();
                activeSockets.add(socket);
                try {
                    clients.execute(() -> handleClient(socket));
                } catch (RejectedExecutionException exception) {
                    activeSockets.remove(socket);
                    closeSocket(socket);
                    if (!closed.get()) {
                        System.err.println("Server busy: connection rejected.");
                    }
                }
            }
        } catch (IOException exception) {
            if (!closed.get()) {
                System.err.println("Server connection error: " + exception.getMessage());
            }
        }
    }

    private void handleClient(Socket socket) {
        ScheduledFuture<?> timeout = null;
        try (socket) {
            if (closed.get()) {
                return;
            }
            timeout = deadlines.schedule(() -> closeSocket(socket),
                    TransferProtocol.TRANSFER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            socket.setSoTimeout(TransferProtocol.SOCKET_TIMEOUT_MS);
            DataInputStream input = new DataInputStream(socket.getInputStream());
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            System.out.println("Client connected: " + socket.getRemoteSocketAddress());

            try {
                String mode = input.readUTF();
                long requested = input.readLong();
                mode = TransferProtocol.validateRequest(mode, requested);
                output.writeUTF("READY");
                output.flush();
                System.out.println("Starting " + mode + ": " + requested + " bytes");

                long transferred;
                if (mode.equals("UPLOAD")) {
                    transferred = TransferProtocol.receiveBytes(input, requested);
                    output.writeUTF("COMPLETE");
                    output.writeLong(transferred);
                    output.flush();
                } else {
                    TransferProtocol.sendBytes(output, requested);
                    socket.shutdownOutput();
                    transferred = input.readLong();
                    if (transferred != requested) {
                        throw new IOException("Client confirmed an incorrect byte count: " + transferred);
                    }
                }
                System.out.println("Completed " + mode + ": " + transferred + " bytes");
            } catch (IllegalArgumentException | IOException exception) {
                System.err.println("Client request failed: " + exception.getMessage());
                if (!socket.isOutputShutdown()) {
                    try {
                        output.writeUTF("ERROR: " + exception.getMessage());
                        output.flush();
                    } catch (IOException ignored) {
                        // A disconnected client cannot receive an error response.
                    }
                }
            }
        } catch (IOException | RejectedExecutionException exception) {
            if (!closed.get()) {
                System.err.println("Client connection error: " + exception.getMessage());
            }
        } finally {
            if (timeout != null) {
                timeout.cancel(false);
            }
            activeSockets.remove(socket);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException exception) {
            System.err.println("Could not close listener: " + exception.getMessage());
        }
        clients.shutdownNow();
        activeSockets.forEach(BandwidthServer::closeSocket);
        deadlines.shutdownNow();
        try {
            if (acceptThread != null && acceptThread != Thread.currentThread()) {
                acceptThread.join(2_000);
            }
            clients.awaitTermination(2, TimeUnit.SECONDS);
            deadlines.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Continue closing the other connections.
        }
    }
}
