package com.pagoclaro.reconciliation.web;

import com.pagoclaro.reconciliation.service.ReconciliationResult;
import com.pagoclaro.reconciliation.service.ReconciliationService;
import java.util.regex.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operaciones de soporte N3 sobre un pago, identificado por su referencia externa.
 *
 * <p>SEGURIDAD: este ejercicio no incluye autenticacion/autorizacion. En un despliegue real estos
 * endpoints deben quedar detras de la capa de seguridad de la organizacion (rol de soporte N3) y
 * registrar el usuario que ejecuta la operacion. Ver docs/DECISIONES.md.
 */
@RestController
@RequestMapping("/api/support/payments")
public class SupportPaymentController {

    /** Lista blanca: alfanumerico, guion y guion bajo; coincide con VARCHAR2(40). Evita inyeccion en logs. */
    private static final Pattern EXTERNAL_REF = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,39}$");

    private final ReconciliationService service;

    public SupportPaymentController(ReconciliationService service) {
        this.service = service;
    }

    /** Diagnostico de solo lectura: que haria la conciliacion, sin modificar nada. */
    @GetMapping("/{externalRef}/diagnosis")
    public ReconciliationResult diagnose(@PathVariable("externalRef") String externalRef) {
        return service.diagnose(validated(externalRef));
    }

    /** Concilia el pago. Es idempotente: repetirla sobre un caso resuelto no genera nuevos efectos. */
    @PostMapping("/{externalRef}/reconciliation")
    public ReconciliationResult reconcile(@PathVariable("externalRef") String externalRef) {
        return service.reconcile(validated(externalRef));
    }

    private static String validated(String externalRef) {
        if (externalRef == null || !EXTERNAL_REF.matcher(externalRef).matches()) {
            throw new InvalidReferenceException();
        }
        return externalRef;
    }
}
