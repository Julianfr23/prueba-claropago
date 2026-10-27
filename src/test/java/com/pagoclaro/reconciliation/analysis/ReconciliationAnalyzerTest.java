package com.pagoclaro.reconciliation.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pruebas unitarias de las reglas puras (sin Spring ni base de datos).
 * Cada caso documenta una decision de negocio del enunciado.
 */
class ReconciliationAnalyzerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 13, 9, 41, 22);

    private final ReconciliationAnalyzer analyzer = new ReconciliationAnalyzer();

    // ---------------- helpers ----------------

    private static PaymentFacts pay(String status, String amount) {
        return new PaymentFacts(status, new BigDecimal(amount));
    }

    private static LedgerFacts debit(long id, String amount, int secondsAfterT0) {
        return new LedgerFacts(id, "DEBIT", new BigDecimal(amount), null, T0.plusSeconds(secondsAfterT0));
    }

    private static LedgerFacts reversal(long id, String amount, long relatedTo, int secondsAfterT0) {
        return new LedgerFacts(id, "REVERSAL", new BigDecimal(amount), relatedTo, T0.plusSeconds(secondsAfterT0));
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertTrue(actual != null && new BigDecimal(expected).compareTo(actual) == 0,
                "esperaba " + expected + " pero fue " + actual);
    }

    // ---------------- casos de muestra del enunciado ----------------

    @Test
    @DisplayName("PAY-AX91: aprobado por el proveedor, debito correcto, estado PENDING => solo se corrige el estado")
    void approvedButPendingOnlyFixesStatus() {
        Decision d = analyzer.analyze(pay("PENDING", "125000"),
                List.of("APPROVED"), List.of(debit(5001, "125000", 0)));

        assertEquals(Verdict.CORRECTABLE, d.verdict());
        assertEquals(ReasonCode.STATUS_ALIGNED_TO_APPROVAL, d.reasonCode());
        assertEquals("CONFIRMED", d.targetStatus());
        assertTrue(d.debitIdsToReverse().isEmpty(), "no debe tocar el ledger");
    }

    @Test
    @DisplayName("PAY-BX17: dos debitos tras reintento => se conserva el mas antiguo y se reversa el posterior")
    void duplicateDebitReversesOnlyTheLaterOne() {
        Decision d = analyzer.analyze(pay("CONFIRMED", "89000"),
                List.of("APPROVED", "APPROVED"),
                List.of(debit(5003, "89000", 3), debit(5002, "89000", 0))); // desordenados a proposito

        assertEquals(Verdict.CORRECTABLE, d.verdict());
        assertEquals(ReasonCode.DUPLICATE_DEBIT_REVERSED, d.reasonCode());
        assertEquals(List.of(5003L), d.debitIdsToReverse());
        assertNull(d.targetStatus(), "el estado ya era CONFIRMED");
        assertAmount("178000", d.netDebit());
        assertAmount("89000", d.expectedNetDebit());
    }

    @Test
    @DisplayName("PAY-CQ22: APPROVED + DECLINED => revision manual, sin correcciones")
    void contradictoryEvidenceGoesToManualReview() {
        Decision d = analyzer.analyze(pay("PENDING", "54000"),
                List.of("APPROVED", "DECLINED"), List.of());

        assertEquals(Verdict.MANUAL_REVIEW, d.verdict());
        assertEquals(ReasonCode.EVIDENCE_CONTRADICTORY, d.reasonCode());
        assertEquals("approvedEvents=1, declinedEvents=1", d.detail());
        assertNull(d.targetStatus());
        assertTrue(d.debitIdsToReverse().isEmpty());
    }

    @Test
    @DisplayName("PAY-DK44: caso sano de control => sin cambios")
    void healthyPaymentIsConsistent() {
        Decision d = analyzer.analyze(pay("CONFIRMED", "31000"),
                List.of("APPROVED"), List.of(debit(5004, "31000", 0)));

        assertEquals(Verdict.CONSISTENT, d.verdict());
        assertEquals(ReasonCode.ALREADY_CONSISTENT, d.reasonCode());
    }

    // ---------------- idempotencia a nivel de reglas ----------------

    @Test
    @DisplayName("Idempotencia: tras el reverso, el mismo analisis converge a CONSISTENT")
    void afterReversalAnalysisConverges() {
        List<LedgerFacts> before = List.of(debit(5002, "89000", 0), debit(5003, "89000", 3));
        Decision first = analyzer.analyze(pay("CONFIRMED", "89000"), List.of("APPROVED", "APPROVED"), before);
        assertEquals(Verdict.CORRECTABLE, first.verdict());

        List<LedgerFacts> after = List.of(debit(5002, "89000", 0), debit(5003, "89000", 3),
                reversal(100000, "89000", 5003, 600));
        Decision second = analyzer.analyze(pay("CONFIRMED", "89000"), List.of("APPROVED", "APPROVED"), after);

        assertEquals(Verdict.CONSISTENT, second.verdict());
        assertAmount("89000", second.netDebit());
    }

    // ---------------- bordes y errores ----------------

    @Test
    @DisplayName("Tres debitos: se conserva uno y se reversan los otros dos")
    void tripleDebitReversesTwo() {
        Decision d = analyzer.analyze(pay("CONFIRMED", "10"), List.of("APPROVED"),
                List.of(debit(1, "10", 0), debit(2, "10", 1), debit(3, "10", 2)));

        assertEquals(List.of(2L, 3L), d.debitIdsToReverse());
    }

    @Test
    @DisplayName("Reverso parcial existente => ledger anomalo, revision manual (no se compensa a ciegas)")
    void partialReversalIsAnomalous() {
        Decision d = analyzer.analyze(pay("CONFIRMED", "89000"), List.of("APPROVED", "APPROVED"),
                List.of(debit(1, "89000", 0), debit(2, "89000", 1), reversal(3, "40000", 2, 5)));

        assertEquals(Verdict.MANUAL_REVIEW, d.verdict());
        assertEquals(ReasonCode.LEDGER_ANOMALY, d.reasonCode());
    }

    @Test
    @DisplayName("Reverso sin debito relacionado valido => ledger anomalo")
    void unlinkedReversalIsAnomalous() {
        LedgerFacts orphan = new LedgerFacts(9L, "REVERSAL", new BigDecimal("89000"), null, T0.plusSeconds(9));
        Decision d = analyzer.analyze(pay("CONFIRMED", "89000"), List.of("APPROVED"),
                List.of(debit(1, "89000", 0), orphan));

        assertEquals(ReasonCode.LEDGER_ANOMALY, d.reasonCode());
    }

    @Test
    @DisplayName("Un debito reversado dos veces => ledger anomalo")
    void doubleReversalIsAnomalous() {
        Decision d = analyzer.analyze(pay("CONFIRMED", "5"), List.of("APPROVED"),
                List.of(debit(1, "5", 0), reversal(2, "5", 1, 1), reversal(3, "5", 1, 2)));

        assertEquals(ReasonCode.LEDGER_ANOMALY, d.reasonCode());
    }

    @Test
    @DisplayName("Monto del debito distinto al del pago => revision manual")
    void debitAmountMismatchGoesToManualReview() {
        Decision d = analyzer.analyze(pay("PENDING", "100"), List.of("APPROVED"), List.of(debit(1, "90", 0)));

        assertEquals(ReasonCode.LEDGER_AMOUNT_MISMATCH, d.reasonCode());
        assertEquals(Verdict.MANUAL_REVIEW, d.verdict());
    }

    @Test
    @DisplayName("Aprobado pero sin ningun debito => no se postea un debito por cuenta propia")
    void approvedWithoutDebitIsManualReview() {
        Decision d = analyzer.analyze(pay("PENDING", "100"), List.of("APPROVED"), List.of());

        assertEquals(ReasonCode.MISSING_DEBIT, d.reasonCode());
    }

    @Test
    @DisplayName("Debito ya reversado en su totalidad (neto 0) y proveedor aprobado => revision manual")
    void fullyReversedDebitWithApprovalIsManualReview() {
        Decision d = analyzer.analyze(pay("CONFIRMED", "100"), List.of("APPROVED"),
                List.of(debit(1, "100", 0), reversal(2, "100", 1, 1)));

        assertEquals(ReasonCode.MISSING_DEBIT, d.reasonCode());
    }

    @Test
    @DisplayName("Solo rechazos, PENDING y sin ledger => se alinea a REJECTED")
    void declinedPendingWithoutLedgerBecomesRejected() {
        Decision d = analyzer.analyze(pay("PENDING", "100"), List.of("DECLINED"), List.of());

        assertEquals(Verdict.CORRECTABLE, d.verdict());
        assertEquals("REJECTED", d.targetStatus());
    }

    @Test
    @DisplayName("Solo rechazos pero con debito neto => revision manual (no se compensa)")
    void declinedWithNetDebitIsManualReview() {
        Decision d = analyzer.analyze(pay("PENDING", "100"), List.of("DECLINED"), List.of(debit(1, "100", 0)));

        assertEquals(ReasonCode.DECLINED_WITH_NET_DEBIT, d.reasonCode());
    }

    @Test
    @DisplayName("CONFIRMED pero el proveedor solo rechazo => revision manual")
    void confirmedButDeclinedIsManualReview() {
        Decision d = analyzer.analyze(pay("CONFIRMED", "100"), List.of("DECLINED"), List.of());

        assertEquals(ReasonCode.CONFIRMED_BUT_DECLINED, d.reasonCode());
    }

    @Test
    @DisplayName("REJECTED pero el proveedor aprobo => revision manual")
    void rejectedButApprovedIsManualReview() {
        Decision d = analyzer.analyze(pay("REJECTED", "100"), List.of("APPROVED"), List.of(debit(1, "100", 0)));

        assertEquals(ReasonCode.REJECTED_BUT_APPROVED, d.reasonCode());
    }

    @Test
    @DisplayName("Sin eventos: PENDING vacio es normal; CONFIRMED sin evidencia es revision manual")
    void noEvidenceCases() {
        assertEquals(Verdict.CONSISTENT,
                analyzer.analyze(pay("PENDING", "100"), List.of(), List.of()).verdict());

        Decision d = analyzer.analyze(pay("CONFIRMED", "100"), List.of(), List.of(debit(1, "100", 0)));
        assertEquals(ReasonCode.NO_PROVIDER_EVIDENCE, d.reasonCode());
    }

    @Test
    @DisplayName("Estados fuera del dominio (pago o evento) => revision manual, nunca excepcion")
    void unknownStatusesAreManualReview() {
        assertEquals(ReasonCode.UNKNOWN_PAYMENT_STATUS,
                analyzer.analyze(pay("WEIRD", "100"), List.of("APPROVED"), List.of()).reasonCode());
        assertEquals(ReasonCode.UNKNOWN_PROVIDER_STATUS,
                analyzer.analyze(pay("PENDING", "100"), List.of("REFUNDED"), List.of()).reasonCode());
    }

    @Test
    @DisplayName("Tipo de movimiento o monto invalido en el ledger => ledger anomalo")
    void invalidLedgerEntryIsAnomalous() {
        LedgerFacts weirdType = new LedgerFacts(1L, "CREDIT", new BigDecimal("100"), null, T0);
        LedgerFacts zeroAmount = new LedgerFacts(2L, "DEBIT", BigDecimal.ZERO, null, T0);

        assertEquals(ReasonCode.LEDGER_ANOMALY,
                analyzer.analyze(pay("CONFIRMED", "100"), List.of("APPROVED"), List.of(weirdType)).reasonCode());
        assertEquals(ReasonCode.LEDGER_ANOMALY,
                analyzer.analyze(pay("CONFIRMED", "100"), List.of("APPROVED"), List.of(zeroAmount)).reasonCode());
    }
}
