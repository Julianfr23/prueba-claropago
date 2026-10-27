package com.pagoclaro.reconciliation.repository;

import com.pagoclaro.reconciliation.domain.ProviderEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderEventRepository extends JpaRepository<ProviderEvent, Long> {

    List<ProviderEvent> findByPaymentId(Long paymentId);
}
