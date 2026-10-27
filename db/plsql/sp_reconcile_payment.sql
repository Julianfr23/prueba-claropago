-- =====================================================================
-- sp_reconcile_payment.sql   (SOLO ORACLE)
--
-- Concilia UN pago de forma atomica o registra que requiere revision manual.
-- Implementa las MISMAS reglas que ReconciliationAnalyzer (Java); los textos de auditoria son
-- identicos, de modo que ejecutar la API y este procedimiento sobre el mismo caso no duplica registros.
--
-- INTERFAZ
--   sp_reconcile_payment(
--       p_external_ref IN  VARCHAR2,   -- PAYMENT.external_ref
--       p_outcome      OUT VARCHAR2,   -- CONSISTENT | RECONCILED | MANUAL_REVIEW | NOT_FOUND
--       p_reason_code  OUT VARCHAR2)   -- mismo catalogo que ReasonCode (Java) o PAYMENT_NOT_FOUND
--
-- CUANDO MODIFICA DATOS (y solo entonces):
--   1. Solo APPROVED, estado PENDING                  => UPDATE PAYMENT (status=CONFIRMED, row_version+1).
--   2. Solo APPROVED, 2+ debitos vigentes del monto   => INSERT de REVERSAL (relacionado al debito
--      exacto del pago                                   duplicado) por cada debito posterior al mas antiguo.
--   3. Solo DECLINED, PENDING, debito neto = 0        => UPDATE PAYMENT (status=REJECTED, row_version+1).
--   4. Revision manual                                 => unico efecto: 1 INSERT en SUPPORT_AUDIT
--                                                         (accion MANUAL_REVIEW_REQUIRED), una sola vez.
--   Cualquier otro caso (consistente, pendiente sin evidencia) no modifica nada.
--   NUNCA hace DELETE ni UPDATE sobre LEDGER_ENTRY.
--
-- ATOMICIDAD / TRANSACCION
--   * Bloquea la fila de PAYMENT (FOR UPDATE WAIT 10): dos ejecuciones cercanas se serializan.
--     Si no obtiene el bloqueo en 10 s lanza ORA-30006 (el llamador puede reintentar).
--   * Ante cualquier error hace ROLLBACK TO SAVEPOINT y relanza: nunca quedan correcciones parciales.
--   * NO hace COMMIT: el llamador decide (asi puede combinarse en una transaccion mayor y los bloqueos
--     se liberan al confirmar). Ver el bloque de ejemplo al final.
--   * Idempotente: tras una correccion, volver a ejecutarlo devuelve CONSISTENT y no inserta nada.
--
-- PRIVILEGIOS: otorgar EXECUTE solo al rol de soporte N3. No imprime ni registra secretos.
-- NOTA: este archivo NO se pudo ejecutar contra una base Oracle durante la prueba (ver README,
--       seccion "Validacion del PL/SQL").
-- =====================================================================
CREATE OR REPLACE PROCEDURE sp_reconcile_payment (
    p_external_ref IN  VARCHAR2,
    p_outcome      OUT VARCHAR2,
    p_reason_code  OUT VARCHAR2
) AS
    v_pay_id       payment.id%TYPE;
    v_status       payment.status%TYPE;
    v_amount       payment.amount%TYPE;
    v_approved     PLS_INTEGER;
    v_declined     PLS_INTEGER;
    v_unknown_evt  PLS_INTEGER;
    v_entries      PLS_INTEGER;
    v_bad_entries  PLS_INTEGER;
    v_double_rev   PLS_INTEGER;
    v_net          NUMBER;
    v_active_cnt   PLS_INTEGER;
    v_active_bad   PLS_INTEGER;
    v_keep_id      ledger_entry.id%TYPE;
    v_changed      BOOLEAN := FALSE;
    v_reason       VARCHAR2(40);

    -- Inserta la auditoria solo si no existe ya una identica (idempotencia de la auditoria).
    PROCEDURE audit_once (p_action IN VARCHAR2, p_reason IN VARCHAR2) IS
        v_exists PLS_INTEGER;
    BEGIN
        SELECT COUNT(*)
          INTO v_exists
          FROM support_audit
         WHERE payment_id = v_pay_id
           AND action     = p_action
           AND reason     = SUBSTR(p_reason, 1, 400);
        IF v_exists = 0 THEN
            INSERT INTO support_audit (id, payment_id, action, reason, created_at)
            VALUES (support_audit_seq.NEXTVAL, v_pay_id, p_action, SUBSTR(p_reason, 1, 400), LOCALTIMESTAMP);
        END IF;
    END audit_once;

    PROCEDURE finish_manual (p_code IN VARCHAR2, p_detail IN VARCHAR2) IS
    BEGIN
        audit_once('MANUAL_REVIEW_REQUIRED', p_code || ': ' || p_detail);
        p_outcome     := 'MANUAL_REVIEW';
        p_reason_code := p_code;
    END finish_manual;

    PROCEDURE finish_consistent (p_code IN VARCHAR2) IS
    BEGIN
        p_outcome     := 'CONSISTENT';
        p_reason_code := p_code;
    END finish_consistent;

