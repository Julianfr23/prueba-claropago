package com.pagoclaro.reconciliation.analysis;

/**
 * Codigos de motivo estables. {@code message} es el texto seguro que se devuelve por la API
 * (sin ids internos ni detalles de base de datos). {@code auditDetail} es el texto fijo que se
 * guarda en SUPPORT_AUDIT para casos de revision manual; al ser determinista, repetir la
 * operacion produce exactamente la misma razon y por eso no se duplica el registro de auditoria.
 * (El procedimiento PL/SQL usa estos mismos textos.)
 */
public enum ReasonCode {

    // --- Sin cambios ---------------------------------------------------------------
    ALREADY_CONSISTENT(
            "El pago es consistente con la evidencia del proveedor y con el ledger.", null),
    PENDING_AWAITING_PROVIDER(
            "El pago sigue pendiente y aun no hay evidencia del proveedor: no hay nada que conciliar.", null),

    // --- Correcciones seguras -------------------------------------------------------
    STATUS_ALIGNED_TO_APPROVAL(
            "El proveedor aprobo el pago: el estado se alinea a CONFIRMED.", null),
    STATUS_ALIGNED_TO_DECLINE(
            "El proveedor rechazo el pago y no hay movimientos contables: el estado se alinea a REJECTED.", null),
    DUPLICATE_DEBIT_REVERSED(
            "Habia debitos duplicados: se registran reversos para dejar un unico debito neto.", null),

    // --- Revision manual ------------------------------------------------------------
    UNKNOWN_PAYMENT_STATUS(
            "El pago tiene un estado no soportado. Requiere revision manual.",
            "payment status not in PENDING|CONFIRMED|REJECTED"),
    UNKNOWN_PROVIDER_STATUS(
            "Hay eventos del proveedor con un estado no soportado. Requiere revision manual.",
            "provider event with status not in APPROVED|DECLINED"),
    EVIDENCE_CONTRADICTORY(
            "La evidencia del proveedor es contradictoria (aprobado y rechazado). Requiere revision manual.",
            null /* se construye con los conteos */),
    LEDGER_ANOMALY(
            "El ledger contiene movimientos anomalos (tipo/monto invalido o reversos mal enlazados). Requiere revision manual.",
            "invalid ledger entries (unknown type, non-positive amount, unlinked/partial/double reversal)"),
    NO_PROVIDER_EVIDENCE(
            "No hay evidencia del proveedor que respalde el estado o los movimientos del pago. Requiere revision manual.",
            "no provider events and payment is not an empty PENDING"),
    DECLINED_WITH_NET_DEBIT(
            "El proveedor rechazo el pago pero existe un debito neto. Requiere revision manual.",
            "provider declined but net debit is not zero"),
    CONFIRMED_BUT_DECLINED(
            "El pago figura CONFIRMED pero el proveedor lo rechazo. Requiere revision manual.",
            "payment CONFIRMED but provider evidence is DECLINED"),
    REJECTED_BUT_APPROVED(
            "El pago figura REJECTED pero el proveedor lo aprobo. Requiere revision manual.",
            "payment REJECTED but provider evidence is APPROVED"),
    MISSING_DEBIT(
            "El proveedor aprobo el pago pero no existe un debito vigente. Requiere revision manual.",
            "provider approved but no active debit exists"),
    LEDGER_AMOUNT_MISMATCH(
            "El monto de algun debito vigente no coincide con el monto del pago. Requiere revision manual.",
            "active debit amount differs from payment amount");

    private final String message;
    private final String auditDetail;

    ReasonCode(String message, String auditDetail) {
        this.message = message;
        this.auditDetail = auditDetail;
    }

    public String message() {
        return message;
    }

    public String auditDetail() {
        return auditDetail;
    }
}
