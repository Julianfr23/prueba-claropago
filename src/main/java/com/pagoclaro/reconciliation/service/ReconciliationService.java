package com.pagoclaro.reconciliation.service;

import com.pagoclaro.reconciliation.analysis.Decision;
import com.pagoclaro.reconciliation.analysis.LedgerFacts;
import com.pagoclaro.reconciliation.analysis.PaymentFacts;
import com.pagoclaro.reconciliation.analysis.ReasonCode;
import com.pagoclaro.reconciliation.analysis.ReconciliationAnalyzer;
import com.pagoclaro.reconciliation.domain.LedgerEntry;
import com.pagoclaro.reconciliation.domain.Payment;
import com.pagoclaro.reconciliation.domain.ProviderEvent;
import com.pagoclaro.reconciliation.domain.SupportAudit;
import com.pagoclaro.reconciliation.repository.LedgerEntryRepository;
import com.pagoclaro.reconciliation.repository.PaymentRepository;
import com.pagoclaro.reconciliation.repository.ProviderEventRepository;
import com.pagoclaro.reconciliation.repository.SupportAuditRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orquesta la conciliacion de UN pago dentro de UNA transaccion:
 *
 * <ol>
 *   <li>bloquea la fila de PAYMENT (SELECT ... FOR UPDATE) => serializa ejecuciones cercanas;</li>
 *   <li>lee evidencia (eventos) y ledger con el bloqueo ya tomado => ve lo que commiteo la ejecucion previa;</li>
 *   <li>delega la decision al {@link ReconciliationAnalyzer} (reglas puras);</li>
 *   <li>aplica SOLO inserciones (reversos, auditoria) y, si corresponde, el cambio de estado;</li>
 *   <li>todo o nada: ledger + estado + auditoria se confirman juntos o se revierten juntos.</li>
 * </ol>
 *
 * <p>Idempotencia: no se usa una tabla de "solicitudes ya procesadas". El resultado se deriva del estado
 * actual; tras una correccion el analisis converge a CONSISTENT y una repeticion no inserta nada. Para los
 * casos de revision manual, la auditoria se registra una sola vez por (pago, accion, razon determinista).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    static final String ACTION_MANUAL_REVIEW = "MANUAL_REVIEW_REQUIRED";
    static final String ACTION_REVERSAL_POSTED = "RECONCILE_REVERSAL_POSTED";
    static final String ACTION_STATUS_UPDATED = "RECONCILE_STATUS_UPDATED";

    private final PaymentRepository payments;
    private final ProviderEventRepository providerEvents;
    private final LedgerEntryRepository ledgerEntries;
    private final SupportAuditRepository audits;
    private final Clock clock;
    private final ReconciliationAnalyzer analyzer = new ReconciliationAnalyzer();

    @PersistenceContext
    private EntityManager entityManager;

    public ReconciliationService(PaymentRepository payments,
                                 ProviderEventRepository providerEvents,
                                 LedgerEntryRepository ledgerEntries,
                                 SupportAuditRepository audits,
                                 Clock clock) {
        this.payments = payments;
        this.providerEvents = providerEvents;
        this.ledgerEntries = ledgerEntries;
        this.audits = audits;
        this.clock = clock;
    }

    /** Concilia el pago: aplica correcciones seguras o registra que requiere revision manual. */
    @Transactional
    public ReconciliationResult reconcile(String externalRef) {
        Payment payment = payments.findByExternalRefForUpdate(externalRef)
                .orElseThrow(PaymentNotFoundException::new);
        // Con el bloqueo tomado, se refresca la fila: garantiza ver lo que commiteo la ejecucion anterior
        // sin depender de como cada motor entrega el valor tras esperar el bloqueo.
        entityManager.refresh(payment);

        List<LedgerEntry> ledger = ledgerEntries.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId());
        Decision decision = decide(payment, ledger);

        ReconciliationResult result;
        switch (decision.verdict()) {
            case CONSISTENT -> result = result(payment, decision, Outcome.CONSISTENT, false,
                    payment.getStatus(), decision.netDebit(), List.of());
            case MANUAL_REVIEW -> {
                recordAuditOnce(payment.getId(), ACTION_MANUAL_REVIEW,
                        decision.reasonCode().name() + ": " + decision.detail());
                result = result(payment, decision, Outcome.MANUAL_REVIEW, false,
                        payment.getStatus(), decision.netDebit(), List.of());
            }
            case CORRECTABLE -> result = applyCorrection(payment, decision, ledger);
            default -> throw new IllegalStateException("unsupported verdict");
        }

        // Log de diagnostico: referencia externa (validada por el controlador), codigos y conteos.
        // No se registran montos ni payloads del proveedor.
        log.info("reconcile externalRef={} outcome={} reasonCode={} changed={} actions={}",
                externalRef, result.outcome(), result.reasonCode(), result.changed(), result.actions());
        return result;
    }

    /** Diagnostico de solo lectura: devuelve lo que haria la conciliacion sin modificar nada. */
    @Transactional(readOnly = true)
    public ReconciliationResult diagnose(String externalRef) {
        Payment payment = payments.findByExternalRef(externalRef).orElseThrow(PaymentNotFoundException::new);
        List<LedgerEntry> ledger = ledgerEntries.findByPaymentIdOrderByCreatedAtAscIdAsc(payment.getId());
        Decision decision = decide(payment, ledger);

        ReconciliationResult result;
        switch (decision.verdict()) {
            case CONSISTENT -> result = result(payment, decision, Outcome.CONSISTENT, false,
                    payment.getStatus(), decision.netDebit(), List.of());
            case MANUAL_REVIEW -> result = result(payment, decision, Outcome.MANUAL_REVIEW, false,
                    payment.getStatus(), decision.netDebit(), List.of());
            case CORRECTABLE -> {
                Map<Long, LedgerEntry> byId = indexById(ledger);
                List<String> planned = new ArrayList<>();
                BigDecimal after = decision.netDebit();
                for (Long debitId : decision.debitIdsToReverse()) {
                    planned.add("PLANNED_REVERSAL_POSTED");
                    after = after.subtract(byId.get(debitId).getAmount());
                }
                if (decision.targetStatus() != null) {
                    planned.add("PLANNED_STATUS_UPDATED:" + decision.targetStatus());
                }
                result = result(payment, decision, Outcome.CORRECTABLE, false,
                        decision.targetStatus() != null ? decision.targetStatus() : payment.getStatus(),
                        after, planned);
            }
            default -> throw new IllegalStateException("unsupported verdict");
        }
        log.info("diagnose externalRef={} outcome={} reasonCode={}",
                externalRef, result.outcome(), result.reasonCode());
        return result;
    }

    // ------------------------------------------------------------------------------------------

    private Decision decide(Payment payment, List<LedgerEntry> ledger) {
        List<String> eventStatuses = new ArrayList<>();
        for (ProviderEvent event : providerEvents.findByPaymentId(payment.getId())) {
            eventStatuses.add(event.getProviderStatus());
        }
        List<LedgerFacts> facts = new ArrayList<>();
        for (LedgerEntry e : ledger) {
            facts.add(new LedgerFacts(e.getId(), e.getEntryType(), e.getAmount(),
                    e.getRelatedEntryId(), e.getCreatedAt()));
        }
        return analyzer.analyze(new PaymentFacts(payment.getStatus(), payment.getAmount()), eventStatuses, facts);
    }

    private ReconciliationResult applyCorrection(Payment payment, Decision decision, List<LedgerEntry> ledger) {
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, LedgerEntry> byId = indexById(ledger);
        List<String> actions = new ArrayList<>();
        BigDecimal netAfter = decision.netDebit();

        // Ledger inmutable: solo se AGREGAN reversos relacionados con el debito duplicado.
        for (Long debitId : decision.debitIdsToReverse()) {
            LedgerEntry debit = byId.get(debitId);
            ledgerEntries.save(LedgerEntry.reversalOf(debit, now));
            recordAuditOnce(payment.getId(), ACTION_REVERSAL_POSTED, "duplicate debit " + debitId + " reversed");
            netAfter = netAfter.subtract(debit.getAmount());
            actions.add("REVERSAL_POSTED");
        }

        if (decision.targetStatus() != null) {
            String previous = payment.getStatus();
            payment.changeStatus(decision.targetStatus(), now);   // row_version se incrementa al confirmar
            recordAuditOnce(payment.getId(), ACTION_STATUS_UPDATED,
                    "status " + previous + "->" + decision.targetStatus() + " by provider evidence");
            actions.add("STATUS_UPDATED:" + decision.targetStatus());
        }
        return result(payment, decision, Outcome.RECONCILED, true, payment.getStatus(), netAfter, actions);
    }

    /** Inserta la auditoria solo si no existe ya una identica: repetir la operacion no la duplica. */
    private void recordAuditOnce(Long paymentId, String action, String reason) {
        if (!audits.existsByPaymentIdAndActionAndReason(paymentId, action, reason)) {
            audits.save(new SupportAudit(paymentId, action, reason, LocalDateTime.now(clock)));
        }
    }

    private static Map<Long, LedgerEntry> indexById(List<LedgerEntry> ledger) {
        Map<Long, LedgerEntry> byId = new HashMap<>();
        for (LedgerEntry e : ledger) {
            byId.put(e.getId(), e);
        }
        return byId;
    }

    private static ReconciliationResult result(Payment payment, Decision decision, Outcome outcome,
                                               boolean changed, String statusAfter,
                                               BigDecimal netAfter, List<String> actions) {
        ReasonCode code = decision.reasonCode();
        return new ReconciliationResult(
                payment.getExternalRef(), outcome, code.name(), code.message(), changed, statusAfter,
                payment.getAmount(), decision.netDebit(), netAfter, List.copyOf(actions));
    }
}
