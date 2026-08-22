package com.demo.upimesh.service;

import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Owns the ledger. One method, one transaction: debit sender, credit
 * receiver, write the ledger row, or none of the above.
 *
 * Note problem #1/#2 from the README's "Honest limitations" section: this
 * only runs *after* the packet has already reached the backend, so it can
 * only tell you the truth about the sender's balance at settlement time —
 * it has no way to stop a sender from promising money offline that isn't
 * there by the time the packet arrives.
 */
@Service
public class SettlementService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public SettlementService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public Transaction settle(PaymentInstruction instruction, String packetHash) {
        Account sender = accountRepository.findByVpa(instruction.getSenderVpa())
                .orElseThrow(() -> new IllegalArgumentException("Unknown sender VPA: " + instruction.getSenderVpa()));
        Account receiver = accountRepository.findByVpa(instruction.getReceiverVpa())
                .orElseThrow(() -> new IllegalArgumentException("Unknown receiver VPA: " + instruction.getReceiverVpa()));

        if (sender.getBalance().compareTo(instruction.getAmount()) < 0) {
            Transaction rejected = new Transaction(
                    sender.getVpa(), receiver.getVpa(), instruction.getAmount(),
                    packetHash, Instant.now(), Transaction.Status.REJECTED,
                    "Insufficient funds at settlement time");
            return transactionRepository.save(rejected);
        }

        sender.setBalance(sender.getBalance().subtract(instruction.getAmount()));
        receiver.setBalance(receiver.getBalance().add(instruction.getAmount()));
        // @Version on Account means these two saves will fail fast (OptimisticLockException)
        // if some other transaction touched either row concurrently — defense in depth
        // behind the idempotency cache and the unique index on packetHash.
        accountRepository.save(sender);
        accountRepository.save(receiver);

        Transaction settled = new Transaction(
                sender.getVpa(), receiver.getVpa(), instruction.getAmount(),
                packetHash, Instant.now(), Transaction.Status.SETTLED, null);
        return transactionRepository.save(settled);
    }
}
