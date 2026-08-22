package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Simulates the Bluetooth mesh on a single laptop. Five virtual phones:
 *
 *   phone-alice     — the sender, starts with the injected packet
 *   phone-stranger1 — intermediate hop, no internet
 *   phone-stranger2 — intermediate hop, no internet
 *   phone-stranger3 — intermediate hop, no internet
 *   phone-bridge    — the only device with hasInternet=true; this is the
 *                      one that "walks outside" and uploads to the backend
 *
 * A gossip round = every device that currently holds a packet broadcasts a
 * TTL-decremented copy of it to every other device ("Bluetooth range" is
 * simplified to "everyone, every round" for the demo). In real life this
 * would happen opportunistically as phones pass within BLE range of each
 * other; here it happens whenever you click the button.
 */
@Service
public class MeshSimulatorService {

    private final Map<String, VirtualDevice> devices = new LinkedHashMap<>();
    private final BridgeIngestionService bridgeIngestionService;

    public MeshSimulatorService(BridgeIngestionService bridgeIngestionService) {
        this.bridgeIngestionService = bridgeIngestionService;
        seedDevices();
    }

    private void seedDevices() {
        register(new VirtualDevice("phone-alice", false));
        register(new VirtualDevice("phone-stranger1", false));
        register(new VirtualDevice("phone-stranger2", false));
        register(new VirtualDevice("phone-stranger3", false));
        register(new VirtualDevice("phone-bridge", true));
    }

    private void register(VirtualDevice device) {
        devices.put(device.getId(), device);
    }

    /** Hands a freshly-encrypted packet to the origin device (the sender's own phone). */
    public void injectPacket(String originDeviceId, MeshPacket packet) {
        VirtualDevice origin = devices.get(originDeviceId);
        if (origin == null) {
            throw new IllegalArgumentException("Unknown device: " + originDeviceId);
        }
        origin.receive(packet);
    }

    /**
     * One gossip round: every device broadcasts every packet it holds (TTL-1)
     * to every other device. A packet whose TTL would go below zero simply
     * isn't forwarded further — it stays wherever it already is.
     */
    public void gossipRound() {
        // Snapshot first so a device doesn't immediately re-broadcast a packet
        // it received in this same round.
        Map<String, List<MeshPacket>> snapshot = new LinkedHashMap<>();
        for (VirtualDevice device : devices.values()) {
            snapshot.put(device.getId(), new ArrayList<>(device.getPackets().values()));
        }

        for (Map.Entry<String, List<MeshPacket>> entry : snapshot.entrySet()) {
            String fromDeviceId = entry.getKey();
            for (MeshPacket packet : entry.getValue()) {
                MeshPacket forwarded = packet.forwardedCopy();
                if (forwarded == null) {
                    continue; // TTL exhausted, packet stops here
                }
                for (VirtualDevice neighbour : devices.values()) {
                    if (!neighbour.getId().equals(fromDeviceId)) {
                        neighbour.receive(forwarded);
                    }
                }
            }
        }
    }

    /**
     * Every device with hasInternet=true "walks outside" and uploads every
     * packet it's carrying to the backend, in parallel — this is what lets
     * the duplicate-storm (README Problem 2) actually happen when more than
     * one bridge holds the same packet.
     */
    public List<IngestResult> flushBridges() {
        List<VirtualDevice> bridges = devices.values().stream()
                .filter(VirtualDevice::hasInternet)
                .collect(Collectors.toList());

        List<CompletableFuture<List<IngestResult>>> futures = new ArrayList<>();
        for (VirtualDevice bridge : bridges) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                List<IngestResult> results = new ArrayList<>();
                for (MeshPacket packet : bridge.getPackets().values()) {
                    results.add(bridgeIngestionService.ingest(packet));
                }
                bridge.clearPackets();
                return results;
            }));
        }

        List<IngestResult> allResults = new ArrayList<>();
        for (CompletableFuture<List<IngestResult>> future : futures) {
            allResults.addAll(future.join());
        }
        return allResults;
    }

    public void reset() {
        for (VirtualDevice device : devices.values()) {
            device.clearPackets();
        }
    }

    public Map<String, VirtualDevice> getDevices() {
        return devices;
    }
}
