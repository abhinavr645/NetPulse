package com.netpulse.network;

import com.netpulse.model.TransferResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class BandwidthTransferTest {
    private BandwidthServer server;
    private BandwidthClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new BandwidthServer(0);
        server.start();
        client = new BandwidthClient(server.getPort(), 2_000);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void clientCanConnectAndExchangeARequest() throws IOException {
        try (Socket socket = connect()) {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());
            output.writeUTF("DOWNLOAD");
            output.writeLong(1);
            assertEquals("READY", input.readUTF());
            assertEquals(0, input.read());
            assertEquals(-1, input.read());
            output.writeLong(1);
            output.flush();
        }
    }

    @Test
    void uploadTenMebibytes() throws IOException {
        assertSuccessful(client.transfer("UPLOAD", 10_485_760), "UPLOAD", 10_485_760);
    }

    @Test
    void downloadTenMebibytes() throws IOException {
        assertSuccessful(client.transfer("DOWNLOAD", 10_485_760), "DOWNLOAD", 10_485_760);
    }

    @ParameterizedTest
    @CsvSource({"UPLOAD,1", "UPLOAD,65536", "UPLOAD,65659",
            "DOWNLOAD,1", "DOWNLOAD,65536", "DOWNLOAD,65659"})
    void transfersExactByteCounts(String mode, long size) throws IOException {
        assertSuccessful(client.transfer(mode, size), mode, size);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"PING", "UPLAOD"})
    void rejectsInvalidClientMode(String mode) {
        assertThrows(IllegalArgumentException.class, () -> client.transfer(mode, 10));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 1_073_741_825L, Long.MAX_VALUE})
    void rejectsInvalidClientSize(long size) {
        assertThrows(IllegalArgumentException.class, () -> client.transfer("UPLOAD", size));
    }

    @ParameterizedTest
    @CsvSource({"PING,10", "UPLOAD,0", "DOWNLOAD,-1", "UPLOAD,1073741825"})
    void serverRejectsInvalidRequestsAndRemainsAvailable(String mode, long size) throws IOException {
        try (Socket socket = connect()) {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());
            output.writeUTF(mode);
            output.writeLong(size);
            output.flush();
            assertTrue(input.readUTF().startsWith("ERROR:"));
        }
        assertEquals(10, client.transfer("UPLOAD", 10).getTotalBytes());
    }

    @Test
    void serverUnavailableProducesAnIOException() throws IOException {
        int unusedPort;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            unusedPort = reservation.getLocalPort();
        }
        BandwidthClient unavailable = new BandwidthClient(unusedPort, 500);
        assertThrows(IOException.class, () -> unavailable.transfer("UPLOAD", 10));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 4})
    void serverRejectsShortAndOversizedUploads(int actualBytes) throws IOException {
        try (Socket socket = connect()) {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());
            output.writeUTF("UPLOAD");
            output.writeLong(3);
            assertEquals("READY", input.readUTF());
            output.write(new byte[actualBytes]);
            socket.shutdownOutput();
            assertTrue(input.readUTF().startsWith("ERROR:"));
        }
        assertEquals(10, client.transfer("DOWNLOAD", 10).getTotalBytes());
    }

    @Test
    void concurrentClientsCanUploadAndDownload() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            var upload = workers.submit(() -> client.transfer("UPLOAD", 1_048_699));
            var download = workers.submit(() -> client.transfer("DOWNLOAD", 1_048_699));
            assertEquals(1_048_699, upload.get(5, TimeUnit.SECONDS).getTotalBytes());
            assertEquals(1_048_699, download.get(5, TimeUnit.SECONDS).getTotalBytes());
        }
    }

    @Test
    void closingServerStopsAnUnfinishedTransfer() throws IOException {
        try (Socket socket = connect()) {
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());
            output.writeUTF("UPLOAD");
            output.writeLong(10);
            assertEquals("READY", input.readUTF());
            server.close();
            assertThrows(IOException.class, input::readUTF);
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", server.getPort());
        socket.setSoTimeout(2_000);
        return socket;
    }

    private void assertSuccessful(TransferResult result, String mode, long size) {
        assertEquals(mode, result.getMode());
        assertEquals(size, result.getTotalBytes());
        assertTrue(result.getElapsedNanos() > 0);
        assertTrue(Double.isFinite(result.getThroughputMbps()));
        assertTrue(result.getThroughputMbps() > 0);
        assertEquals(result.getMegabytesPerSecond() * 8, result.getThroughputMbps(), 0.000001);
    }
}
