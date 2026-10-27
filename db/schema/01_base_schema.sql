-- =====================================================================
-- 01_base_schema.sql
-- Modelo de datos del enunciado (seccion 2.2) para entornos locales/de prueba.
-- En Oracle de produccion estas tablas YA EXISTEN: este script no se ejecuta alli.
--
-- Unica diferencia con el enunciado: se agrega precision explicita a los NUMBER
-- (ids NUMBER(19), row_version NUMBER(10)) para que el mapeo a Long sea determinista
-- tanto en Oracle como en H2 (modo Oracle). Y se agregan las dos secuencias que usa
-- la aplicacion para insertar en LEDGER_ENTRY y SUPPORT_AUDIT.
-- =====================================================================

CREATE TABLE PAYMENT (
    id            NUMBER(19)     PRIMARY KEY,
    external_ref  VARCHAR2(40)   UNIQUE NOT NULL,
    status        VARCHAR2(20)   NOT NULL,   -- PENDING | CONFIRMED | REJECTED
    amount        NUMBER(18,2)   NOT NULL,
    updated_at    TIMESTAMP      NOT NULL,
    row_version   NUMBER(10)     DEFAULT 0 NOT NULL
);

CREATE TABLE PROVIDER_EVENT (
    id                 NUMBER(19)    PRIMARY KEY,
    payment_id         NUMBER(19)    NOT NULL REFERENCES PAYMENT(id),
    provider_event_id  VARCHAR2(60)  UNIQUE NOT NULL,
    provider_status    VARCHAR2(20)  NOT NULL,  -- APPROVED | DECLINED
    received_at        TIMESTAMP     NOT NULL
);

CREATE TABLE LEDGER_ENTRY (
    id                NUMBER(19)    PRIMARY KEY,
    payment_id        NUMBER(19)    NOT NULL REFERENCES PAYMENT(id),
    entry_type        VARCHAR2(20)  NOT NULL,   -- DEBIT | REVERSAL
    amount            NUMBER(18,2)  NOT NULL,
    related_entry_id  NUMBER(19)    NULL REFERENCES LEDGER_ENTRY(id),
    created_at        TIMESTAMP     NOT NULL
);

CREATE TABLE SUPPORT_AUDIT (
    id          NUMBER(19)     PRIMARY KEY,
    payment_id  NUMBER(19)     NOT NULL REFERENCES PAYMENT(id),
    action      VARCHAR2(40)   NOT NULL,
    reason      VARCHAR2(400)  NOT NULL,
    created_at  TIMESTAMP      NOT NULL
);

-- Secuencias: arrancan lejos de los ids existentes para no colisionar con datos historicos.
-- En Oracle real: ajustar START WITH a MAX(id)+margen antes de usar la aplicacion.
CREATE SEQUENCE LEDGER_ENTRY_SEQ START WITH 100000 INCREMENT BY 1;
CREATE SEQUENCE SUPPORT_AUDIT_SEQ START WITH 100000 INCREMENT BY 1;
