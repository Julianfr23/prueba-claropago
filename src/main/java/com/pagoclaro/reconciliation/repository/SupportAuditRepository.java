package com.pagoclaro.reconciliation.repository;

import com.pagoclaro.reconciliation.domain.SupportAudit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportAuditRepository extends JpaRepository<SupportAudit, Long> {

    boolean existsByPaymentIdAndActionAndReason(Long paymentId, String action, String reason);
}
