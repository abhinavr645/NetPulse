package com.netpulse.monitoring;

import com.netpulse.model.AlertSettings;
import com.netpulse.model.NetworkAdapter;
import com.netpulse.model.NetworkStatisticsSnapshot;
import com.netpulse.model.TrafficAlert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;

class TrafficAnalyzerTest {
    private final TrafficAnalyzer analyzer = new TrafficAnalyzer(
            Clock.fixed(Instant.parse("2026-11-17T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void downloadThresholdProducesAWarningWithTimestampAndDescription() {
        enable();
        var alerts = analyzer.analyze(sample(11, 1, 1));
        assertEquals(1, alerts.size());
        assertEquals(TrafficAlert.Type.DOWNLOAD_THRESHOLD, alerts.getFirst().type());
        assertEquals(TrafficAlert.Severity.WARNING, alerts.getFirst().severity());
        assertEquals(Instant.parse("2026-11-17T00:00:00Z"), alerts.getFirst().timestamp());
        assertTrue(alerts.getFirst().description().contains("Wi-Fi [6]"));
        assertTrue(alerts.getFirst().description().contains("Mbps"));
    }

    @Test
    void uploadThresholdProducesAnIndependentWarning() {
        enable();
        var alerts = analyzer.analyze(sample(1, 6, 1));
        assertEquals(1, alerts.size());
        assertEquals(TrafficAlert.Type.UPLOAD_THRESHOLD, alerts.getFirst().type());
        assertEquals(TrafficAlert.Severity.WARNING, alerts.getFirst().severity());
    }

    @Test
    void speedsAtLeastTwiceTheLimitAreCritical() {
        enable();
        var alerts = analyzer.analyze(sample(20, 10, 1));
        assertEquals(2, alerts.size());
        assertTrue(alerts.stream().allMatch(alert -> alert.severity() == TrafficAlert.Severity.CRITICAL));
    }

    @Test
    void equalityDoesNotExceedTheThreshold() {
        enable();
        assertTrue(analyzer.analyze(sample(10, 5, 1)).isEmpty());
    }

    @Test
    void repeatedAboveThresholdSamplesDoNotRepeatOrEscalateTheSameCondition() {
        enable();
        assertEquals(2, analyzer.analyze(sample(11, 6, 1)).size());
        assertTrue(analyzer.analyze(sample(12, 7, 1)).isEmpty());
        assertTrue(analyzer.analyze(sample(40, 20, 1)).isEmpty());
        assertEquals(2, analyzer.getHistory().size());
    }

    @Test
    void clearingAndRecrossingAThresholdAllowsAnotherAlert() {
        enable();
        analyzer.analyze(sample(11, 6, 1));
        assertTrue(analyzer.analyze(sample(10, 6, 1)).isEmpty());
        var downloadAgain = analyzer.analyze(sample(12, 6, 1));
        assertEquals(List.of(TrafficAlert.Type.DOWNLOAD_THRESHOLD),
                downloadAgain.stream().map(TrafficAlert::type).toList());
        analyzer.analyze(sample(12, 5, 1));
        assertEquals(TrafficAlert.Type.UPLOAD_THRESHOLD, analyzer.analyze(sample(12, 7, 1)).getFirst().type());
    }

    @Test
    void inactivityUsesConsecutiveObservedIntervalsAndOnlyEmitsOnce() {
        enable(); // Three seconds of valid zero-traffic intervals are required.
        assertTrue(analyzer.analyze(sample(0, 0, 1)).isEmpty());
        assertTrue(analyzer.analyze(sample(0, 0, 1)).isEmpty());
        var alert = analyzer.analyze(sample(0, 0, 1)).getFirst();
        assertEquals(TrafficAlert.Type.INACTIVITY, alert.type());
        assertEquals(TrafficAlert.Severity.INFO, alert.severity());
        assertTrue(alert.description().contains("does not imply Internet disconnection"));
        assertTrue(analyzer.analyze(sample(0, 0, 10)).isEmpty());
    }

    @Test
    void trafficResumingResetsTheInactivityTimerAndRearmsIt() {
        enable();
        analyzer.analyze(sample(0, 0, 2));
        analyzer.analyze(sample(0, 1, 1));
        assertTrue(analyzer.analyze(sample(0, 0, 2)).isEmpty());
        assertEquals(TrafficAlert.Type.INACTIVITY, analyzer.analyze(sample(0, 0, 1)).getFirst().type());
        analyzer.analyze(sample(1, 0, 1));
        assertEquals(TrafficAlert.Type.INACTIVITY, analyzer.analyze(sample(0, 0, 3)).getFirst().type());
    }

    @Test
    void aDuplicateSnapshotCannotAdvanceIdleTimeOrCreateRepeatedAlerts() {
        enable();
        var zero = sample(0, 0, 1);
        for (int count = 0; count < 100; count++) {
            assertTrue(analyzer.analyze(zero).isEmpty());
        }
        assertTrue(analyzer.analyze(sample(0, 0, 1)).isEmpty());
        assertEquals(1, analyzer.analyze(sample(0, 0, 1)).size());
    }

    @Test
    void disabledAlertsDoNotAccumulateConditionsOrHistory() {
        assertTrue(analyzer.analyze(sample(500, 500, 10)).isEmpty());
        assertTrue(analyzer.analyze(sample(0, 0, 100)).isEmpty());
        assertTrue(analyzer.getHistory().isEmpty());
        enable();
        assertEquals(2, analyzer.analyze(sample(500, 500, 1)).size());
        analyzer.configure(new AlertSettings(false, 10, 5, 3));
        assertTrue(analyzer.analyze(sample(500, 500, 1)).isEmpty());
        assertEquals(2, analyzer.getHistory().size()); // Disabling does not erase history.
    }

    @ParameterizedTest
    @EnumSource(value = NetworkStatisticsSnapshot.State.class,
            names = {"STARTING", "BASELINE", "COUNTER_RESET", "INVALID_INTERVAL", "UNAVAILABLE", "STOPPED"})
    void unavailableReadingsNeverGenerateAlertsOrCountTowardInactivity(NetworkStatisticsSnapshot.State state) {
        enable();
        analyzer.analyze(sample(0, 0, 2));
        assertTrue(analyzer.analyze(snapshot(state, 100, 100, 10, 6)).isEmpty());
        assertTrue(analyzer.analyze(sample(0, 0, 2)).isEmpty());
        assertEquals(1, analyzer.analyze(sample(0, 0, 1)).size());
    }

    @Test
    void unknownReadingsDoNotProveAnActiveThresholdHasCleared() {
        enable();
        analyzer.analyze(sample(11, 6, 1));
        analyzer.analyze(snapshot(NetworkStatisticsSnapshot.State.UNAVAILABLE, 0, 0, 1, 6));
        assertTrue(analyzer.analyze(sample(11, 6, 1)).isEmpty());
        analyzer.analyze(sample(1, 1, 1));
        assertEquals(2, analyzer.analyze(sample(11, 6, 1)).size());
    }

    @Test
    void zeroLengthIntervalsAreNotInterpretedAsIdleTraffic() {
        enable();
        assertTrue(analyzer.analyze(sample(100, 100, 0)).isEmpty());
        assertTrue(analyzer.analyze(sample(0, 0, 0)).isEmpty());
    }

    @Test
    void historyIsNewestFirstImmutableAndBoundedToOneHundredAlerts() {
        enable();
        for (int index = 0; index < 150; index++) {
            analyzer.analyze(sample(1, 1, 1));
            analyzer.analyze(sample(11 + index, 1, 1));
        }
        var history = analyzer.getHistory();
        assertEquals(100, history.size());
        assertTrue(history.getFirst().description().contains("160.000"));
        assertThrows(UnsupportedOperationException.class, history::clear);
    }

    @Test
    void clearingHistoryDoesNotRearmAnActiveCondition() {
        enable();
        analyzer.analyze(sample(11, 6, 1));
        analyzer.clearHistory();
        assertTrue(analyzer.getHistory().isEmpty());
        assertTrue(analyzer.analyze(sample(11, 6, 1)).isEmpty());
        analyzer.analyze(sample(1, 1, 1));
        assertEquals(2, analyzer.analyze(sample(11, 6, 1)).size());
    }

    @Test
    void adapterOrSessionChangesResetConditionsButKeepHistory() {
        enable();
        analyzer.analyze(sample(11, 1, 1));
        assertEquals(1, analyzer.analyze(snapshot(NetworkStatisticsSnapshot.State.VALID, 11, 1, 1, 7)).size());
        analyzer.resetSession();
        assertEquals(1, analyzer.analyze(sample(11, 1, 1)).size());
        assertEquals(3, analyzer.getHistory().size());
    }

    @Test
    void settingsAreValidatedAndApplyingIdenticalSettingsDoesNotRearmConditions() {
        assertThrows(IllegalArgumentException.class, () -> new AlertSettings(true, 0, 1, 30));
        assertThrows(IllegalArgumentException.class, () -> new AlertSettings(true, Double.NaN, 1, 30));
        assertThrows(IllegalArgumentException.class, () -> new AlertSettings(true, 1, Double.POSITIVE_INFINITY, 30));
        assertThrows(IllegalArgumentException.class, () -> new AlertSettings(true, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new AlertSettings(true, 1, 1, 86_401));
        enable();
        analyzer.analyze(sample(11, 1, 1));
        enable();
        assertTrue(analyzer.analyze(sample(11, 1, 1)).isEmpty());
    }

    private void enable() {
        analyzer.configure(new AlertSettings(true, 10, 5, 3));
    }

    private NetworkStatisticsSnapshot sample(double downloadMbps, double uploadMbps, long seconds) {
        return snapshot(NetworkStatisticsSnapshot.State.VALID, downloadMbps, uploadMbps, seconds, 6);
    }

    private NetworkStatisticsSnapshot snapshot(NetworkStatisticsSnapshot.State state,
            double downloadMbps, double uploadMbps, long seconds, int index) {
        return new NetworkStatisticsSnapshot(new NetworkAdapter(index, "adapter", "Test adapter", true, false),
                "Wi-Fi", "Up", OptionalLong.of(100), OptionalLong.of(200), 0, 0, seconds * 1_000_000_000L,
                OptionalDouble.of(downloadMbps * 1_000_000 / 8),
                OptionalDouble.of(uploadMbps * 1_000_000 / 8), state, "Controlled test reading");
    }
}
