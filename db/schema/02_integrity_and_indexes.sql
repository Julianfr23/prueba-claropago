-- =====================================================================
-- 02_integrity_and_indexes.sql   (OPCIONAL en produccion, ver README)
-- Indices y una restriccion de integridad. Sintaxis portable Oracle / H2.
--
-- PRE-REQUISITO en Oracle real: verificar que no existan reversos duplicados historicos,
-- de lo contrario el indice unico fallara al crearse:
--     SELECT related_entry_id, COUNT(*) FROM ledger_entry
--      WHERE related_entry_id IS NOT NULL GROUP BY related_entry_id HAVING COUNT(*) > 1;
-- Si devuelve filas, esas filas son casos para revision manual ANTES de crear el indice.
-- =====================================================================

-- Proposito: un debito solo puede ser reversado UNA vez (los reversos referencian al debito en
-- related_entry_id; esa columna solo la usan los reversos). Es la ultima barrera contra una
-- carrera o un doble reverso, incluso si otro proceso escribe en el ledger sin pasar por el
-- bloqueo de PAYMENT. Los NULL (los debitos) no cuentan para la unicidad.
CREATE UNIQUE INDEX UX_LEDGER_ONE_REVERSAL_PER_ENTRY ON LEDGER_ENTRY (related_entry_id);

-- Proposito: acelerar la lectura de evidencia por pago (FKs sin indice en Oracle ademas
-- provocan bloqueos de tabla al modificar el padre).
CREATE INDEX IX_PROVIDER_EVENT_PAYMENT ON PROVIDER_EVENT (payment_id);
CREATE INDEX IX_LEDGER_ENTRY_PAYMENT   ON LEDGER_ENTRY (payment_id, created_at);
CREATE INDEX IX_SUPPORT_AUDIT_PAYMENT  ON SUPPORT_AUDIT (payment_id, action);

-- Proposito: soportar la consulta de deteccion de "ultimas 24 horas" sin barrer las tablas.
CREATE INDEX IX_PAYMENT_UPDATED_AT        ON PAYMENT (updated_at);
CREATE INDEX IX_PROVIDER_EVENT_RECEIVED   ON PROVIDER_EVENT (received_at);
CREATE INDEX IX_LEDGER_ENTRY_CREATED_AT   ON LEDGER_ENTRY (created_at);
