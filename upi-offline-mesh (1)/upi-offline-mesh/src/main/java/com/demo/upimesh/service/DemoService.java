package com.demo.upimesh.service;

import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Two jobs:
 *  1. Seed a handful of demo accounts on startup so the dashboard has
 *     something to show immediately.
 *  2. Play the role of "the sender's phone" for /api/demo/send — build a
 *     PaymentInstruction, encrypt it, wrap it in a MeshPacket, and hand it
 *     to phone-alice in the mesh simulator. Everything from here on is
 *     exactly what a real Android app would do before going offline.
 */
@Service
public class DemoService {

    private final AccountRepository accountRepository;
    private final HybridCryptoService cryptoService;
    private final MeshSimulatorService meshSimulatorService;

    @Value("${app.packet.default-ttl:5}")
    private int defaultTtl;

    public DemoService(AccountRepository accountRepository,
                        HybridCryptoService cryptoService,
                        MeshSimulatorService meshSimulatorService) {
        this.accountRepository = accountRepository;
        this.cryptoService = cryptoService;
        this.meshSimulatorService = meshSimulatorService;
    }

    @PostConstruct
    public void seedAccounts() {
        if (accountRepository.count() > 0) {
            return;
        }
        accountRepository.save(new Account("deevanshu@mesh", "Deevanshu", new BigDecimal("2500.00")));
        accountRepository.save(new Account("ayush@mesh", "Ayush", new BigDecimal("800.00")));
        accountRepository.save(new Account("Carol@mesh", "Carol", new BigDecimal("1200.00")));
        accountRepository.save(new Account("dave@mesh", "Dave", new BigDecimal("50.00")));
    }

    /** Builds, encrypts, and injects a payment as if sent from the origin device's phone. */
    public MeshPacket sendPayment(String senderVpa, String receiverVpa, BigDecimal amount, String pin) {
        PaymentInstruction instruction = PaymentInstruction.createNow(senderVpa, receiverVpa, amount, pin);
        String ciphertext = cryptoService.encrypt(instruction);
        MeshPacket packet = MeshPacket.newPacket(defaultTtl, ciphertext);
        meshSimulatorService.injectPacket("phone-alice", packet);
        return packet;
    }
}
