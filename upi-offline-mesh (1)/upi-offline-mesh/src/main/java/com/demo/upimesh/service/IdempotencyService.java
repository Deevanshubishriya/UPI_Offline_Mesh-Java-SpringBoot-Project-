package com.demo.upimesh.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The duplicate-storm defense. See README "Problem 2".
 *
 * {@code ConcurrentHashMap.putIfAbsent} is atomic: if a hundred threads call
 * claim() with the same hash at the same nanosecond, exactly one gets back
 * {@code true} (the first claimer) and every other caller gets {@code false}
 * immediately, before any decryption or settlement work happens.
 *
 * In production this map becomes Redis: {@code SET key NX EX 86400}. Same
 * semantics, just shared across replicas instead of living in one JVM.
 */
@Service
public class IdempotencyService {

    private final ConcurrentHashMap<String, Instant> seen = new ConcurrentHashMap<>();

    @Value("${app.idempotency.ttl-hours:24}")
    private long ttlHours;

    /** Returns true if this hash has not been seen before (i.e. this caller "wins" and may proceed). */
    public boolean claim(String packetHash) {
        Instant previous = seen.putIfAbsent(packetHash, Instant.now());
        return previous == null;
    }

    public boolean hasSeen(String packetHash) {
        return seen.containsKey(packetHash);
    }

    public int size() {
        return seen.size();
    }

    public void reset() {
        seen.clear();
    }

    /** Periodic eviction so the map doesn't grow forever — mirrors Redis's own EX expiry. */
    @Scheduled(fixedRate = 15 * 60 * 1000) // every 15 minutes
    public void evictExpired() {
        Instant cutoff = Instant.now().minusSeconds(ttlHours * 3600);
        seen.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
    }
}
