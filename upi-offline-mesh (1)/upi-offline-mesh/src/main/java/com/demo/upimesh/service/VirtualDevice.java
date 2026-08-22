package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simulated phone. It holds whatever packets have reached it via gossip
 * (or that it originated), keyed by packetId so re-receiving the same
 * packet from two neighbours in one round doesn't duplicate it on-device.
 *
 * hasInternet models whether this particular phone currently has a data
 * connection — in the real system this flips on/off as someone walks in
 * and out of range of a cell tower or Wi-Fi; here it's just a fixed flag
 * per seeded device (see MeshSimulatorService).
 */
public class VirtualDevice {

    private final String id;
    private final boolean hasInternet;
    private final Map<String, MeshPacket> packets = new ConcurrentHashMap<>();

    public VirtualDevice(String id, boolean hasInternet) {
        this.id = id;
        this.hasInternet = hasInternet;
    }

    public String getId() {
        return id;
    }

    public boolean hasInternet() {
        return hasInternet;
    }

    public void receive(MeshPacket packet) {
        packets.put(packet.getPacketId(), packet);
    }

    public Map<String, MeshPacket> getPackets() {
        return new LinkedHashMap<>(packets);
    }

    public int packetCount() {
        return packets.size();
    }

    public void clearPackets() {
        packets.clear();
    }
}
