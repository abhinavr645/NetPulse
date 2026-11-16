package com.netpulse.monitoring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class WindowsAdapterStatisticsTest {
    private static final String PREFIX = "NETPULSE|6|{82CF1986-9365-4F6C-BFD1-79F48A46B43F}|";

    @Test
    void parsesUnicodeNamesAndLargeCumulativeCounters() throws IOException {
        String name = "Wi-Fi | कॉलेज";
        String encoded = Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
        var counters = WindowsAdapterStatistics.parse(PREFIX + encoded + "|Up|5000000000|3000000000", 6);
        assertEquals(name, counters.name());
        assertEquals("Up", counters.status());
        assertEquals(5_000_000_000L, counters.receivedBytes());
        assertEquals(3_000_000_000L, counters.sentBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Access denied", "NETPULSE|6|missing", "NETPULSE|6|bad-guid|V2ktRmk=|Up|1|2",
            "NETPULSE|6|{82CF1986-9365-4F6C-BFD1-79F48A46B43F}|***|Up|1|2"})
    void rejectsMalformedPowerShellOutput(String output) {
        assertThrows(IOException.class, () -> WindowsAdapterStatistics.parse(output, 6));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1|10", "1|-10", "not-a-number|10", "|10", "9223372036854775808|10"})
    void rejectsInvalidOrOutOfRangeCounters(String values) {
        assertThrows(IOException.class,
                () -> WindowsAdapterStatistics.parse(PREFIX + "V2ktRmk=|Up|" + values, 6));
    }

    @Test
    void rejectsADifferentInterfaceIndex() {
        assertThrows(IOException.class,
                () -> WindowsAdapterStatistics.parse(PREFIX + "V2ktRmk=|Up|1|2", 7));
    }
}
