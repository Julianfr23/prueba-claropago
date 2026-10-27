package com.pagoclaro.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pagoclaro.reconciliation.support.TestData;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Pruebas de integracion del servicio contra H2 en modo Oracle (esquema de db/schema, incluido el
 * indice unico de reversos). Sin @Transactional a nivel de prueba: cada llamada al servicio hace commit
 * real, igual que en produccion, que es lo que permite probar concurrencia de verdad.
 */
@SpringBootTest
class ReconciliationServiceTest {

    @Autowired
    private ReconciliationService service;

    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        data.clean();
        data.loadSamples();
    }

    // ---------------- comportamiento principal (casos de muestra) ----------------

    @Test
    @DisplayName("PAY-AX91: solo se alinea el estado a CONFIRMED; el ledger no se toca")
    void ax91AlignsStatusOnly() {
        List<Map<String, Object>> ledgerBefore = data.ledgerSnapshot(1);
        long versionBefore = data.rowVersion(1);

        ReconciliationResult r = service.reconcile("PAY-AX91");

        assertThat(r.outcome()).isEqualTo(Outcome.RECONCILED);
        assertThat(r.changed()).isTrue();
        assertThat(r.reasonCode()).isEqualTo("STATUS_ALIGNED_TO_APPROVAL");
        assertThat(data.status(1)).isEqualTo("CONFIRMED");
        assertThat(data.rowVersion(1)).isGreaterThan(versionBefore);
        assertThat(data.ledgerSnapshot(1)).isEqualTo(ledgerBefore);
        assertThat(data.auditCount(1, "RECONCILE_STATUS_UPDATED")).isEqualTo(1);
    }

    @Test
    @DisplayName("PAY-BX17: se agrega UN reverso relacionado al debito duplicado; los existentes no cambian")
    void bx17PostsOneReversalAndKeepsExistingEntriesUntouched() {
        List<Map<String, Object>> ledgerBefore = data.ledgerSnapshot(2);
        assertThat(data.netDebit(2)).isEqualByComparingTo("178000");

        ReconciliationResult r = service.reconcile("PAY-BX17");

        assertThat(r.outcome()).isEqualTo(Outcome.RECONCILED);
        assertThat(r.reasonCode()).isEqualTo("DUPLICATE_DEBIT_REVERSED");
        assertThat(r.netDebitBefore()).isEqualByComparingTo("178000");
        assertThat(r.netDebitAfter()).isEqualByComparingTo("89000");
        assertThat(r.actions()).containsExactly("REVERSAL_POSTED");

        // Debito neto unico por el valor del pago.
        assertThat(data.netDebit(2)).isEqualByComparingTo("89000");

        // Los 2 movimientos originales siguen identicos (inmutables) y hay exactamente 1 nuevo.
        List<Map<String, Object>> after = data.ledgerSnapshot(2);
        assertThat(after).hasSize(3);
        assertThat(after.subList(0, 2)).isEqualTo(ledgerBefore);

        // El reverso corrige el debito POSTERIOR (5003), con su mismo monto y relacionado a el.
        Map<String, Object> reversal = after.get(2);
        assertThat(reversal.get("ENTRY_TYPE")).isEqualTo("REVERSAL");
        assertThat(((Number) reversal.get("RELATED_ENTRY_ID")).longValue()).isEqualTo(5003L);
        assertThat((BigDecimal) reversal.get("AMOUNT")).isEqualByComparingTo("89000");

        assertThat(data.status(2)).isEqualTo("CONFIRMED");
        assertThat(data.auditCount(2, "RECONCILE_REVERSAL_POSTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("PAY-CQ22: evidencia contradictoria => revision manual, sin cambios en pago ni ledger")
    void cq22GoesToManualReviewWithoutChangingAnything() {
        long versionBefore = data.rowVersion(3);

        ReconciliationResult r = service.reconcile("PAY-CQ22");

        assertThat(r.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
        assertThat(r.reasonCode()).isEqualTo("EVIDENCE_CONTRADICTORY");
        assertThat(r.changed()).isFalse();
        assertThat(data.status(3)).isEqualTo("PENDING");
        assertThat(data.rowVersion(3)).isEqualTo(versionBefore);
        assertThat(data.count("ledger_entry", 3)).isZero();   // ni debitos ni compensaciones
        assertThat(data.auditCount(3, "MANUAL_REVIEW_REQUIRED")).isEqualTo(1);
    }

    @Test
    @DisplayName("PAY-DK44: caso sano => sin cambios ni auditoria")
    void dk44HealthyCaseChangesNothing() {
        List<Map<String, Object>> ledgerBefore = data.ledgerSnapshot(4);
        long versionBefore = data.rowVersion(4);

        ReconciliationResult r = service.reconcile("PAY-DK44");

        assertThat(r.outcome()).isEqualTo(Outcome.CONSISTENT);
        assertThat(r.changed()).isFalse();
        assertThat(data.ledgerSnapshot(4)).isEqualTo(ledgerBefore);
        assertThat(data.rowVersion(4)).isEqualTo(versionBefore);
        assertThat(data.count("support_audit", 4)).isZero();
    }

    // ---------------- idempotencia: evidencia verificable ----------------

    @Test
    @DisplayName("Repetir la conciliacion de PAY-BX17 N veces no agrega reversos ni auditoria")
    void repeatingBx17DoesNotDuplicateEffects() {
        service.reconcile("PAY-BX17");
        int ledgerRows = data.count("ledger_entry", 2);
        int audits = data.count("support_audit", 2);
        long version = data.rowVersion(2);

        for (int i = 0; i < 5; i++) {
            ReconciliationResult again = service.reconcile("PAY-BX17");
            assertThat(again.outcome()).isEqualTo(Outcome.CONSISTENT);
            assertThat(again.changed()).isFalse();
            assertThat(again.actions()).isEmpty();
        }

        assertThat(data.count("ledger_entry", 2)).isEqualTo(ledgerRows).isEqualTo(3);
        assertThat(data.count("support_audit", 2)).isEqualTo(audits);
        assertThat(data.rowVersion(2)).isEqualTo(version);
        assertThat(data.netDebit(2)).isEqualByComparingTo("89000");
    }

    @Test
    @DisplayName("Repetir PAY-AX91 no vuelve a actualizar el pago (row_version estable) ni audita de nuevo")
    void repeatingAx91DoesNotTouchPaymentAgain() {
        service.reconcile("PAY-AX91");
        long version = data.rowVersion(1);

        ReconciliationResult again = service.reconcile("PAY-AX91");

        assertThat(again.outcome()).isEqualTo(Outcome.CONSISTENT);
        assertThat(data.rowVersion(1)).isEqualTo(version);
        assertThat(data.count("support_audit", 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("Repetir un caso de revision manual (PAY-CQ22) no duplica el registro de auditoria")
    void repeatingManualReviewDoesNotDuplicateAudit() {
        for (int i = 0; i < 4; i++) {
            assertThat(service.reconcile("PAY-CQ22").outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
        }
        assertThat(data.auditCount(3, "MANUAL_REVIEW_REQUIRED")).isEqualTo(1);
        assertThat(data.count("ledger_entry", 3)).isZero();
    }

    // ---------------- concurrencia ----------------

    @Test
    @DisplayName("Concurrencia: 6 conciliaciones simultaneas de PAY-BX17 dejan exactamente 1 reverso")
    void concurrentReconciliationsOfSamePaymentPostOnlyOneReversal() throws Exception {
        List<ReconciliationResult> results = runConcurrently("PAY-BX17", 6);

        assertThat(results.stream().filter(r -> r.outcome() == Outcome.RECONCILED).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.outcome() == Outcome.CONSISTENT).count()).isEqualTo(5);
        assertThat(data.count("ledger_entry", 2)).isEqualTo(3);            // 2 originales + 1 reverso
        assertThat(data.netDebit(2)).isEqualByComparingTo("89000");
        assertThat(data.auditCount(2, "RECONCILE_REVERSAL_POSTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("Concurrencia: 6 conciliaciones simultaneas de PAY-AX91 actualizan el estado una sola vez")
    void concurrentReconciliationsOfSamePaymentUpdateStatusOnce() throws Exception {
        List<ReconciliationResult> results = runConcurrently("PAY-AX91", 6);

        assertThat(results.stream().filter(r -> r.outcome() == Outcome.RECONCILED).count()).isEqualTo(1);
        assertThat(data.status(1)).isEqualTo("CONFIRMED");
        assertThat(data.auditCount(1, "RECONCILE_STATUS_UPDATED")).isEqualTo(1);
        assertThat(data.rowVersion(1)).isEqualTo(1L);
    }

    // ---------------- bordes y errores ----------------

    @Test
    @DisplayName("Borde: referencia inexistente => PaymentNotFoundException y nada se escribe")
    void unknownReferenceThrowsNotFound() {
        int auditsBefore = data.totalAuditCount();

        assertThatThrownBy(() -> service.reconcile("PAY-NOPE")).isInstanceOf(PaymentNotFoundException.class);

        assertThat(data.totalAuditCount()).isEqualTo(auditsBefore);
    }

    @Test
    @DisplayName("Borde: reverso parcial preexistente => revision manual, no se compensa a ciegas")
    void partialReversalGoesToManualReview() {
        LocalDateTime t = LocalDateTime.now().minusHours(1);
        data.payment(10, "PAY-PARTIAL", "CONFIRMED", "100", t);
        data.event(10, 10, "EVT-P1", "APPROVED", t);
        data.event(11, 10, "EVT-P2", "APPROVED", t.plusSeconds(1));
        data.ledger(6001, 10, "DEBIT", "100", null, t);
        data.ledger(6002, 10, "DEBIT", "100", null, t.plusSeconds(1));
        // Un humano ya reverso solo 40 de 100 del segundo debito: el ledger esta en un estado ambiguo.
        // (el indice unico no lo impide: aqui es el monto el que no coincide)
        data.ledger(6003, 10, "REVERSAL", "40", 6002L, t.plusSeconds(2));

        ReconciliationResult r = service.reconcile("PAY-PARTIAL");

        assertThat(r.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
        assertThat(r.reasonCode()).isEqualTo("LEDGER_ANOMALY");
        assertThat(data.count("ledger_entry", 10)).isEqualTo(3);   // no se agrego nada
    }

    @Test
    @DisplayName("Borde: debito duplicado ya reversado antes => CONSISTENT, no se reversa dos veces")
    void alreadyReversedDuplicateIsConsistent() {
        LocalDateTime t = LocalDateTime.now().minusHours(1);
        data.payment(11, "PAY-REVERSED", "CONFIRMED", "100", t);
        data.event(12, 11, "EVT-R1", "APPROVED", t);
        data.event(13, 11, "EVT-R2", "APPROVED", t.plusSeconds(1));
        data.ledger(6101, 11, "DEBIT", "100", null, t);
        data.ledger(6102, 11, "DEBIT", "100", null, t.plusSeconds(1));
        data.ledger(6103, 11, "REVERSAL", "100", 6102L, t.plusSeconds(2));

        ReconciliationResult r = service.reconcile("PAY-REVERSED");

        assertThat(r.outcome()).isEqualTo(Outcome.CONSISTENT);
        assertThat(data.count("ledger_entry", 11)).isEqualTo(3);
    }

    @Test
    @DisplayName("Borde: estado de pago fuera del dominio => revision manual, nunca excepcion")
    void unknownPaymentStatusGoesToManualReview() {
        data.payment(12, "PAY-WEIRD", "REFUNDED", "100", LocalDateTime.now());

        ReconciliationResult r = service.reconcile("PAY-WEIRD");

        assertThat(r.outcome()).isEqualTo(Outcome.MANUAL_REVIEW);
        assertThat(r.reasonCode()).isEqualTo("UNKNOWN_PAYMENT_STATUS");
    }

    @Test
    @DisplayName("Solo rechazos, PENDING y sin ledger => se alinea a REJECTED (y repetir no cambia nada)")
    void declinedPendingBecomesRejectedOnce() {
        LocalDateTime t = LocalDateTime.now().minusMinutes(30);
        data.payment(13, "PAY-DECLINED", "PENDING", "100", t);
        data.event(14, 13, "EVT-X1", "DECLINED", t);

        assertThat(service.reconcile("PAY-DECLINED").outcome()).isEqualTo(Outcome.RECONCILED);
        assertThat(data.status(13)).isEqualTo("REJECTED");
        assertThat(service.reconcile("PAY-DECLINED").outcome()).isEqualTo(Outcome.CONSISTENT);
        assertThat(data.count("support_audit", 13)).isEqualTo(1);
    }

    // ---------------- diagnostico (solo lectura) ----------------

    @Test
    @DisplayName("diagnose: informa la correccion prevista sin modificar nada")
    void diagnoseDoesNotWrite() {
        List<Map<String, Object>> ledgerBefore = data.ledgerSnapshot(2);
        int auditsBefore = data.totalAuditCount();

        ReconciliationResult r = service.diagnose("PAY-BX17");

        assertThat(r.outcome()).isEqualTo(Outcome.CORRECTABLE);
        assertThat(r.changed()).isFalse();
        assertThat(r.actions()).containsExactly("PLANNED_REVERSAL_POSTED");
        assertThat(r.netDebitAfter()).isEqualByComparingTo("89000");
        assertThat(data.ledgerSnapshot(2)).isEqualTo(ledgerBefore);
        assertThat(data.totalAuditCount()).isEqualTo(auditsBefore);
    }

    // ---------------- utilidades ----------------

    private List<ReconciliationResult> runConcurrently(String ref, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ReconciliationResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();                       // todos arrancan a la vez
                    return service.reconcile(ref);
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<ReconciliationResult> results = new ArrayList<>();
            for (Future<ReconciliationResult> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
