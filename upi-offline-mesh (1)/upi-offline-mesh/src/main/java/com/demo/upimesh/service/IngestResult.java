package com.demo.upimesh.service;

public class IngestResult {

    public enum Outcome {
        SETTLED,
        REJECTED,
        DUPLICATE_DROPPED,
        INVALID
    }

    private final Outcome outcome;
    private final String packetHash;
    private final String reason;
    private final Long transactionId;

    private IngestResult(Outcome outcome, String packetHash, String reason, Long transactionId) {
        this.outcome = outcome;
        this.packetHash = packetHash;
        this.reason = reason;
        this.transactionId = transactionId;
    }

    public static IngestResult settled(String packetHash, Long transactionId) {
        return new IngestResult(Outcome.SETTLED, packetHash, null, transactionId);
    }

    public static IngestResult rejected(String packetHash, String reason, Long transactionId) {
        return new IngestResult(Outcome.REJECTED, packetHash, reason, transactionId);
    }

    public static IngestResult duplicateDropped(String packetHash) {
        return new IngestResult(Outcome.DUPLICATE_DROPPED, packetHash, "Packet already settled or in flight", null);
    }

    public static IngestResult invalid(String packetHash, String reason) {
        return new IngestResult(Outcome.INVALID, packetHash, reason, null);
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getPacketHash() {
        return packetHash;
    }

    public String getReason() {
        return reason;
    }

    public Long getTransactionId() {
        return transactionId;
    }
}
