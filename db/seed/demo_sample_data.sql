-- Datos de muestra del enunciado (seccion 2.3) para el perfil "demo".
-- Las fechas son relativas a "ahora" para que caigan dentro de la ventana de 24 horas.

-- PAY-AX91: aprobado por el proveedor, debito correcto, pero estado PENDING.
INSERT INTO payment (id, external_ref, status, amount, updated_at, row_version)
VALUES (1, 'PAY-AX91', 'PENDING', 125000, LOCALTIMESTAMP - INTERVAL '2' HOUR, 0);
INSERT INTO provider_event (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (1, 1, 'EVT-A1', 'APPROVED', LOCALTIMESTAMP - INTERVAL '2' HOUR);
INSERT INTO ledger_entry (id, payment_id, entry_type, amount, related_entry_id, created_at)
VALUES (5001, 1, 'DEBIT', 125000, NULL, LOCALTIMESTAMP - INTERVAL '2' HOUR);

-- PAY-BX17: dos eventos APPROVED y dos debitos tras el reintento (debito neto duplicado).
INSERT INTO payment (id, external_ref, status, amount, updated_at, row_version)
VALUES (2, 'PAY-BX17', 'CONFIRMED', 89000, LOCALTIMESTAMP - INTERVAL '2' HOUR + INTERVAL '2' MINUTE, 0);
INSERT INTO provider_event (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (2, 2, 'EVT-B1', 'APPROVED', LOCALTIMESTAMP - INTERVAL '2' HOUR + INTERVAL '1' MINUTE);
INSERT INTO provider_event (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (3, 2, 'EVT-B2', 'APPROVED', LOCALTIMESTAMP - INTERVAL '2' HOUR + INTERVAL '2' MINUTE);
INSERT INTO ledger_entry (id, payment_id, entry_type, amount, related_entry_id, created_at)
VALUES (5002, 2, 'DEBIT', 89000, NULL, LOCALTIMESTAMP - INTERVAL '2' HOUR + INTERVAL '1' MINUTE);
INSERT INTO ledger_entry (id, payment_id, entry_type, amount, related_entry_id, created_at)
VALUES (5003, 2, 'DEBIT', 89000, NULL, LOCALTIMESTAMP - INTERVAL '2' HOUR + INTERVAL '2' MINUTE);

-- PAY-CQ22: evidencia contradictoria (APPROVED + DECLINED), sin movimientos.
INSERT INTO payment (id, external_ref, status, amount, updated_at, row_version)
VALUES (3, 'PAY-CQ22', 'PENDING', 54000, LOCALTIMESTAMP - INTERVAL '90' MINUTE, 0);
INSERT INTO provider_event (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (4, 3, 'EVT-C1', 'APPROVED', LOCALTIMESTAMP - INTERVAL '91' MINUTE);
INSERT INTO provider_event (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (5, 3, 'EVT-C2', 'DECLINED', LOCALTIMESTAMP - INTERVAL '90' MINUTE);

-- PAY-DK44: caso sano de control.
INSERT INTO payment (id, external_ref, status, amount, updated_at, row_version)
VALUES (4, 'PAY-DK44', 'CONFIRMED', 31000, LOCALTIMESTAMP - INTERVAL '60' MINUTE, 0);
INSERT INTO provider_event (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (6, 4, 'EVT-D1', 'APPROVED', LOCALTIMESTAMP - INTERVAL '60' MINUTE);
INSERT INTO ledger_entry (id, payment_id, entry_type, amount, related_entry_id, created_at)
VALUES (5004, 4, 'DEBIT', 31000, NULL, LOCALTIMESTAMP - INTERVAL '60' MINUTE);
