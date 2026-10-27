package com.pagoclaro.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Movimiento contable INMUTABLE: todas las columnas son {@code updatable = false}, no hay setters y
 * la aplicacion nunca borra. La unica forma de crear un movimiento nuevo es {@link #reversalOf}.
 */
@Entity
@Table(name = "LEDGER_ENTRY")
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ledger_entry_gen")
    @SequenceGenerator(name = "ledger_entry_gen", sequenceName = "LEDGER_ENTRY_SEQ", allocationSize = 1)
    @Column(name = "ID", updatable = false)
    private Long id;

    @Column(name = "PAYMENT_ID", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "ENTRY_TYPE", nullable = false, updatable = false)
    private String entryType;

    @Column(name = "AMOUNT", nullable = false, updatable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "RELATED_ENTRY_ID", updatable = false)
    private Long relatedEntryId;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected LedgerEntry() {
        // requerido por JPA
    }

    private LedgerEntry(Long paymentId, String entryType, BigDecimal amount, Long relatedEntryId,
                        LocalDateTime createdAt) {
        this.paymentId = paymentId;
        this.entryType = entryType;
        this.amount = amount;
        this.relatedEntryId = relatedEntryId;
        this.createdAt = createdAt;
    }

    /** Reverso por el monto completo del movimiento que corrige, relacionado con el. */
    public static LedgerEntry reversalOf(LedgerEntry debit, LocalDateTime now) {
        return new LedgerEntry(debit.paymentId, "REVERSAL", debit.amount, debit.id, now);
    }

    public Long getId() {
        return id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getEntryType() {
        return entryType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Long getRelatedEntryId() {
        return relatedEntryId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
