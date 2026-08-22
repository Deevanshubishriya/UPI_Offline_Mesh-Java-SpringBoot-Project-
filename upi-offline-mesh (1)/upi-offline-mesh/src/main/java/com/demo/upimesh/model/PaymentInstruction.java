package com.demo.upimesh.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The plaintext a sender's phone builds before encrypting. This is what
 * {@link com.demo.upimesh.crypto.HybridCryptoService} serializes to JSON,
 * encrypts, and what the backend decrypts on ingestion.
 *
 * signedAt + nonce together defeat replay:
 *  - signedAt lets the backend reject anything older than the freshness
 *    window (see BridgeIngestionService).
 *  - nonce means two *legitimate* payments of the same amount between the
 *    same parties still produce different ciphertexts (and therefore
 *    different hashes), so they are not confused with a replay of one
 *    another.
 */
public class PaymentInstruction {

    private String senderVpa;
    private String receiverVpa;
    private BigDecimal amount;
    private String pinHash;
    private String nonce;
    private long signedAt;

    public PaymentInstruction() {
        // for JSON deserialization
    }

    public PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
                               String pinHash, String nonce, long signedAt) {
        this.senderVpa = senderVpa;
        this.receiverVpa = receiverVpa;
        this.amount = amount;
        this.pinHash = pinHash;
        this.nonce = nonce;
        this.signedAt = signedAt;
    }

    public static PaymentInstruction createNow(String senderVpa, String receiverVpa,
                                                BigDecimal amount, String pin) {
        return new PaymentInstruction(
                senderVpa,
                receiverVpa,
                amount,
                Integer.toHexString(pin.hashCode()), // demo-only "hash", see README limitations
                UUID.randomUUID().toString(),
                System.currentTimeMillis()
        );
    }

    public String getSenderVpa() {
        return senderVpa;
    }

    public void setSenderVpa(String senderVpa) {
        this.senderVpa = senderVpa;
    }

    public String getReceiverVpa() {
        return receiverVpa;
    }

    public void setReceiverVpa(String receiverVpa) {
        this.receiverVpa = receiverVpa;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getPinHash() {
        return pinHash;
    }

    public void setPinHash(String pinHash) {
        this.pinHash = pinHash;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public long getSignedAt() {
        return signedAt;
    }

    public void setSignedAt(long signedAt) {
        this.signedAt = signedAt;
    }
}
