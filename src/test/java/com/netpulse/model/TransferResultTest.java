package com.netpulse.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TransferResultTest {
    @Test
    void calculatesDecimalMegabitsAndMegabytesPerSecond() {
        TransferResult result = new TransferResult("UPLOAD", 10_000_000, 2_000_000_000L);
        assertEquals(2.0, result.getElapsedSeconds());
        assertEquals(5.0, result.getMegabytesPerSecond());
        assertEquals(40.0, result.getThroughputMbps());
    }

    @Test
    void handlesByteCountsLargerThanAnIntegerWithoutOverflow() {
        TransferResult result = new TransferResult("DOWNLOAD", 3_000_000_000L, 1_000_000_000L);
        assertEquals(24_000.0, result.getThroughputMbps());
    }

    @Test
    void rejectsInvalidMeasurements() {
        assertThrows(IllegalArgumentException.class, () -> new TransferResult("PING", 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TransferResult("UPLOAD", 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TransferResult("UPLOAD", 1, 0));
    }
}
