package com.pagoclaro.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Pago. Los pagos los crea otro sistema: esta aplicacion solo los lee y, como unica escritura,
 * alinea su estado cuando la evidencia del proveedor es suficiente.
 *
 * <p>{@code rowVersion} mapea la columna ROW_VERSION existente como version optimista: es una segunda
 * barrera (ademas del bloqueo pesimista) contra escrituras concurrentes de otros procesos.
 */
@Entity
@Table(name = "PAYMENT")
public class Payment {

    @Id
    @Column(name = "ID")
    private Long id;

    @Column(name = "EXTERNAL_REF", nullable = false, updatable = false)
    private String externalRef;

    @Column(name = "STATUS", nullable = false)
    private String status;

    @Column(name = "AMOUNT", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "ROW_VERSION", nullable = false)
    private Long rowVersion;

    protected Payment() {
        // requerido por JPA
    }

    public Long getId() {
        return id;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public String getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getRowVersion() {
        return rowVersion;
    }

    public void changeStatus(String newStatus, LocalDateTime now) {
        this.status = newStatus;
        this.updatedAt = now;
    }
}
