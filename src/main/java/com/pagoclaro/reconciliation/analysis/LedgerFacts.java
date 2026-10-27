package com.pagoclaro.reconciliation.analysis;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Un movimiento del ledger tal como lo ve el analizador (sin depender de JPA). */
public record LedgerFacts(Long id, String type, BigDecimal amount, Long relatedEntryId, LocalDateTime createdAt) {

    public static final String DEBIT = "DEBIT";
    public static final String REVERSAL = "REVERSAL";

    public boolean isDebit() {
        return DEBIT.equals(type);
    }

    public boolean isReversal() {
        return REVERSAL.equals(type);
    }
}
