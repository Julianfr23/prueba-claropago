-- =====================================================================
-- 03_oracle_check_constraints.sql   (SOLO ORACLE, opcional, NO lo carga la aplicacion)
-- Restricciones CHECK que impiden estados invalidos en el origen.
-- Se crean ENABLE NOVALIDATE: protegen toda escritura NUEVA sin fallar por datos historicos
-- sucios. Luego se pueden validar cuando el equipo haya revisado las filas que no cumplen.
-- =====================================================================

ALTER TABLE PAYMENT ADD CONSTRAINT CK_PAYMENT_STATUS
    CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED')) ENABLE NOVALIDATE;

ALTER TABLE PROVIDER_EVENT ADD CONSTRAINT CK_PROVIDER_EVENT_STATUS
    CHECK (provider_status IN ('APPROVED', 'DECLINED')) ENABLE NOVALIDATE;

ALTER TABLE LEDGER_ENTRY ADD CONSTRAINT CK_LEDGER_ENTRY_TYPE
    CHECK (entry_type IN ('DEBIT', 'REVERSAL')) ENABLE NOVALIDATE;

ALTER TABLE LEDGER_ENTRY ADD CONSTRAINT CK_LEDGER_ENTRY_AMOUNT
    CHECK (amount > 0) ENABLE NOVALIDATE;

-- Un reverso siempre referencia el movimiento que corrige; un debito nunca referencia nada.
ALTER TABLE LEDGER_ENTRY ADD CONSTRAINT CK_LEDGER_ENTRY_RELATED
    CHECK ((entry_type = 'REVERSAL' AND related_entry_id IS NOT NULL)
        OR (entry_type = 'DEBIT'    AND related_entry_id IS NULL)) ENABLE NOVALIDATE;
