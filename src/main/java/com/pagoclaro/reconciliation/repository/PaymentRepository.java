package com.pagoclaro.reconciliation.repository;

import com.pagoclaro.reconciliation.domain.Payment;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Lectura sin bloqueo: para el diagnostico (solo lectura). */
    Optional<Payment> findByExternalRef(String externalRef);

    /**
     * SELECT ... FOR UPDATE sobre la fila del pago. Serializa conciliaciones concurrentes del MISMO
     * pago; la segunda espera hasta 10 s (en Oracle: FOR UPDATE WAIT 10) y, si no obtiene el bloqueo,
     * falla con un error de bloqueo que la API traduce a 409 en lugar de colgarse indefinidamente.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "10000"))
    @Query("select p from Payment p where p.externalRef = :externalRef")
    Optional<Payment> findByExternalRefForUpdate(@Param("externalRef") String externalRef);
}
