package com.pagoclaro.reconciliation.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Respuesta de la API de soporte. Deliberadamente NO incluye ids internos de ledger/eventos, SQL,
 * nombres de tablas ni trazas: solo lo necesario para decidir el siguiente paso operativo.
 *
 * @param externalRef     referencia externa consultada
 * @param outcome         resultado funcional
 * @param reasonCode      codigo de motivo estable (ver ReasonCode)
 * @param message         explicacion corta y segura
 * @param changed         true si esta llamada modifico el pago o el ledger
 * @param paymentStatus   estado del pago DESPUES de la operacion
 * @param expectedAmount  monto del pago (debito neto esperado cuando la evidencia es de aprobacion)
 * @param netDebitBefore  debito neto antes de la operacion
 * @param netDebitAfter   debito neto despues (o el previsto, en diagnostico)
 * @param actions         acciones aplicadas (o previstas, con prefijo PLANNED_ en diagnostico)
 */
public record ReconciliationResult(
        String externalRef,
        Outcome outcome,
        String reasonCode,
        String message,
        boolean changed,
        String paymentStatus,
        BigDecimal expectedAmount,
        BigDecimal netDebitBefore,
        BigDecimal netDebitAfter,
        List<String> actions) {
}
