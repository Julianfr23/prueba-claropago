package com.pagoclaro.reconciliation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** Registro de auditoria de soporte: solo se inserta, nunca se actualiza. */
@Entity
@Table(name = "SUPPORT_AUDIT")
public class SupportAudit {

    private static final int MAX_REASON_LENGTH = 400;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "support_audit_gen")
    @SequenceGenerator(name = "support_audit_gen", sequenceName = "SUPPORT_AUDIT_SEQ", allocationSize = 1)
    @Column(name = "ID", updatable = false)
    private Long id;

    @Column(name = "PAYMENT_ID", nullable = false, updatable = false)
    private Long paymentId;

    @Column(name = "ACTION", nullable = false, updatable = false, length = 40)
    private String action;

    @Column(name = "REASON", nullable = false, updatable = false, length = MAX_REASON_LENGTH)
    private String reason;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected SupportAudit() {
        // requerido por JPA
    }

    public SupportAudit(Long paymentId, String action, String reason, LocalDateTime createdAt) {
        this.paymentId = paymentId;
        this.action = action;
        this.reason = reason.length() > MAX_REASON_LENGTH ? reason.substring(0, MAX_REASON_LENGTH) : reason;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public String getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
