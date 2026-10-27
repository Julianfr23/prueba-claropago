package com.pagoclaro.reconciliation.repository;

import com.pagoclaro.reconciliation.domain.LedgerEntry;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findByPaymentIdOrderByCreatedAtAscIdAsc(Long paymentId);
}
