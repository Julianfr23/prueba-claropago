package com.pagoclaro.reconciliation.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    /** Reloj inyectable para que los timestamps de ledger/auditoria sean controlables en pruebas. */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
