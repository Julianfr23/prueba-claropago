package com.pagoclaro.reconciliation.service;

/** Resultado funcional visible para soporte. */
public enum Outcome {
    /** Nada que corregir (incluye repeticiones de una conciliacion ya resuelta). */
    CONSISTENT,
    /** Solo diagnostico: existe una correccion segura que la conciliacion aplicaria. */
    CORRECTABLE,
    /** La conciliacion aplico correcciones seguras y dejo el pago consistente. */
    RECONCILED,
    /** No se modifico el pago ni el ledger: el caso requiere decision humana. */
    MANUAL_REVIEW
}
