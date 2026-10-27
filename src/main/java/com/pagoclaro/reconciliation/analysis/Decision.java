package com.pagoclaro.reconciliation.analysis;

import java.math.BigDecimal;
import java.util.List;

/**
 * Decision pura del analizador.
 *
 * @param verdict            que hacer con el pago
 * @param reasonCode         motivo estable
 * @param detail             detalle determinista para auditoria (nunca contiene secretos)
 * @param targetStatus       nuevo estado del pago, o null si no cambia
 * @param debitIdsToReverse  ids de debitos duplicados a reversar (vacio si no aplica)
 * @param netDebit           debito neto actual segun el ledger (DEBIT - REVERSAL)
 * @param expectedNetDebit   debito neto esperado segun la evidencia, o null si no se puede determinar
 */
public record Decision(
        Verdict verdict,
        ReasonCode reasonCode,
        String detail,
        String targetStatus,
        List<Long> debitIdsToReverse,
        BigDecimal netDebit,
        BigDecimal expectedNetDebit) {

    public Decision {
        debitIdsToReverse = debitIdsToReverse == null ? List.of() : List.copyOf(debitIdsToReverse);
    }
}
