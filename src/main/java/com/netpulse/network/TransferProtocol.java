package com.netpulse.network;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.function.LongConsumer;

// Shared constants and byte-transfer helpers for the client and server.
final class TransferProtocol {
    static final String HOST = "127.0.0.1";
    static final int PORT = 5001;
    static final int BUFFER_SIZE = 64 * 1024;
    static final int SOCKET_TIMEOUT_MS = 5_000;
    static final int CONNECT_TIMEOUT_MS = 3_000;
    static final int TRANSFER_TIMEOUT_SECONDS = 60;
    static final long MAX_TRANSFER_BYTES = 1024L * 1024 * 1024;

    private TransferProtocol() {
    }

    static String validateRequest(String mode, long size) {
        if (mode == null) {
            throw new IllegalArgumentException("Transfer mode must be UPLOAD or DOWNLOAD.");
        }
        String normalizedMode = mode.trim().toUpperCase(Locale.ROOT);
        if (!normalizedMode.equals("UPLOAD") && !normalizedMode.equals("DOWNLOAD")) {
            throw new IllegalArgumentException("Transfer mode must be UPLOAD or DOWNLOAD.");
        }
        if (size <= 0 || size > MAX_TRANSFER_BYTES) {
            throw new IllegalArgumentException(
                    "Transfer size must be between 1 and " + MAX_TRANSFER_BYTES + " bytes.");
        }
        return normalizedMode;
    }

    static long sendBytes(OutputStream output, long size) throws IOException {
        return sendBytes(output, size, count -> { });
    }

    static long sendBytes(OutputStream output, long size, LongConsumer payloadCounter) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long sent = 0;
        while (sent < size) {
            int count = (int) Math.min(buffer.length, size - sent);
            output.write(buffer, 0, count);
            sent += count;
            payloadCounter.accept(count);
        }
        output.flush();
        return sent;
    }

    static long receiveBytes(InputStream input, long size) throws IOException {
        return receiveBytes(input, size, count -> { });
    }

    static long receiveBytes(InputStream input, long size, LongConsumer payloadCounter) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long received = 0;
        while (received < size) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, size - received));
            if (count == -1) {
                throw new EOFException("Expected " + size + " bytes but received " + received + ".");
            }
            received += count;
            payloadCounter.accept(count);
        }
        // The sender half-closes its output after the payload. This also detects extra bytes.
        if (input.read() != -1) {
            throw new IOException("Received more than the requested " + size + " bytes.");
        }
        return received;
    }
}
