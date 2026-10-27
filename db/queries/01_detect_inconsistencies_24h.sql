-- =====================================================================
-- 01_detect_inconsistencies_24h.sql
-- Detecta, para las ULTIMAS 24 HORAS, pagos con:
--   (a) diferencia entre el debito neto del ledger y el monto esperado, o
--   (b) estado incompatible con la evidencia del proveedor, o
--   (c) evidencia del proveedor contradictoria (APPROVED y DECLINED a la vez).
-- Solo lectura. Compatible con Oracle y con H2 en modo Oracle (se ejecuta en las pruebas).
--
-- DEFINICIONES (justificacion de cada decision):
--  * "Reciente" = el pago, alguno de sus eventos o alguno de sus movimientos ocurrio en las ultimas
--    24 horas. No basta con PAYMENT.updated_at: un debito duplicado puede insertarse sin tocar el
--    pago (es justo lo que paso en PAY-BX17).
--  * Debito neto = SUM(DEBIT) - SUM(REVERSAL). Los movimientos son inmutables, asi que el neto es
--    la unica medida valida de "cuanto se cobro realmente".
--  * Debito neto esperado:
--      solo APPROVED                 => monto del pago
--      solo DECLINED                 => 0
--      sin eventos y CONFIRMED       => monto del pago (regla de negocio: confirmado = un debito)
--      sin eventos y otro estado     => 0
--      APPROVED + DECLINED           => NULL (no se puede decidir; no se adivina)
--  * Estado incompatible = el estado no corresponde a la evidencia:
--      solo APPROVED y estado <> CONFIRMED, o solo DECLINED y estado <> REJECTED,
--      o sin eventos y estado <> PENDING.
--  * Las agregaciones se limitan a los pagos recientes (CTE recent_payments) para no recorrer las
--    tablas completas; se apoya en los indices de db/schema/02_integrity_and_indexes.sql.
-- =====================================================================
WITH recent_payments AS (
    SELECT id FROM payment
     WHERE updated_at >= LOCALTIMESTAMP - INTERVAL '24' HOUR
    UNION
    SELECT payment_id FROM provider_event
     WHERE received_at >= LOCALTIMESTAMP - INTERVAL '24' HOUR
    UNION
    SELECT payment_id FROM ledger_entry
     WHERE created_at >= LOCALTIMESTAMP - INTERVAL '24' HOUR
),
evidence AS (
    SELECT pe.payment_id,
           SUM(CASE WHEN pe.provider_status = 'APPROVED' THEN 1 ELSE 0 END) AS approved_events,
           SUM(CASE WHEN pe.provider_status = 'DECLINED' THEN 1 ELSE 0 END) AS declined_events
      FROM provider_event pe
      JOIN recent_payments rp ON rp.id = pe.payment_id
     GROUP BY pe.payment_id
),
ledger AS (
    SELECT le.payment_id,
           SUM(CASE le.entry_type
                   WHEN 'DEBIT'    THEN le.amount
                   WHEN 'REVERSAL' THEN -le.amount
                   ELSE 0
               END) AS net_debit
      FROM ledger_entry le
      JOIN recent_payments rp ON rp.id = le.payment_id
     GROUP BY le.payment_id
),
classified AS (
    SELECT p.external_ref,
           p.status,
           p.amount AS payment_amount,
           NVL(ev.approved_events, 0) AS approved_events,
           NVL(ev.declined_events, 0) AS declined_events,
           NVL(l.net_debit, 0)        AS net_debit,
           CASE
               WHEN NVL(ev.approved_events, 0) > 0 AND NVL(ev.declined_events, 0) = 0 THEN p.amount
               WHEN NVL(ev.approved_events, 0) = 0 AND NVL(ev.declined_events, 0) > 0 THEN 0
               WHEN NVL(ev.approved_events, 0) = 0 AND NVL(ev.declined_events, 0) = 0
                    THEN CASE WHEN p.status = 'CONFIRMED' THEN p.amount ELSE 0 END
               ELSE NULL
           END AS expected_net_debit
      FROM payment p
      JOIN recent_payments rp ON rp.id = p.id
      LEFT JOIN evidence ev ON ev.payment_id = p.id
      LEFT JOIN ledger   l  ON l.payment_id  = p.id
),
flagged AS (
    SELECT c.*,
           CASE WHEN c.approved_events > 0 AND c.declined_events > 0
                THEN 'Y' ELSE 'N' END AS contradictory_evidence,
           CASE WHEN c.expected_net_debit IS NOT NULL AND c.net_debit <> c.expected_net_debit
                THEN 'Y' ELSE 'N' END AS amount_mismatch,
           CASE WHEN (c.approved_events > 0 AND c.declined_events = 0 AND c.status <> 'CONFIRMED')
                  OR (c.declined_events > 0 AND c.approved_events = 0 AND c.status <> 'REJECTED')
                  OR (c.approved_events = 0 AND c.declined_events = 0 AND c.status <> 'PENDING')
                THEN 'Y' ELSE 'N' END AS status_incompatible
      FROM classified c
)
SELECT f.external_ref,
       f.status,
       f.payment_amount,
       f.expected_net_debit,
       f.net_debit,
       CASE WHEN f.expected_net_debit IS NOT NULL
            THEN f.net_debit - f.expected_net_debit END AS amount_difference,
       f.approved_events,
       f.declined_events,
       f.amount_mismatch,
       f.status_incompatible,
       f.contradictory_evidence
  FROM flagged f
 WHERE f.amount_mismatch = 'Y'
    OR f.status_incompatible = 'Y'
    OR f.contradictory_evidence = 'Y'
 ORDER BY f.external_ref;
