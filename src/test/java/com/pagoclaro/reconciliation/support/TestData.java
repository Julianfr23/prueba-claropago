package com.pagoclaro.reconciliation.support;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Utilidades de pruebas: inserta los 4 casos de muestra del enunciado con SQL plano (independiente de
 * JPA) y consulta el estado de la base para verificar efectos.
 */
public class TestData {

    private final JdbcTemplate jdbc;

    public TestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- limpieza ----------------

    public void clean() {
        // Orden por dependencias de FK. Las secuencias no se reinician (no hace falta).
        jdbc.update("DELETE FROM support_audit");
        // Los reversos referencian a los debitos: primero ellos.
        jdbc.update("DELETE FROM ledger_entry WHERE related_entry_id IS NOT NULL");
        jdbc.update("DELETE FROM ledger_entry");
        jdbc.update("DELETE FROM provider_event");
        jdbc.update("DELETE FROM payment");
    }

    // ---------------- inserciones ----------------

    public void payment(long id, String ref, String status, String amount, LocalDateTime updatedAt) {
        jdbc.update("INSERT INTO payment (id, external_ref, status, amount, updated_at, row_version) "
                        + "VALUES (?, ?, ?, ?, ?, 0)",
                id, ref, status, new BigDecimal(amount), Timestamp.valueOf(updatedAt));
    }

    public void event(long id, long paymentId, String providerEventId, String status, LocalDateTime receivedAt) {
        jdbc.update("INSERT INTO provider_event (id, payment_id, provider_event_id, provider_status, received_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                id, paymentId, providerEventId, status, Timestamp.valueOf(receivedAt));
    }

    public void ledger(long id, long paymentId, String type, String amount, Long relatedId, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO ledger_entry (id, payment_id, entry_type, amount, related_entry_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, paymentId, type, new BigDecimal(amount), relatedId, Timestamp.valueOf(createdAt));
    }

    /** Los cuatro casos de la seccion 2.3 del enunciado, con fechas relativas a "ahora". */
    public void loadSamples() {
        LocalDateTime t = LocalDateTime.now().minusHours(2);

        // PAY-AX91: aprobado + debito correcto, pero PENDING.
        payment(1, "PAY-AX91", "PENDING", "125000", t);
        event(1, 1, "EVT-A1", "APPROVED", t);
        ledger(5001, 1, "DEBIT", "125000", null, t);

        // PAY-BX17: dos aprobaciones y dos debitos tras el reintento.
        payment(2, "PAY-BX17", "CONFIRMED", "89000", t.plusSeconds(3));
        event(2, 2, "EVT-B1", "APPROVED", t);
        event(3, 2, "EVT-B2", "APPROVED", t.plusSeconds(3));
        ledger(5002, 2, "DEBIT", "89000", null, t);
        ledger(5003, 2, "DEBIT", "89000", null, t.plusSeconds(3));

        // PAY-CQ22: evidencia contradictoria, sin ledger.
        payment(3, "PAY-CQ22", "PENDING", "54000", t);
        event(4, 3, "EVT-C1", "APPROVED", t);
        event(5, 3, "EVT-C2", "DECLINED", t.plusSeconds(1));

        // PAY-DK44: caso sano de control.
        payment(4, "PAY-DK44", "CONFIRMED", "31000", t);
        event(6, 4, "EVT-D1", "APPROVED", t);
        ledger(5004, 4, "DEBIT", "31000", null, t);
    }

    // ---------------- consultas de verificacion ----------------

    public int count(String table, long paymentId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE payment_id = ?",
                Integer.class, paymentId);
        return n == null ? 0 : n;
    }

    public int auditCount(long paymentId, String action) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM support_audit WHERE payment_id = ? AND action = ?",
                Integer.class, paymentId, action);
        return n == null ? 0 : n;
    }

    public int totalAuditCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM support_audit", Integer.class);
        return n == null ? 0 : n;
    }

    public BigDecimal netDebit(long paymentId) {
        BigDecimal n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE entry_type WHEN 'DEBIT' THEN amount WHEN 'REVERSAL' THEN -amount ELSE 0 END), 0) "
                        + "FROM ledger_entry WHERE payment_id = ?", BigDecimal.class, paymentId);
        return n == null ? BigDecimal.ZERO : n;
    }

    public String status(long paymentId) {
        return jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId);
    }

    public long rowVersion(long paymentId) {
        Long v = jdbc.queryForObject("SELECT row_version FROM payment WHERE id = ?", Long.class, paymentId);
        return v == null ? 0 : v;
    }

    /** Foto completa del ledger de un pago, para comprobar que los movimientos existentes no cambian. */
    public List<Map<String, Object>> ledgerSnapshot(long paymentId) {
        return jdbc.queryForList(
                "SELECT id, entry_type, amount, related_entry_id, created_at FROM ledger_entry "
                        + "WHERE payment_id = ? ORDER BY id", paymentId);
    }
}
