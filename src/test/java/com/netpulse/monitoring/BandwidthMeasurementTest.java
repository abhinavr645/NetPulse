package com.netpulse.monitoring;

import com.netpulse.model.BandwidthSnapshot;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BandwidthMeasurementTest {
    @Test
    void calculatesMbpsAndMegabytesPerSecond() {
        AtomicLong clock = new AtomicLong();
        BandwidthMeasurement measurement = new BandwidthMeasurement(10_000_000, clock::get);
        measurement.start();
        clock.set(2_000_000_000L);
        measurement.recordBytes(10_000_000);

        BandwidthSnapshot snapshot = measurement.finish();
        assertEquals(10_000_000, snapshot.totalBytes());
        assertEquals(2.0, snapshot.elapsedSeconds());
        assertEquals(40.0, snapshot.currentMbps());
        assertEquals(40.0, snapshot.averageMbps());
        assertEquals(40.0, snapshot.peakMbps());
        assertEquals(5.0, snapshot.currentMBps());
        assertEquals(5.0, snapshot.averageMBps());
        assertEquals(5.0, snapshot.peakMBps());
    }

    @Test
    void emptyTransferHasZeroRatesAndCompleteProgress() {
        AtomicLong clock = new AtomicLong();
        BandwidthMeasurement measurement = new BandwidthMeasurement(0, clock::get);
        measurement.start();
        measurement.recordBytes(0);
        clock.set(1_000_000_000L);
        BandwidthSnapshot snapshot = measurement.finish();

        assertEquals(0, snapshot.totalBytes());
        assertEquals(0.0, snapshot.currentMbps());
        assertEquals(0.0, snapshot.averageMbps());
        assertEquals(0.0, snapshot.peakMbps());
        assertEquals(100.0, snapshot.progressPercent().orElseThrow());
    }

    @Test
    void zeroElapsedTimeDoesNotInventThroughput() {
        BandwidthMeasurement measurement = new BandwidthMeasurement(100, () -> 42);
        measurement.start();
        measurement.recordBytes(100);
        BandwidthSnapshot snapshot = measurement.finish();

        assertEquals(100, snapshot.totalBytes());
        assertEquals(0, snapshot.elapsedNanos());
        assertEquals(0.0, snapshot.currentMBps());
        assertEquals(0.0, snapshot.averageMBps());
        assertEquals(0.0, snapshot.peakMBps());
        assertEquals(0.0, BandwidthSnapshot.bytesPerSecond(100, 0));
    }

    @Test
    void rejectsNegativeElapsedTimeAndInvalidSizes() {
        assertThrows(IllegalArgumentException.class, () -> new BandwidthMeasurement(-1));
        assertThrows(IllegalArgumentException.class, () -> BandwidthSnapshot.bytesPerSecond(1, -1));
        assertThrows(IllegalArgumentException.class, () -> BandwidthSnapshot.bytesPerSecond(-1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new BandwidthSnapshot(1, -1, 0, 0, OptionalLong.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new BandwidthSnapshot(1, 1, Double.NaN, 0, OptionalLong.empty()));
    }

    @Test
    void backwardClockReadingsCannotDecreaseElapsedTimeOrCreateSpikes() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        BandwidthMeasurement measurement = new BandwidthMeasurement(200, clock::get);
        measurement.start();
        clock.set(900_000_000L);
        measurement.recordBytes(100);
        assertEquals(0.0, measurement.getSnapshot().averageMbps());

        clock.set(1_100_000_000L);
        measurement.recordBytes(100);
        clock.set(1_050_000_000L);
        BandwidthSnapshot snapshot = measurement.finish();
        assertEquals(100_000_000L, snapshot.elapsedNanos());
        assertEquals(2_000.0, snapshot.averageBytesPerSecond());
        assertEquals(2_000.0, snapshot.peakBytesPerSecond());
    }

    @Test
    void averageUsesTotalBytesAndTimeRatherThanMeanOfSampleRates() {
        AtomicLong clock = new AtomicLong();
        BandwidthMeasurement measurement = new BandwidthMeasurement(2_000_000, clock::get);
        measurement.start();
        clock.set(200_000_000L);
        measurement.recordBytes(1_000_000); // 5 MB/s for 0.2 seconds.
        clock.set(1_200_000_000L);
        measurement.recordBytes(1_000_000); // 1 MB/s for 1 second.

        BandwidthSnapshot snapshot = measurement.finish();
        assertEquals(1.0, snapshot.currentMBps());
        assertEquals(2.0 / 1.2, snapshot.averageMBps(), 0.000001);
        assertNotEquals(3.0, snapshot.averageMBps());
        assertEquals(5.0, snapshot.peakMBps());
    }

    @Test
    void peakSurvivesSlowerSamplesAndCurrentFallsToZeroWhenIdle() {
        AtomicLong clock = new AtomicLong();
        BandwidthMeasurement measurement = new BandwidthMeasurement(3_000_000, clock::get);
        measurement.start();
        clock.set(100_000_000L);
        measurement.recordBytes(1_000_000);
        assertEquals(80.0, measurement.getSnapshot().peakMbps());
        clock.set(300_000_000L);
        measurement.recordBytes(1_000_000);
        assertEquals(40.0, measurement.getSnapshot().currentMbps());
        clock.set(500_000_000L);

        BandwidthSnapshot idle = measurement.getSnapshot();
        assertEquals(0.0, idle.currentMbps());
        assertEquals(80.0, idle.peakMbps());
        assertEquals(32.0, idle.averageMbps());
    }

    @Test
    void progressTracksBytesAndNeverExceedsOneHundredPercent() {
        AtomicLong clock = new AtomicLong();
        BandwidthMeasurement measurement = new BandwidthMeasurement(100, clock::get);
        assertEquals(0.0, measurement.getSnapshot().progressPercent().orElseThrow());
        measurement.start();
        clock.set(100_000_000L);
        measurement.recordBytes(25);
        assertEquals(25.0, measurement.getSnapshot().progressPercent().orElseThrow());
        clock.set(200_000_000L);
        measurement.recordBytes(100);
        assertEquals(125, measurement.getSnapshot().totalBytes());
        assertEquals(100.0, measurement.finish().progressPercent().orElseThrow());
    }

    @Test
    void unknownExpectedSizeHasNoProgressPercentage() {
        BandwidthMeasurement measurement = new BandwidthMeasurement();
        measurement.start();
        measurement.recordBytes(10);
        assertTrue(measurement.getSnapshot().expectedBytes().isEmpty());
        assertTrue(measurement.finish().progressPercent().isEmpty());
    }

    @Test
    void finalShortWindowIsMeasuredWithoutWaitingForOneHundredMilliseconds() {
        AtomicLong clock = new AtomicLong();
        BandwidthMeasurement measurement = new BandwidthMeasurement(500_000, clock::get);
        measurement.start();
        clock.set(50_000_000L);
        measurement.recordBytes(500_000);
        assertEquals(0.0, measurement.getSnapshot().currentMbps());
        BandwidthSnapshot snapshot = measurement.finish();
        assertEquals(80.0, snapshot.currentMbps());
        assertEquals(80.0, snapshot.averageMbps());
        assertEquals(80.0, snapshot.peakMbps());
    }

    @Test
    void snapshotsAreImmutableAndFinishingFreezesStatistics() {
        AtomicLong clock = new AtomicLong();
        BandwidthMeasurement measurement = new BandwidthMeasurement(100, clock::get);
        measurement.start();
        clock.set(100_000_000L);
        measurement.recordBytes(25);
        BandwidthSnapshot earlier = measurement.getSnapshot();
        clock.set(200_000_000L);
        measurement.recordBytes(75);
        BandwidthSnapshot finished = measurement.finish();
        clock.set(5_000_000_000L);

        assertEquals(25, earlier.totalBytes());
        assertEquals(100, finished.totalBytes());
        assertEquals(finished, measurement.getSnapshot());
        assertEquals(finished, measurement.finish());
        assertThrows(IllegalStateException.class, () -> measurement.recordBytes(1));
        assertThrows(IllegalStateException.class, measurement::start);
    }

    @Test
    void requiresStartAndRejectsNegativeByteUpdates() {
        BandwidthMeasurement measurement = new BandwidthMeasurement(10);
        assertThrows(IllegalStateException.class, () -> measurement.recordBytes(1));
        assertThrows(IllegalStateException.class, measurement::finish);
        measurement.start();
        assertThrows(IllegalArgumentException.class, () -> measurement.recordBytes(-1));
        assertEquals(0, measurement.getSnapshot().totalBytes());
    }

    @Test
    void byteCounterOverflowDoesNotCorruptPreviouslyRecordedBytes() {
        BandwidthMeasurement measurement = new BandwidthMeasurement(Long.MAX_VALUE);
        measurement.start();
        measurement.recordBytes(Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> measurement.recordBytes(1));
        assertEquals(Long.MAX_VALUE, measurement.finish().totalBytes());
    }
}
