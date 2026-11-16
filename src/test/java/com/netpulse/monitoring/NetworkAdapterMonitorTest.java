package com.netpulse.monitoring;

import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.NetworkStatisticsSnapshot.State;
import com.netpulse.monitoring.WindowsAdapterStatistics.Counters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class NetworkAdapterMonitorTest {
    private final NetworkAdapter adapter = new NetworkAdapter(6, "wlan0", "Test Wi-Fi", true, false);

    @Test
    void firstReadingIsABaselineAndSpeedsAreUnavailable() {
        try (NetworkAdapterMonitor monitor = monitor(new AtomicLong(), counters(1_000, 2_000))) {
            assertEquals(State.STARTING, monitor.getSnapshot().state());
            assertTrue(monitor.getSnapshot().receivedBytes().isEmpty());
            monitor.sampleOnce();
            NetworkStatisticsSnapshot snapshot = monitor.getSnapshot();
            assertEquals(State.BASELINE, snapshot.state());
            assertEquals(1_000, snapshot.receivedBytes().orElseThrow());
            assertEquals(2_000, snapshot.sentBytes().orElseThrow());
            assertEquals(0, snapshot.totalReceivedBytes());
            assertTrue(snapshot.receiveMbps().isEmpty());
            assertTrue(snapshot.sendMbps().isEmpty());
        }
    }

    @Test
    void calculatesIndependentReceiveAndSendRatesFromActualCounterDeltas() {
        AtomicLong clock = new AtomicLong();
        try (NetworkAdapterMonitor monitor = monitor(clock,
                counters(1_000, 2_000), counters(2_001_000, 1_002_000), counters(2_501_000, 1_252_000))) {
            monitor.sampleOnce();
            clock.set(2_000_000_000L);
            monitor.sampleOnce();
            NetworkStatisticsSnapshot snapshot = monitor.getSnapshot();
            assertEquals(State.VALID, snapshot.state());
            assertEquals(2_000_000_000L, snapshot.intervalNanos());
            assertEquals(8.0, snapshot.receiveMbps().orElseThrow());
            assertEquals(1.0, snapshot.receiveMBps().orElseThrow());
            assertEquals(4.0, snapshot.sendMbps().orElseThrow());
            assertEquals(0.5, snapshot.sendMBps().orElseThrow());
            assertEquals(2_000_000, snapshot.totalReceivedBytes());
            assertEquals(1_000_000, snapshot.totalSentBytes());

            clock.set(3_000_000_000L);
            monitor.sampleOnce();
            assertEquals(4.0, monitor.getSnapshot().receiveMbps().orElseThrow());
            assertEquals(2_500_000, monitor.getSnapshot().totalReceivedBytes());
            assertEquals(1_250_000, monitor.getSnapshot().totalSentBytes());
            assertEquals(2_000_000, snapshot.totalReceivedBytes()); // Earlier snapshot remains immutable.
        }
    }

    @Test
    void unchangedCountersProduceMeasuredZeroSpeeds() {
        AtomicLong clock = new AtomicLong();
        try (NetworkAdapterMonitor monitor = monitor(clock, counters(100, 200), counters(100, 200))) {
            monitor.sampleOnce();
            clock.set(1_000_000_000L);
            monitor.sampleOnce();
            assertEquals(State.VALID, monitor.getSnapshot().state());
            assertEquals(0.0, monitor.getSnapshot().receiveMbps().orElseThrow());
            assertEquals(0.0, monitor.getSnapshot().sendMBps().orElseThrow());
        }
    }

    @Test
    void zeroAndBackwardTimeDoNotDivideOrDiscardTheBaseline() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        try (NetworkAdapterMonitor monitor = monitor(clock,
                counters(100, 200), counters(150, 250), counters(175, 275), counters(200, 300))) {
            monitor.sampleOnce();
            monitor.sampleOnce();
            assertEquals(State.INVALID_INTERVAL, monitor.getSnapshot().state());
            assertTrue(monitor.getSnapshot().receiveMbps().isEmpty());
            clock.set(900_000_000L);
            monitor.sampleOnce();
            assertEquals(State.INVALID_INTERVAL, monitor.getSnapshot().state());
            assertEquals(0, monitor.getSnapshot().totalReceivedBytes());
            clock.set(2_000_000_000L);
            monitor.sampleOnce();
            assertEquals(100.0, monitor.getSnapshot().receiveBytesPerSecond().orElseThrow());
            assertEquals(100, monitor.getSnapshot().totalReceivedBytes());
        }
    }

    @Test
    void counterResetDoesNotCreateNegativeTotalsOrASpeedSpike() {
        AtomicLong clock = new AtomicLong();
        try (NetworkAdapterMonitor monitor = monitor(clock,
                counters(1_000, 2_000), counters(1_100, 2_100), counters(10, 20), counters(30, 50))) {
            monitor.sampleOnce();
            clock.set(1_000_000_000L);
            monitor.sampleOnce();
            clock.set(2_000_000_000L);
            monitor.sampleOnce();
            assertEquals(State.COUNTER_RESET, monitor.getSnapshot().state());
            assertTrue(monitor.getSnapshot().receiveMbps().isEmpty());
            assertEquals(100, monitor.getSnapshot().totalReceivedBytes());
            clock.set(3_000_000_000L);
            monitor.sampleOnce();
            assertEquals(120, monitor.getSnapshot().totalReceivedBytes());
            assertEquals(130, monitor.getSnapshot().totalSentBytes());
            assertEquals(20.0, monitor.getSnapshot().receiveBytesPerSecond().orElseThrow());
        }
    }

    @Test
    void reusedInterfaceIndexStartsANewBaselineEvenIfCountersIncreased() {
        AtomicLong clock = new AtomicLong();
        try (NetworkAdapterMonitor monitor = monitor(clock, counters(100, 200),
                new Counters("replacement-id", "Replacement", "Up", 10_000, 20_000))) {
            monitor.sampleOnce();
            clock.set(1_000_000_000L);
            monitor.sampleOnce();
            assertEquals(State.COUNTER_RESET, monitor.getSnapshot().state());
            assertEquals(0, monitor.getSnapshot().totalReceivedBytes());
            assertEquals("Replacement", monitor.getSnapshot().adapterName());
        }
    }

    @Test
    void invalidAndFailedReadingsAreUnavailableAndRecoveryExcludesTheGap() {
        AtomicLong clock = new AtomicLong();
        try (NetworkAdapterMonitor monitor = monitor(clock, counters(100, 200), counters(200, 300),
                counters(-1, 400), new IOException("Access denied"), counters(10_000, 20_000),
                counters(10_050, 20_075))) {
            monitor.sampleOnce();
            clock.set(1_000_000_000L);
            monitor.sampleOnce();
            clock.set(2_000_000_000L);
            monitor.sampleOnce();
            assertEquals(State.UNAVAILABLE, monitor.getSnapshot().state());
            assertTrue(monitor.getSnapshot().receivedBytes().isEmpty());
            assertTrue(monitor.getSnapshot().receiveMbps().isEmpty());
            monitor.sampleOnce();
            assertTrue(monitor.getSnapshot().message().contains("Access denied"));
            clock.set(4_000_000_000L);
            monitor.sampleOnce();
            assertEquals(State.BASELINE, monitor.getSnapshot().state());
            assertEquals(100, monitor.getSnapshot().totalReceivedBytes());
            clock.set(5_000_000_000L);
            monitor.sampleOnce();
            assertEquals(150, monitor.getSnapshot().totalReceivedBytes());
            assertEquals(175, monitor.getSnapshot().totalSentBytes());
        }
    }

    @Test
    void disconnectedAdaptersHaveNoSpeedAndReconnectionRebaselines() {
        AtomicLong clock = new AtomicLong();
        try (NetworkAdapterMonitor monitor = monitor(clock, counters(100, 200),
                new Counters("adapter-id", "Wi-Fi", "Disconnected", 150, 250), counters(1_000, 2_000))) {
            monitor.sampleOnce();
            clock.set(1_000_000_000L);
            monitor.sampleOnce();
            assertEquals(State.UNAVAILABLE, monitor.getSnapshot().state());
            assertEquals("Disconnected", monitor.getSnapshot().adapterStatus());
            assertTrue(monitor.getSnapshot().sendMBps().isEmpty());
            clock.set(2_000_000_000L);
            monitor.sampleOnce();
            assertEquals(State.BASELINE, monitor.getSnapshot().state());
            assertEquals(0, monitor.getSnapshot().totalReceivedBytes());
        }
    }

    @Test
    void stoppingReturnsTheLastRealReadingEvenAfterThePublicSnapshotBecomesStopped() {
        AtomicLong clock = new AtomicLong();
        try (NetworkAdapterMonitor monitor = monitor(clock, counters(100, 200), counters(300, 500))) {
            monitor.sampleOnce();
            clock.set(1_000_000_000L);
            monitor.sampleOnce();
            var last = monitor.getSnapshot();
            assertSame(last, monitor.stopAndGetLastSnapshot());
            assertEquals(State.VALID, last.state());
            assertEquals(200, last.totalReceivedBytes());
            assertEquals(State.STOPPED, monitor.getSnapshot().state());
            assertSame(last, monitor.stopAndGetLastSnapshot());
        }
    }

    @Test
    void stopInterruptsActiveReadAndTerminatesTheWorker() throws InterruptedException {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        WindowsAdapterStatistics source = new WindowsAdapterStatistics() {
            @Override
            Counters read(int index) throws IOException {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                    throw new AssertionError("The read should have been interrupted.");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Stopped");
                } finally {
                    exited.countDown();
                }
            }
        };
        try (NetworkAdapterMonitor monitor = new NetworkAdapterMonitor(adapter, source, System::nanoTime, 10)) {
            monitor.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            monitor.stop();
            assertTrue(exited.await(2, TimeUnit.SECONDS));
            assertEquals(State.STOPPED, monitor.getSnapshot().state());
            assertTrue(monitor.getSnapshot().receiveMbps().isEmpty());
            assertThrows(IllegalStateException.class, monitor::start);
        }
        assertTrue(Thread.getAllStackTraces().keySet().stream()
                .noneMatch(thread -> thread.isAlive() && thread.getName().equals("netpulse-adapter-monitor")));
    }

    private NetworkAdapterMonitor monitor(AtomicLong clock, Object... readings) {
        Queue<Object> pending = new ArrayDeque<>(Arrays.asList(readings));
        WindowsAdapterStatistics source = new WindowsAdapterStatistics() {
            @Override
            Counters read(int index) throws IOException {
                Object next = pending.remove();
                if (next instanceof IOException exception) {
                    throw exception;
                }
                return (Counters) next;
            }
        };
        return new NetworkAdapterMonitor(adapter, source, clock::get, 10);
    }

    private Counters counters(long received, long sent) {
        return new Counters("adapter-id", "Wi-Fi", "Up", received, sent);
    }
}
