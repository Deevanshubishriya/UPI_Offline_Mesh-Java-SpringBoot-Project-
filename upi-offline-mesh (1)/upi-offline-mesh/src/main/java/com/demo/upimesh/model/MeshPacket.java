package com.demo.upimesh.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.UUID;

/**
 * The packet that actually hops device-to-device over the mesh.
 *
 * Every field except {@code ciphertext} is plaintext metadata that any
 * intermediate device needs to route the packet (an id to dedupe on sight,
 * a TTL to know when to stop forwarding, a timestamp). The ciphertext is
 * opaque hybrid-encrypted (RSA-OAEP + AES-256-GCM) payload — no intermediate,
 * however malicious, can read or usefully tamper with it.
 */
public class MeshPacket {

    private final String packetId;
    private int ttl;
    private final long createdAt;
    private final String ciphertext;

    @JsonCreator
    public MeshPacket(@JsonProperty("packetId") String packetId,
                       @JsonProperty("ttl") int ttl,
                       @JsonProperty("createdAt") long createdAt,
                       @JsonProperty("ciphertext") String ciphertext) {
        this.packetId = packetId;
        this.ttl = ttl;
        this.createdAt = createdAt;
        this.ciphertext = ciphertext;
    }

    public static MeshPacket newPacket(int ttl, String ciphertext) {
        return new MeshPacket(UUID.randomUUID().toString(), ttl, System.currentTimeMillis(), ciphertext);
    }

    /** Returns a copy with TTL decremented by one hop, or null if TTL would drop below zero. */
    public MeshPacket forwardedCopy() {
        if (ttl <= 0) {
            return null;
        }
        return new MeshPacket(packetId, ttl - 1, createdAt, ciphertext);
    }

    public String getPacketId() {
        return packetId;
    }

    public int getTtl() {
        return ttl;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MeshPacket)) return false;
        MeshPacket that = (MeshPacket) o;
        return Objects.equals(ciphertext, that.ciphertext);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ciphertext);
    }
}
