package com.netpulse.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class BandwidthClientFailureTest {
    @ParameterizedTest
    @ValueSource(ints = {2, 6})
    void rejectsIncompleteAndOversizedDownloads(int actualBytes) throws Exception {
        try (ServerSocket listener = localListener();
             var worker = Executors.newSingleThreadExecutor()) {
            var peer = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(2_000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    assertEquals("DOWNLOAD", input.readUTF());
                    assertEquals(5, input.readLong());
                    output.writeUTF("READY");
                    output.write(new byte[actualBytes]);
                    socket.shutdownOutput();
                }
                return null;
            });
            BandwidthClient client = new BandwidthClient(listener.getLocalPort(), 1_000);
            assertThrows(IOException.class, () -> client.transfer("DOWNLOAD", 5));
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void stalledDownloadTimesOut() throws Exception {
        try (ServerSocket listener = localListener();
             var worker = Executors.newSingleThreadExecutor()) {
            var peer = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(2_000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    input.readUTF();
                    input.readLong();
                    output.writeUTF("READY");
                    output.flush();
                    // Wait for the timed-out client to close instead of sending payload.
                    assertEquals(-1, input.read());
                }
                return null;
            });
            BandwidthClient client = new BandwidthClient(listener.getLocalPort(), 200);
            assertThrows(SocketTimeoutException.class, () -> client.transfer("DOWNLOAD", 5));
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void uploadRequiresAnExactServerAcknowledgment() throws Exception {
        try (ServerSocket listener = localListener();
             var worker = Executors.newSingleThreadExecutor()) {
            var peer = worker.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(2_000);
                    var input = new DataInputStream(socket.getInputStream());
                    var output = new DataOutputStream(socket.getOutputStream());
                    input.readUTF();
                    long size = input.readLong();
                    output.writeUTF("READY");
                    output.flush();
                    assertEquals(size, TransferProtocol.receiveBytes(input, size));
                    output.writeUTF("COMPLETE");
                    output.writeLong(size + 1);
                    output.flush();
                }
                return null;
            });
            BandwidthClient client = new BandwidthClient(listener.getLocalPort(), 1_000);
            assertThrows(IOException.class, () -> client.transfer("UPLOAD", 5));
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    private ServerSocket localListener() throws IOException {
        ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        listener.setSoTimeout(2_000);
        return listener;
    }
}
