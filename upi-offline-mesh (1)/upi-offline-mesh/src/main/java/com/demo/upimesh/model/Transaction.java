package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A row in the settled-transaction ledger. The unique index on packetHash
 * is the last line of defense against double-settlement: even if the
 * in-memory idempotency cache is somehow bypassed (e.g. after a restart in
 * production with a distributed cache outage), the database itself will
 * reject a second INSERT with the same ciphertext hash.
 */
@Entity
@Table(name = "transactions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_transactions_packet_hash", columnNames = "packetHash")
})
public class Transaction {

    public enum Status {
        SETTLED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String senderVpa;

    @Column(nullable = false)
    private String receiverVpa;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, unique = true, length = 64)
    private String packetHash;

    @Column(nullable = false)
    private Instant settledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column
    private String note;

    protected Transaction() {
        // JPA
    }

    public Transaction(String senderVpa, String receiverVpa, BigDecimal amount,
                        String packetHash, Instant settledAt, Status status, String note) {
        this.senderVpa = senderVpa;
        this.receiverVpa = receiverVpa;
        this.amount = amount;
        this.packetHash = packetHash;
        this.settledAt = settledAt;
        this.status = status;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public String getSenderVpa() {
        return senderVpa;
    }

    public String getReceiverVpa() {
        return receiverVpa;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getPacketHash() {
        return packetHash;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public Status getStatus() {
        return status;
    }

    public String getNote() {
        return note;
    }
}
