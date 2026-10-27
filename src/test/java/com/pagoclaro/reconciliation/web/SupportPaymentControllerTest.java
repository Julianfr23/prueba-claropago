package com.pagoclaro.reconciliation.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pagoclaro.reconciliation.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pruebas de la API. MockMvc se arma a mano (no @AutoConfigureMockMvc) para reutilizar el MISMO contexto
 * de Spring que las demas pruebas: un segundo contexto volveria a ejecutar el DDL sobre la base en
 * memoria compartida y fallaria con "table already exists".
 */
@SpringBootTest
class SupportPaymentControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private MockMvc mvc;
    private TestData data;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        data = new TestData(jdbc);
        data.clean();
        data.loadSamples();
    }

    @Test
    @DisplayName("POST reconciliation de PAY-BX17: RECONCILED y, al repetir, CONSISTENT sin cambios")
    void reconcileThenRepeatIsIdempotent() throws Exception {
        mvc.perform(post("/api/support/payments/{ref}/reconciliation", "PAY-BX17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("RECONCILED"))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.reasonCode").value("DUPLICATE_DEBIT_REVERSED"))
                .andExpect(jsonPath("$.paymentStatus").value("CONFIRMED"));

        int ledgerRows = data.count("ledger_entry", 2);

        mvc.perform(post("/api/support/payments/{ref}/reconciliation", "PAY-BX17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONSISTENT"))
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.actions").isEmpty());

        org.assertj.core.api.Assertions.assertThat(data.count("ledger_entry", 2)).isEqualTo(ledgerRows);
    }

    @Test
    @DisplayName("POST de un caso contradictorio responde 200 con MANUAL_REVIEW y el motivo, sin tocar datos")
    void contradictoryCaseReturnsManualReview() throws Exception {
        mvc.perform(post("/api/support/payments/{ref}/reconciliation", "PAY-CQ22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.reasonCode").value("EVIDENCE_CONTRADICTORY"))
                .andExpect(jsonPath("$.changed").value(false))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
    }

    @Test
    @DisplayName("GET diagnosis es de solo lectura: informa CORRECTABLE y el ledger queda igual")
    void diagnosisIsReadOnly() throws Exception {
        mvc.perform(get("/api/support/payments/{ref}/diagnosis", "PAY-BX17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CORRECTABLE"))
                .andExpect(jsonPath("$.changed").value(false));

        org.assertj.core.api.Assertions.assertThat(data.count("ledger_entry", 2)).isEqualTo(2);
    }

    @Test
    @DisplayName("Referencia inexistente => 404 con mensaje generico")
    void unknownReferenceReturns404() throws Exception {
        mvc.perform(post("/api/support/payments/{ref}/reconciliation", "PAY-NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Pago no encontrado"));
    }

    @Test
    @DisplayName("Referencia con caracteres no permitidos o demasiado larga => 400")
    void invalidReferenceReturns400() throws Exception {
        mvc.perform(post("/api/support/payments/{ref}/reconciliation", "PAY BX17;DROP"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/support/payments/{ref}/diagnosis", "A".repeat(41)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Metodo no permitido conserva su 405 (el manejador de errores no lo convierte en 500)")
    void wrongMethodKeeps405() throws Exception {
        mvc.perform(get("/api/support/payments/{ref}/reconciliation", "PAY-BX17"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("La respuesta no expone SQL, nombres de tablas ni ids internos del ledger")
    void responseDoesNotLeakInternals() throws Exception {
        mvc.perform(post("/api/support/payments/{ref}/reconciliation", "PAY-BX17"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("LEDGER_ENTRY"))))
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("5003"))))
                .andExpect(content().string(not(containsString("Exception"))));
    }
}
