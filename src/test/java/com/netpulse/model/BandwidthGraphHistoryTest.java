package com.netpulse.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class BandwidthGraphHistoryTest {
    @Test
    void addsBothDirectionsFromTheSameAdapterSnapshot() {
        AtomicLong clock = new AtomicLong();
        BandwidthGraphHistory history = new BandwidthGraphHistory(clock::get);
        history.start();
        clock.set(1_000_000_000L);
        assertTrue(history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 1_000_000, 500_000)));
        var sample = history.getWindow().samples().getFirst();
        assertEquals(1_000_000_000L, sample.elapsedNanos());
        assertEquals(8.0, sample.downloadMbps().orElseThrow());
        assertEquals(4.0, sample.uploadMbps().orElseThrow());
    }

    @Test
    void repeatedRefreshesDoNotDuplicateASnapshotButEqualNewReadingsAreKept() {
        AtomicLong clock = new AtomicLong();
        BandwidthGraphHistory history = new BandwidthGraphHistory(clock::get);
        history.start();
        var first = snapshot(NetworkStatisticsSnapshot.State.VALID, 0, 0);
        var next = snapshot(NetworkStatisticsSnapshot.State.VALID, 0, 0);
        assertEquals(first, next);
        assertNotSame(first, next);
        assertTrue(history.addSnapshot(first));
        clock.set(500_000_000L);
        assertFalse(history.addSnapshot(first));
        clock.set(1_000_000_000L);
        assertTrue(history.addSnapshot(next));
        assertEquals(2, history.getWindow().samples().size());
    }

    @Test
    void maintainsSixtySecondWindowAndEvictsOldDataEvenWithoutNewReadings() {
        AtomicLong clock = new AtomicLong();
        BandwidthGraphHistory history = new BandwidthGraphHistory(clock::get);
        history.start();
        history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 10, 20));
        clock.set(59_000_000_000L);
        history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 20, 30));
        clock.set(60_000_000_000L);
        history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 30, 40));
        assertEquals(3, history.getWindow().samples().size()); // The exact sixty-second boundary is included.
        clock.incrementAndGet();
        assertEquals(2, history.getWindow().samples().size());
        clock.set(121_000_000_000L);
        assertTrue(history.getWindow().samples().isEmpty());
    }

    @Test
    void measuredZeroSpeedsRemainValidPoints() {
        BandwidthGraphHistory history = new BandwidthGraphHistory();
        history.start();
        history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 0, 0));
        var sample = history.getWindow().samples().getFirst();
        assertTrue(sample.downloadMbps().isPresent());
        assertEquals(0.0, sample.downloadMbps().orElseThrow());
        assertEquals(0.0, sample.uploadMbps().orElseThrow());
    }

    @ParameterizedTest
    @EnumSource(value = NetworkStatisticsSnapshot.State.class,
            names = {"BASELINE", "COUNTER_RESET", "INVALID_INTERVAL", "UNAVAILABLE"})
    void unavailableReadingsAreGapsRatherThanInventedZeros(NetworkStatisticsSnapshot.State state) {
        BandwidthGraphHistory history = new BandwidthGraphHistory();
        history.start();
        history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 100, 200));
        history.addSnapshot(snapshot(state, 0, 0));
        history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 300, 400));
        var samples = history.getWindow().samples();
        assertEquals(3, samples.size());
        assertTrue(samples.get(0).downloadMbps().isPresent());
        assertTrue(samples.get(1).downloadMbps().isEmpty());
        assertTrue(samples.get(1).uploadMbps().isEmpty());
        assertTrue(samples.get(2).downloadMbps().isPresent());
    }

    @Test
    void memoryIsBoundedEvenWhenManySamplesHaveTheSameTimestamp() {
        BandwidthGraphHistory history = new BandwidthGraphHistory(() -> 0);
        history.start();
        for (int index = 0; index < 10_000; index++) {
            history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, index, index));
        }
        assertEquals(BandwidthGraphHistory.MAX_SAMPLES, history.getWindow().samples().size());
        assertEquals(9_999 * 8.0 / 1_000_000,
                history.getWindow().samples().getLast().downloadMbps().orElseThrow(), 0.000000001);
    }

    @Test
    void stoppingFreezesBothDataAndTimeUntilANewSessionStarts() {
        AtomicLong clock = new AtomicLong();
        BandwidthGraphHistory history = new BandwidthGraphHistory(clock::get);
        history.start();
        history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 100, 200));
        clock.set(5_000_000_000L);
        history.stop();
        var frozen = history.getWindow();
        assertFalse(frozen.running());
        clock.set(90_000_000_000L);
        assertFalse(history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 500, 600)));
        history.stop();
        assertEquals(frozen, history.getWindow());
        history.start();
        assertTrue(history.getWindow().running());
        assertTrue(history.getWindow().samples().isEmpty());
        assertEquals(0, history.getWindow().endNanos());
    }

    @Test
    void noSamplesAreAddedBeforeStartOrForStartingAndStoppedSnapshots() {
        BandwidthGraphHistory history = new BandwidthGraphHistory();
        assertFalse(history.getWindow().started());
        assertFalse(history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 1, 2)));
        history.start();
        assertFalse(history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.STARTING, 0, 0)));
        assertFalse(history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.STOPPED, 0, 0)));
        assertTrue(history.getWindow().samples().isEmpty());
        history.clear();
        assertFalse(history.getWindow().started());
        assertFalse(history.isRunning());
    }

    @Test
    void windowsAreImmutableAndBackwardClockValuesCannotMoveTheGraphBackwards() {
        AtomicLong clock = new AtomicLong(5_000_000_000L);
        BandwidthGraphHistory history = new BandwidthGraphHistory(clock::get);
        history.start();
        clock.set(6_000_000_000L);
        history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 100, 200));
        var earlier = history.getWindow();
        clock.set(5_500_000_000L);
        history.addSnapshot(snapshot(NetworkStatisticsSnapshot.State.VALID, 200, 300));
        assertEquals(1_000_000_000L, history.getWindow().endNanos());
        assertEquals(1, earlier.samples().size());
        assertThrows(UnsupportedOperationException.class, () -> earlier.samples().clear());
    }

    private NetworkStatisticsSnapshot snapshot(NetworkStatisticsSnapshot.State state, double received, double sent) {
        return new NetworkStatisticsSnapshot(new NetworkAdapter(6, "wlan0", "Wi-Fi", true, false),
                "Wi-Fi", "Up", OptionalLong.of(1_000), OptionalLong.of(2_000), 100, 200,
                1_000_000_000L, OptionalDouble.of(received), OptionalDouble.of(sent), state, "Test reading");
    }
}
