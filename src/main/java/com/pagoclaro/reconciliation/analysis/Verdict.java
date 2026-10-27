package com.pagoclaro.reconciliation.analysis;

/** Resultado del analisis de un pago, antes de tocar la base de datos. */
public enum Verdict {
    /** El pago ya es coherente con la evidencia: no se hace nada. */
    CONSISTENT,
    /** La evidencia es suficiente y existe una correccion segura y acotada. */
    CORRECTABLE,
    /** Evidencia contradictoria/insuficiente o datos anomalos: no se toca nada, se senala el caso. */
    MANUAL_REVIEW
}
