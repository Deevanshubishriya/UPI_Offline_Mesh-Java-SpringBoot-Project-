package com.demo.upimesh.model;

import jakarta.persistence.*;

import java.math.BigDecimal;

/**
 * A bank account. @Version enables JPA optimistic locking: every UPDATE
 * checks the version column, so two concurrent debits against the same
 * row cannot silently stomp on each other (defense in depth on top of
 * the idempotency cache and @Transactional in SettlementService).
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String vpa; // virtual payment address, e.g. "alice@mesh"

    @Column(nullable = false)
    private String displayName;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Version
    private Long version;

    protected Account() {
        // JPA
    }

    public Account(String vpa, String displayName, BigDecimal balance) {
        this.vpa = vpa;
        this.displayName = displayName;
        this.balance = balance;
    }

    public Long getId() {
        return id;
    }

    public String getVpa() {
        return vpa;
    }

    public String getDisplayName() {
        return displayName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }

    public Long getVersion() {
        return version;
    }
}
