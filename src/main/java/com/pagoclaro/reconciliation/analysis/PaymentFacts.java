package com.pagoclaro.reconciliation.analysis;

import java.math.BigDecimal;

/** Datos del pago que el analizador necesita (sin depender de JPA). */
public record PaymentFacts(String status, BigDecimal amount) {
}
