package com.pagoclaro.reconciliation.web;

import com.pagoclaro.reconciliation.service.PaymentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduce errores a respuestas seguras (RFC 7807). Los detalles tecnicos van SOLO al log del servidor;
 * la respuesta nunca incluye SQL, nombres de tablas, ids internos ni trazas.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidReferenceException.class)
    public ProblemDetail invalidReference() {
        return problem(HttpStatus.BAD_REQUEST, "Referencia invalida",
                "La referencia externa debe ser alfanumerica (guion y guion bajo permitidos) de maximo 40 caracteres.");
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ProblemDetail notFound() {
        return problem(HttpStatus.NOT_FOUND, "Pago no encontrado",
                "No existe un pago con la referencia indicada.");
    }

    /** Otra conciliacion sobre el mismo pago esta en curso (o hubo escritura concurrente): es seguro reintentar. */
    @ExceptionHandler({PessimisticLockingFailureException.class,
            ObjectOptimisticLockingFailureException.class,
            DataIntegrityViolationException.class})
    public ProblemDetail conflict(Exception e) {
        log.warn("concurrency conflict while reconciling: {}", e.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "Operacion en conflicto",
                "Hay otra operacion en curso sobre este pago. Reintente en unos segundos y revise el diagnostico.");
    }

    /** Solo RuntimeException: los errores de MVC (404/405/...) son ServletException y conservan su codigo. */
    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail unexpected(RuntimeException e) {
        log.error("unexpected error", e);   // detalle completo solo en el servidor
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno",
                "No fue posible completar la operacion. Contacte al equipo de desarrollo con la hora del intento.");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
