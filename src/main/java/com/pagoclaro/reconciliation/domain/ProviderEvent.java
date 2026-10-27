package com.pagoclaro.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Evento recibido del proveedor. Solo lectura desde esta aplicacion. */
@Entity
@Table(name = "PROVIDER_EVENT")
public class ProviderEvent {

    @Id
    @Column(name = "ID")
    private Long id;

    @Column(name = "PAYMENT_ID", nullable = false)
    private Long paymentId;

    @Column(name = "PROVIDER_EVENT_ID", nullable = false)
    private String providerEventId;

    @Column(name = "PROVIDER_STATUS", nullable = false)
    private String providerStatus;

    @Column(name = "RECEIVED_AT", nullable = false)
    private LocalDateTime receivedAt;

    protected ProviderEvent() {
        // requerido por JPA
    }

    public Long getId() {
        return id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
