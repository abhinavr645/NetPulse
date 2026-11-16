package com.netpulse.model;

/** Adapter metadata discovered using java.net.NetworkInterface. */
public record NetworkAdapter(int index, String name, String displayName, boolean up, boolean loopback) {
    public NetworkAdapter {
        if (index <= 0 || name == null || name.isBlank() || displayName == null) {
            throw new IllegalArgumentException("A valid interface index and adapter name are required.");
        }
    }
}
