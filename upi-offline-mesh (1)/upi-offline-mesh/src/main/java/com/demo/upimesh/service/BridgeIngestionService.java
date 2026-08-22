package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * THE pipeline. Every packet a bridge node uploads goes through here, in
 * this exact order (see README architecture diagram):
 *
 *   1. hash the ciphertext (SHA-256)
 *   2. atomically claim that hash — first caller wins, everyone else is
 *      a DUPLICATE_DROPPED, before any decryption happens
 *   3. decrypt with the server's RSA private key (RSA-OAEP unwraps the
 *      AES key, AES-GCM decrypts + verifies — tampering surfaces here
 *      as INVALID)
 *   4. freshness check: reject anything older than the configured window
 *      (replay defense)
 *   5. hand off to SettlementService for the actual debit/credit
 */
@Service
public class BridgeIngestionService {

    private final IdempotencyService idempotencyService;
    private final HybridCryptoService cryptoService;
    private final SettlementService settlementService;

    @Value("${app.payment.max-age-hours:24}")
    private long maxAgeHours;

    public BridgeIngestionService(IdempotencyService idempotencyService,
                                   HybridCryptoService cryptoService,
                                   SettlementService settlementService) {
        this.idempotencyService = idempotencyService;
        this.cryptoService = cryptoService;
        this.settlementService = settlementService;
    }

    public IngestResult ingest(MeshPacket packet) {
        String packetHash = cryptoService.hash(packet.getCiphertext());

        // Step 2: atomic claim BEFORE any decryption work.
        boolean firstClaimer = idempotencyService.claim(packetHash);
        if (!firstClaimer) {
            return IngestResult.duplicateDropped(packetHash);
        }

        // Step 3: decrypt. Any tampering (bad GCM tag) or malformed blob lands here.
        PaymentInstruction instruction;
        try {
            instruction = cryptoService.decrypt(packet.getCiphertext());
        } catch (HybridCryptoService.CryptoOperationException e) {
            return IngestResult.invalid(packetHash, e.getMessage());
        }

        // Step 4: freshness / replay window.
        Instant signedAt = Instant.ofEpochMilli(instruction.getSignedAt());
        Duration age = Duration.between(signedAt, Instant.now());
        if (age.isNegative() || age.toHours() > maxAgeHours) {
            return IngestResult.invalid(packetHash,
                    "Packet outside freshness window (signed " + age.toHours() + "h ago)");
        }

        // Step 5: settle.
        try {
            Transaction transaction = settlementService.settle(instruction, packetHash);
            if (transaction.getStatus() == Transaction.Status.SETTLED) {
                return IngestResult.settled(packetHash, transaction.getId());
            } else {
                return IngestResult.rejected(packetHash, transaction.getNote(), transaction.getId());
            }
        } catch (IllegalArgumentException e) {
            // Unknown sender/receiver VPA — treat as invalid, not settled.
            return IngestResult.invalid(packetHash, e.getMessage());
        }
    }
}
