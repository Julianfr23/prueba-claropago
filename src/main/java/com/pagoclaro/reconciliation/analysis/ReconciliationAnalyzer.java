package com.pagoclaro.reconciliation.analysis;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reglas de conciliacion en codigo puro: recibe hechos (estado del pago, estados de los eventos del
 * proveedor y movimientos del ledger) y devuelve una {@link Decision}. No lee ni escribe base de datos,
 * por eso es determinista, facil de probar y es la UNICA fuente de las reglas del lado Java.
 * (El procedimiento PL/SQL sp_reconcile_payment implementa estas mismas reglas.)
 *
 * <p>Principios:
 * <ul>
 *   <li>No se adivina: evidencia contradictoria o insuficiente, o ledger anomalo => MANUAL_REVIEW.</li>
 *   <li>El ledger es inmutable: la unica correccion contable posible es AGREGAR reversos que
 *       referencian el debito duplicado.</li>
 *   <li>Idempotencia por convergencia: el resultado depende solo del estado actual; una vez corregido,
 *       el mismo analisis devuelve CONSISTENT y no hay nada que volver a aplicar.</li>
 * </ul>
 */
public class ReconciliationAnalyzer {

    static final String PENDING = "PENDING";
    static final String CONFIRMED = "CONFIRMED";
    static final String REJECTED = "REJECTED";
    static final String APPROVED = "APPROVED";
    static final String DECLINED = "DECLINED";

    public Decision analyze(PaymentFacts payment, List<String> providerStatuses, List<LedgerFacts> ledger) {
        BigDecimal net = netDebit(ledger);

        int approved = 0;
        int declined = 0;
        int unknownEvents = 0;
        for (String s : providerStatuses) {
            if (APPROVED.equals(s)) {
                approved++;
            } else if (DECLINED.equals(s)) {
                declined++;
            } else {
                unknownEvents++;
            }
        }

        // 1) Valores fuera del dominio: no se interpreta nada.
        if (!isKnownPaymentStatus(payment.status())) {
            return manual(ReasonCode.UNKNOWN_PAYMENT_STATUS, null, net, null);
        }
        if (unknownEvents > 0) {
            return manual(ReasonCode.UNKNOWN_PROVIDER_STATUS, null, net, null);
        }

        // 2) Evidencia contradictoria: NO se resuelve por orden temporal ni por cantidad (seria adivinar).
        if (approved > 0 && declined > 0) {
            String detail = "approvedEvents=" + approved + ", declinedEvents=" + declined;
            return manual(ReasonCode.EVIDENCE_CONTRADICTORY, detail, net, null);
        }

        // 3) Un ledger invalido hace que cualquier calculo de "debito neto" sea poco confiable.
        if (hasLedgerAnomaly(ledger)) {
            return manual(ReasonCode.LEDGER_ANOMALY, null, net, null);
        }

        // 4) Sin eventos del proveedor.
        if (approved == 0 && declined == 0) {
            if (PENDING.equals(payment.status()) && ledger.isEmpty()) {
                return new Decision(Verdict.CONSISTENT, ReasonCode.PENDING_AWAITING_PROVIDER, null,
                        null, List.of(), net, BigDecimal.ZERO);
            }
            return manual(ReasonCode.NO_PROVIDER_EVIDENCE, null, net, null);
        }

        // 5) Solo rechazos.
        if (declined > 0) {
            if (net.signum() != 0) {
                return manual(ReasonCode.DECLINED_WITH_NET_DEBIT, null, net, BigDecimal.ZERO);
            }
            if (REJECTED.equals(payment.status())) {
                return new Decision(Verdict.CONSISTENT, ReasonCode.ALREADY_CONSISTENT, null,
                        null, List.of(), net, BigDecimal.ZERO);
            }
            if (PENDING.equals(payment.status())) {
                return new Decision(Verdict.CORRECTABLE, ReasonCode.STATUS_ALIGNED_TO_DECLINE, null,
                        REJECTED, List.of(), net, BigDecimal.ZERO);
            }
            return manual(ReasonCode.CONFIRMED_BUT_DECLINED, null, net, BigDecimal.ZERO);
        }

        // 6) Solo aprobaciones.
        BigDecimal expected = payment.amount();
        if (REJECTED.equals(payment.status())) {
            return manual(ReasonCode.REJECTED_BUT_APPROVED, null, net, expected);
        }

        List<LedgerFacts> activeDebits = activeDebits(ledger);
        if (activeDebits.isEmpty()) {
            // No se postea un debito "por nuestra cuenta": podria haber una contabilizacion asincrona en curso.
            return manual(ReasonCode.MISSING_DEBIT, null, net, expected);
        }
        for (LedgerFacts d : activeDebits) {
            if (d.amount().compareTo(payment.amount()) != 0) {
                return manual(ReasonCode.LEDGER_AMOUNT_MISMATCH, null, net, expected);
            }
        }

        // Se conserva el debito mas antiguo (el del intento original) y se reversan los posteriores.
        List<Long> duplicates = new ArrayList<>();
        for (int i = 1; i < activeDebits.size(); i++) {
            duplicates.add(activeDebits.get(i).id());
        }
        String targetStatus = PENDING.equals(payment.status()) ? CONFIRMED : null;

        if (duplicates.isEmpty() && targetStatus == null) {
            return new Decision(Verdict.CONSISTENT, ReasonCode.ALREADY_CONSISTENT, null,
                    null, List.of(), net, expected);
        }
        ReasonCode code = duplicates.isEmpty()
                ? ReasonCode.STATUS_ALIGNED_TO_APPROVAL
                : ReasonCode.DUPLICATE_DEBIT_REVERSED;
        return new Decision(Verdict.CORRECTABLE, code, null, targetStatus, duplicates, net, expected);
    }

