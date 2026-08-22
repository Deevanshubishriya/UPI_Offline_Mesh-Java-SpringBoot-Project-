package com.demo.upimesh;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.*;
import com.demo.upimesh.service.BridgeIngestionService;
import com.demo.upimesh.service.IngestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IdempotencyConcurrencyTest {

    @Autowired
    private HybridCryptoService cryptoService;

    @Autowired
    private BridgeIngestionService bridgeIngestionService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        accountRepository.save(new Account("alice@mesh", "Alice", new BigDecimal("2500.00")));
        accountRepository.save(new Account("bob@mesh", "Bob", new BigDecimal("800.00")));
    }

    private MeshPacket buildPacket(BigDecimal amount) {
        PaymentInstruction instruction = PaymentInstruction.createNow("alice@mesh", "bob@mesh", amount, "1234");
        String ciphertext = cryptoService.encrypt(instruction);
        return MeshPacket.newPacket(5, ciphertext);
    }

    @Test
    void encryptDecryptRoundTrip() {
        PaymentInstruction original = PaymentInstruction.createNow("alice@mesh", "bob@mesh", new BigDecimal("250.00"), "1234");
        String ciphertext = cryptoService.encrypt(original);

        PaymentInstruction decrypted = cryptoService.decrypt(ciphertext);

        assertEquals(original.getSenderVpa(), decrypted.getSenderVpa());
        assertEquals(original.getReceiverVpa(), decrypted.getReceiverVpa());
        assertEquals(0, original.getAmount().compareTo(decrypted.getAmount()));
        assertEquals(original.getNonce(), decrypted.getNonce());
        assertEquals(original.getSignedAt(), decrypted.getSignedAt());
    }

    @Test
    void tamperedCiphertextIsRejected() {
        MeshPacket packet = buildPacket(new BigDecimal("300.00"));

        // Flip one byte in the middle of the Base64 ciphertext to simulate a
        // malicious (or just corrupted) intermediate hop.
        char[] chars = packet.getCiphertext().toCharArray();
        int mid = chars.length / 2;
        chars[mid] = chars[mid] == 'A' ? 'B' : 'A';
        MeshPacket tampered = new MeshPacket(packet.getPacketId(), packet.getTtl(), packet.getCreatedAt(), new String(chars));

        IngestResult result = bridgeIngestionService.ingest(tampered);

        assertEquals(IngestResult.Outcome.INVALID, result.getOutcome());
    }

    @Test
    void singlePacketDeliveredByThreeBridgesSettlesExactlyOnce() throws InterruptedException {
        MeshPacket packet = buildPacket(new BigDecimal("500.00"));
        BigDecimal aliceBalanceBefore = accountRepository.findByVpa("alice@mesh").orElseThrow().getBalance();

        int deliveries = 3;
        ExecutorService pool = Executors.newFixedThreadPool(deliveries);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<IngestResult>> futures = new java.util.ArrayList<>();

        for (int i = 0; i < deliveries; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return bridgeIngestionService.ingest(packet); // byte-identical ciphertext each time
            }));
        }
        startGate.countDown(); // release all three threads at once
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        AtomicInteger settled = new AtomicInteger();
        AtomicInteger duplicates = new AtomicInteger();
        for (Future<IngestResult> f : futures) {
            IngestResult result = getResult(f);
            if (result.getOutcome() == IngestResult.Outcome.SETTLED) settled.incrementAndGet();
            if (result.getOutcome() == IngestResult.Outcome.DUPLICATE_DROPPED) duplicates.incrementAndGet();
        }

        assertEquals(1, settled.get(), "Exactly one delivery should settle");
        assertEquals(deliveries - 1, duplicates.get(), "The rest should be dropped as duplicates");

        BigDecimal aliceBalanceAfter = accountRepository.findByVpa("alice@mesh").orElseThrow().getBalance();
        assertEquals(0, aliceBalanceBefore.subtract(new BigDecimal("500.00")).compareTo(aliceBalanceAfter),
                "Sender should be debited exactly once, not three times");
    }

    private static IngestResult getResult(Future<IngestResult> f) {
        try {
            return f.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
