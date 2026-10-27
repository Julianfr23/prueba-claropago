package com.pagoclaro.reconciliation.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.pagoclaro.reconciliation.service.ReconciliationService;
import com.pagoclaro.reconciliation.support.TestData;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ejecuta el archivo REAL db/queries/01_detect_inconsistencies_24h.sql (el mismo que se entrega) contra
 * H2 en modo Oracle y verifica que clasifica los 4 casos de muestra como se espera.
 */
@SpringBootTest
class DetectionQueryTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReconciliationService service;

    private TestData data;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        data.clean();
        data.loadSamples();
    }

    @Test
    @DisplayName("Detecta AX91 (estado), BX17 (monto) y CQ22 (contradictoria); no marca el caso sano DK44")
    void flagsTheThreeProblemPaymentsAndNotTheHealthyOne() throws IOException {
        List<Map<String, Object>> rows = runQuery();

        assertThat(rows).extracting(r -> r.get("external_ref"))
                .containsExactly("PAY-AX91", "PAY-BX17", "PAY-CQ22");

        Map<String, Object> ax91 = row(rows, "PAY-AX91");
        assertThat(ax91.get("status_incompatible")).isEqualTo("Y");
        assertThat(ax91.get("amount_mismatch")).isEqualTo("N");

        Map<String, Object> bx17 = row(rows, "PAY-BX17");
        assertThat(bx17.get("amount_mismatch")).isEqualTo("Y");
        assertThat((BigDecimal) bx17.get("net_debit")).isEqualByComparingTo("178000");
        assertThat((BigDecimal) bx17.get("expected_net_debit")).isEqualByComparingTo("89000");
        assertThat((BigDecimal) bx17.get("amount_difference")).isEqualByComparingTo("89000");

        Map<String, Object> cq22 = row(rows, "PAY-CQ22");
        assertThat(cq22.get("contradictory_evidence")).isEqualTo("Y");
        assertThat(cq22.get("expected_net_debit")).isNull();   // no se adivina el esperado
    }

    @Test
    @DisplayName("Respeta la ventana de 24 horas: un pago inconsistente de hace 3 dias no aparece")
    void ignoresRowsOlderThan24Hours() throws IOException {
        LocalDateTime old = LocalDateTime.now().minusDays(3);
        data.payment(20, "PAY-OLD", "PENDING", "500", old);
        data.event(30, 20, "EVT-OLD", "APPROVED", old);
        data.ledger(7001, 20, "DEBIT", "500", null, old);

        assertThat(runQuery()).extracting(r -> r.get("external_ref")).doesNotContain("PAY-OLD");
    }

    @Test
    @DisplayName("Un debito duplicado reciente sobre un pago antiguo SI se detecta (no depende de updated_at)")
    void detectsRecentDuplicateOnOlderPayment() throws IOException {
        LocalDateTime old = LocalDateTime.now().minusDays(3);
        LocalDateTime recent = LocalDateTime.now().minusMinutes(10);
        data.payment(21, "PAY-OLDDUP", "CONFIRMED", "500", old);
        data.event(31, 21, "EVT-OD1", "APPROVED", old);
        data.ledger(7101, 21, "DEBIT", "500", null, old);
        data.ledger(7102, 21, "DEBIT", "500", null, recent);   // duplicado reciente

        assertThat(runQuery()).extracting(r -> r.get("external_ref")).contains("PAY-OLDDUP");
    }

    @Test
    @DisplayName("Despues de conciliar AX91 y BX17, la consulta solo sigue marcando el caso de revision manual")
    void afterReconciliationOnlyManualReviewRemains() throws IOException {
        service.reconcile("PAY-AX91");
        service.reconcile("PAY-BX17");

        assertThat(runQuery()).extracting(r -> r.get("external_ref")).containsExactly("PAY-CQ22");
    }

    // ---------------- utilidades ----------------

    private List<Map<String, Object>> runQuery() throws IOException {
        return jdbc.queryForList(loadSql());
    }

    private static Map<String, Object> row(List<Map<String, Object>> rows, String ref) {
        return rows.stream().filter(r -> ref.equals(r.get("external_ref"))).findFirst().orElseThrow();
    }

    /** Lee el archivo entregado y le quita el ';' final (JDBC no lo admite en una sentencia suelta). */
    private static String loadSql() throws IOException {
        try (InputStream in = new ClassPathResource("db/queries/01_detect_inconsistencies_24h.sql").getInputStream()) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            return sql.endsWith(";") ? sql.substring(0, sql.length() - 1) : sql;
        }
    }
}
