-- =====================================================================
-- validate_sp_reconcile_payment.sql   (SOLO ORACLE)
-- Prueba de humo REPRODUCIBLE de sp_reconcile_payment. Pensada para correr en una base Oracle de
-- desarrollo/pruebas (p. ej. contenedor gvenzl/oracle-free) despues de aplicar:
--     db/schema/01_base_schema.sql, db/schema/02_integrity_and_indexes.sql, db/plsql/sp_reconcile_payment.sql
--
-- Inserta datos propios (ids 90001+), ejecuta el procedimiento y verifica con RAISE_APPLICATION_ERROR.
-- Termina con ROLLBACK: no deja datos. Si imprime "OK: todas las verificaciones pasaron", el PL/SQL
-- cumple el contrato (BX17 duplicado, CQ22 contradictorio, AX91 estado, repeticion idempotente).
--
-- Uso:  sqlplus usuario/clave@servicio @db/plsql/validate_sp_reconcile_payment.sql
-- =====================================================================
SET SERVEROUTPUT ON

DECLARE
    v_outcome VARCHAR2(20);
    v_reason  VARCHAR2(40);
    v_n       NUMBER;
    v_net     NUMBER;
    v_status  VARCHAR2(20);

    PROCEDURE check_true(p_ok IN BOOLEAN, p_msg IN VARCHAR2) IS
    BEGIN
        IF NOT p_ok THEN
            RAISE_APPLICATION_ERROR(-20001, 'FALLO: ' || p_msg);
        END IF;
    END;
BEGIN
    -- ---------- datos de prueba ----------
    INSERT INTO payment VALUES (90001, 'T-BX17', 'CONFIRMED', 89000,  LOCALTIMESTAMP, 0);
    INSERT INTO provider_event VALUES (90001, 90001, 'T-EVT-B1', 'APPROVED', LOCALTIMESTAMP);
    INSERT INTO provider_event VALUES (90002, 90001, 'T-EVT-B2', 'APPROVED', LOCALTIMESTAMP);
    INSERT INTO ledger_entry VALUES (90001, 90001, 'DEBIT', 89000, NULL, LOCALTIMESTAMP - INTERVAL '5' SECOND);
    INSERT INTO ledger_entry VALUES (90002, 90001, 'DEBIT', 89000, NULL, LOCALTIMESTAMP);

    INSERT INTO payment VALUES (90002, 'T-CQ22', 'PENDING', 54000, LOCALTIMESTAMP, 0);
    INSERT INTO provider_event VALUES (90003, 90002, 'T-EVT-C1', 'APPROVED', LOCALTIMESTAMP);
    INSERT INTO provider_event VALUES (90004, 90002, 'T-EVT-C2', 'DECLINED', LOCALTIMESTAMP);

    INSERT INTO payment VALUES (90003, 'T-AX91', 'PENDING', 125000, LOCALTIMESTAMP, 0);
    INSERT INTO provider_event VALUES (90005, 90003, 'T-EVT-A1', 'APPROVED', LOCALTIMESTAMP);
    INSERT INTO ledger_entry VALUES (90003, 90003, 'DEBIT', 125000, NULL, LOCALTIMESTAMP);

    -- ---------- BX17: duplicado => 1 reverso, neto 89000 ----------
    sp_reconcile_payment('T-BX17', v_outcome, v_reason);
    check_true(v_outcome = 'RECONCILED' AND v_reason = 'DUPLICATE_DEBIT_REVERSED', 'BX17 debe quedar RECONCILED');
    SELECT NVL(SUM(CASE entry_type WHEN 'DEBIT' THEN amount ELSE -amount END), 0) INTO v_net
      FROM ledger_entry WHERE payment_id = 90001;
    check_true(v_net = 89000, 'BX17 debe quedar con debito neto 89000');
    SELECT COUNT(*) INTO v_n FROM ledger_entry WHERE payment_id = 90001 AND entry_type = 'REVERSAL' AND related_entry_id = 90002;
    check_true(v_n = 1, 'BX17 debe tener exactamente 1 reverso sobre el debito posterior');

    -- ---------- repeticion idempotente ----------
    sp_reconcile_payment('T-BX17', v_outcome, v_reason);
    check_true(v_outcome = 'CONSISTENT', 'la repeticion de BX17 debe ser CONSISTENT');
    SELECT COUNT(*) INTO v_n FROM ledger_entry WHERE payment_id = 90001;
    check_true(v_n = 3, 'la repeticion no debe agregar movimientos');
    SELECT COUNT(*) INTO v_n FROM support_audit WHERE payment_id = 90001;
    check_true(v_n = 1, 'la repeticion no debe duplicar la auditoria');

    -- ---------- CQ22: contradictorio => revision manual, sin cambios, auditoria unica ----------
    sp_reconcile_payment('T-CQ22', v_outcome, v_reason);
    check_true(v_outcome = 'MANUAL_REVIEW' AND v_reason = 'EVIDENCE_CONTRADICTORY', 'CQ22 debe ir a revision manual');
    sp_reconcile_payment('T-CQ22', v_outcome, v_reason);
    SELECT status INTO v_status FROM payment WHERE id = 90002;
    check_true(v_status = 'PENDING', 'CQ22 no debe cambiar de estado');
    SELECT COUNT(*) INTO v_n FROM ledger_entry WHERE payment_id = 90002;
    check_true(v_n = 0, 'CQ22 no debe tener movimientos ni compensaciones');
    SELECT COUNT(*) INTO v_n FROM support_audit WHERE payment_id = 90002 AND action = 'MANUAL_REVIEW_REQUIRED';
    check_true(v_n = 1, 'CQ22 debe tener una sola auditoria aunque se repita');

    -- ---------- AX91: solo se alinea el estado ----------
    sp_reconcile_payment('T-AX91', v_outcome, v_reason);
    SELECT status INTO v_status FROM payment WHERE id = 90003;
    check_true(v_outcome = 'RECONCILED' AND v_status = 'CONFIRMED', 'AX91 debe quedar CONFIRMED');
    SELECT COUNT(*) INTO v_n FROM ledger_entry WHERE payment_id = 90003;
    check_true(v_n = 1, 'AX91 no debe tocar el ledger');

    -- ---------- inexistente ----------
    sp_reconcile_payment('T-NOPE', v_outcome, v_reason);
    check_true(v_outcome = 'NOT_FOUND', 'una referencia inexistente debe devolver NOT_FOUND');

    ROLLBACK;
    DBMS_OUTPUT.PUT_LINE('OK: todas las verificaciones pasaron');
EXCEPTION
    WHEN OTHERS THEN
        ROLLBACK;
        DBMS_OUTPUT.PUT_LINE('ERROR: ' || SQLERRM);
        RAISE;
END;
/