    // ------------------------------------------------------------------------------------------

    /** Debito neto = suma(DEBIT) - suma(REVERSAL). Tipos desconocidos y montos nulos no suman. */
    static BigDecimal netDebit(List<LedgerFacts> ledger) {
        BigDecimal net = BigDecimal.ZERO;
        for (LedgerFacts e : ledger) {
            if (e.amount() == null) {
                continue;
            }
            if (e.isDebit()) {
                net = net.add(e.amount());
            } else if (e.isReversal()) {
                net = net.subtract(e.amount());
            }
        }
        return net;
    }

    /**
     * Ledger anomalo si hay: tipo desconocido, monto nulo o no positivo, reverso sin debito valido del
     * mismo pago, reverso parcial (monto distinto al del debito) o un debito reversado mas de una vez.
     */
    static boolean hasLedgerAnomaly(List<LedgerFacts> ledger) {
        Map<Long, LedgerFacts> debitsById = new HashMap<>();
        for (LedgerFacts e : ledger) {
            if (!e.isDebit() && !e.isReversal()) {
                return true;
            }
            if (e.amount() == null || e.amount().signum() <= 0) {
                return true;
            }
            if (e.isDebit()) {
                debitsById.put(e.id(), e);
            }
        }
        Set<Long> alreadyReversed = new HashSet<>();
        for (LedgerFacts e : ledger) {
            if (!e.isReversal()) {
                continue;
            }
            LedgerFacts target = e.relatedEntryId() == null ? null : debitsById.get(e.relatedEntryId());
            if (target == null || target.amount().compareTo(e.amount()) != 0) {
                return true;
            }
            if (!alreadyReversed.add(e.relatedEntryId())) {
                return true;
            }
        }
        return false;
    }

    /** Debitos que no han sido reversados, del mas antiguo al mas reciente (created_at, id). */
    static List<LedgerFacts> activeDebits(List<LedgerFacts> ledger) {
        Set<Long> reversedIds = new HashSet<>();
        for (LedgerFacts e : ledger) {
            if (e.isReversal()) {
                reversedIds.add(e.relatedEntryId());
            }
        }
        List<LedgerFacts> active = new ArrayList<>();
        for (LedgerFacts e : ledger) {
            if (e.isDebit() && !reversedIds.contains(e.id())) {
                active.add(e);
            }
        }
        active.sort(Comparator.comparing(LedgerFacts::createdAt).thenComparing(LedgerFacts::id));
        return active;
    }

    private static boolean isKnownPaymentStatus(String status) {
        return PENDING.equals(status) || CONFIRMED.equals(status) || REJECTED.equals(status);
    }

    private static Decision manual(ReasonCode code, String detail, BigDecimal net, BigDecimal expected) {
        String finalDetail = detail != null ? detail : code.auditDetail();
        return new Decision(Verdict.MANUAL_REVIEW, code, finalDetail, null, List.of(), net, expected);
    }
}