BEGIN
    SAVEPOINT sp_reconcile_start;
    p_outcome     := NULL;
    p_reason_code := NULL;

    -- 1) Bloqueo de la fila del pago: serializa ejecuciones concurrentes sobre el mismo pago.
    BEGIN
        SELECT id, status, amount
          INTO v_pay_id, v_status, v_amount
          FROM payment
         WHERE external_ref = p_external_ref
           FOR UPDATE WAIT 10;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            p_outcome     := 'NOT_FOUND';
            p_reason_code := 'PAYMENT_NOT_FOUND';
            RETURN;
    END;

    -- 2) Hechos (con el bloqueo ya tomado, se lee lo que commiteo la ejecucion anterior).
    SELECT NVL(SUM(CASE WHEN provider_status = 'APPROVED' THEN 1 ELSE 0 END), 0),
           NVL(SUM(CASE WHEN provider_status = 'DECLINED' THEN 1 ELSE 0 END), 0),
           NVL(SUM(CASE WHEN provider_status NOT IN ('APPROVED', 'DECLINED') THEN 1 ELSE 0 END), 0)
      INTO v_approved, v_declined, v_unknown_evt
      FROM provider_event
     WHERE payment_id = v_pay_id;

    SELECT COUNT(*) INTO v_entries FROM ledger_entry WHERE payment_id = v_pay_id;

    -- Movimientos invalidos: tipo desconocido, monto no positivo o reverso sin debito
    -- valido del mismo pago y del mismo monto (reverso parcial / huerfano).
    SELECT COUNT(*)
      INTO v_bad_entries
      FROM ledger_entry e
     WHERE e.payment_id = v_pay_id
       AND (   e.entry_type NOT IN ('DEBIT', 'REVERSAL')
            OR e.amount <= 0
            OR (    e.entry_type = 'REVERSAL'
                AND NOT EXISTS (SELECT 1
                                  FROM ledger_entry d
                                 WHERE d.id         = e.related_entry_id
                                   AND d.payment_id = e.payment_id
                                   AND d.entry_type = 'DEBIT'
                                   AND d.amount     = e.amount)));

    -- Un debito reversado mas de una vez.
    SELECT COUNT(*)
      INTO v_double_rev
      FROM (SELECT related_entry_id
              FROM ledger_entry
             WHERE payment_id = v_pay_id
               AND entry_type = 'REVERSAL'
             GROUP BY related_entry_id
            HAVING COUNT(*) > 1);

    SELECT NVL(SUM(CASE entry_type WHEN 'DEBIT' THEN amount WHEN 'REVERSAL' THEN -amount ELSE 0 END), 0)
      INTO v_net
      FROM ledger_entry
     WHERE payment_id = v_pay_id;

    -- 3) Reglas (mismo orden que el analizador Java).
    IF v_status NOT IN ('PENDING', 'CONFIRMED', 'REJECTED') THEN
        finish_manual('UNKNOWN_PAYMENT_STATUS', 'payment status not in PENDING|CONFIRMED|REJECTED');
        RETURN;
    END IF;

    IF v_unknown_evt > 0 THEN
        finish_manual('UNKNOWN_PROVIDER_STATUS', 'provider event with status not in APPROVED|DECLINED');
        RETURN;
    END IF;

    -- Evidencia contradictoria: NO se decide por orden temporal ni por cantidad.
    IF v_approved > 0 AND v_declined > 0 THEN
        finish_manual('EVIDENCE_CONTRADICTORY',
                      'approvedEvents=' || v_approved || ', declinedEvents=' || v_declined);
        RETURN;
    END IF;

    IF v_bad_entries > 0 OR v_double_rev > 0 THEN
        finish_manual('LEDGER_ANOMALY',
                      'invalid ledger entries (unknown type, non-positive amount, unlinked/partial/double reversal)');
        RETURN;
    END IF;

    -- Sin eventos del proveedor.
    IF v_approved = 0 AND v_declined = 0 THEN
        IF v_status = 'PENDING' AND v_entries = 0 THEN
            finish_consistent('PENDING_AWAITING_PROVIDER');
        ELSE
            finish_manual('NO_PROVIDER_EVIDENCE', 'no provider events and payment is not an empty PENDING');
        END IF;
        RETURN;
    END IF;

    -- Solo rechazos.
    IF v_declined > 0 THEN
        IF v_net <> 0 THEN
            finish_manual('DECLINED_WITH_NET_DEBIT', 'provider declined but net debit is not zero');
        ELSIF v_status = 'REJECTED' THEN
            finish_consistent('ALREADY_CONSISTENT');
        ELSIF v_status = 'PENDING' THEN
            UPDATE payment
               SET status      = 'REJECTED',
                   updated_at  = LOCALTIMESTAMP,
                   row_version = row_version + 1
             WHERE id = v_pay_id;
            audit_once('RECONCILE_STATUS_UPDATED', 'status PENDING->REJECTED by provider evidence');
            p_outcome     := 'RECONCILED';
            p_reason_code := 'STATUS_ALIGNED_TO_DECLINE';
        ELSE
            finish_manual('CONFIRMED_BUT_DECLINED', 'payment CONFIRMED but provider evidence is DECLINED');
        END IF;
        RETURN;
    END IF;

    -- Solo aprobaciones.
    IF v_status = 'REJECTED' THEN
        finish_manual('REJECTED_BUT_APPROVED', 'payment REJECTED but provider evidence is APPROVED');
        RETURN;
    END IF;

    -- Debitos vigentes = debitos sin un reverso que los referencie.
    SELECT COUNT(*),
           NVL(SUM(CASE WHEN d.amount <> v_amount THEN 1 ELSE 0 END), 0)
      INTO v_active_cnt, v_active_bad
      FROM ledger_entry d
     WHERE d.payment_id = v_pay_id
       AND d.entry_type = 'DEBIT'
       AND NOT EXISTS (SELECT 1
                         FROM ledger_entry r
                        WHERE r.related_entry_id = d.id
                          AND r.entry_type = 'REVERSAL');

    IF v_active_cnt = 0 THEN
        -- No se postea un debito por cuenta propia: podria haber una contabilizacion asincrona en curso.
        finish_manual('MISSING_DEBIT', 'provider approved but no active debit exists');
        RETURN;
    END IF;

    IF v_active_bad > 0 THEN
        finish_manual('LEDGER_AMOUNT_MISMATCH', 'active debit amount differs from payment amount');
        RETURN;
    END IF;

    -- Correccion de duplicados: se conserva el debito vigente mas antiguo y se reversan los demas.
    IF v_active_cnt > 1 THEN
        SELECT id
          INTO v_keep_id
          FROM (SELECT d.id
                  FROM ledger_entry d
                 WHERE d.payment_id = v_pay_id
                   AND d.entry_type = 'DEBIT'
                   AND NOT EXISTS (SELECT 1 FROM ledger_entry r
                                    WHERE r.related_entry_id = d.id AND r.entry_type = 'REVERSAL')
                 ORDER BY d.created_at, d.id)
         WHERE ROWNUM = 1;

        FOR rec IN (SELECT d.id, d.amount
                      FROM ledger_entry d
                     WHERE d.payment_id = v_pay_id
                       AND d.entry_type = 'DEBIT'
                       AND d.id <> v_keep_id
                       AND NOT EXISTS (SELECT 1 FROM ledger_entry r
                                        WHERE r.related_entry_id = d.id AND r.entry_type = 'REVERSAL')
                     ORDER BY d.created_at, d.id)
        LOOP
            INSERT INTO ledger_entry (id, payment_id, entry_type, amount, related_entry_id, created_at)
            VALUES (ledger_entry_seq.NEXTVAL, v_pay_id, 'REVERSAL', rec.amount, rec.id, LOCALTIMESTAMP);
            audit_once('RECONCILE_REVERSAL_POSTED', 'duplicate debit ' || rec.id || ' reversed');
            v_changed := TRUE;
            v_reason  := 'DUPLICATE_DEBIT_REVERSED';
        END LOOP;
    END IF;

    IF v_status = 'PENDING' THEN
        UPDATE payment
           SET status      = 'CONFIRMED',
               updated_at  = LOCALTIMESTAMP,
               row_version = row_version + 1
         WHERE id = v_pay_id;
        audit_once('RECONCILE_STATUS_UPDATED', 'status PENDING->CONFIRMED by provider evidence');
        v_changed := TRUE;
        IF v_reason IS NULL THEN
            v_reason := 'STATUS_ALIGNED_TO_APPROVAL';
        END IF;
    END IF;

    IF v_changed THEN
        p_outcome     := 'RECONCILED';
        p_reason_code := v_reason;
    ELSE
        finish_consistent('ALREADY_CONSISTENT');
    END IF;

EXCEPTION
    WHEN OTHERS THEN
        -- Atomicidad: no quedan correcciones parciales. El llamador recibe el error original.
        ROLLBACK TO SAVEPOINT sp_reconcile_start;
        RAISE;
END sp_reconcile_payment;
/

-- ---------------------------------------------------------------------
-- Ejemplo de uso (SQL*Plus / SQLcl / PL/SQL Developer):
--
--   SET SERVEROUTPUT ON
--   DECLARE
--       v_outcome VARCHAR2(20);
--       v_reason  VARCHAR2(40);
--   BEGIN
--       sp_reconcile_payment('PAY-BX17', v_outcome, v_reason);
--       DBMS_OUTPUT.PUT_LINE('outcome=' || v_outcome || ' reason=' || v_reason);
--       COMMIT;     -- el procedimiento no confirma: el llamador decide
--   END;
--   /
-- ---------------------------------------------------------------------
