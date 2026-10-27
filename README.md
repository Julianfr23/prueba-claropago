# Conciliación segura de pagos — Incidente N3 (PagoClaro)
 
Herramienta de soporte N3 en **Java 17 + Spring Boot 3.5** que permite **diagnosticar y conciliar un pago por su
referencia externa** a partir de la evidencia en base de datos (`PAYMENT`, `PROVIDER_EVENT`, `LEDGER_ENTRY`),
sin adivinar, sin tocar movimientos existentes y de forma **idempotente y segura ante concurrencia**.
 
| Documento | Contenido |
|---|---|
| [`docs/DIAGNOSTICO.md`](docs/DIAGNOSTICO.md) | Hechos observados, hipótesis, validaciones en producción y corrección propuesta |
| [`docs/DECISIONES.md`](docs/DECISIONES.md) | Decisiones técnicas (contexto, alternativas, decisión, trade-offs, riesgos, verificación) |
| [`docs/NOTA_SOPORTE.md`](docs/NOTA_SOPORTE.md) | Nota operativa corta: qué casos son seguros, cuándo detenerse, qué revisar después |
| [`docs/AI_USAGE.md`](docs/AI_USAGE.md) | Trazabilidad del uso de IA |
 
---
 
## 1. Cómo ejecutar
 
**Requisitos:** JDK 17+ y Maven 3.9+. No se necesita Oracle ni Docker: por defecto se usa **H2 en modo Oracle**
(en memoria). Los scripts de `db/` son la única fuente del esquema y se cargan tanto en la app como en las pruebas.
 
```bash
# Pruebas automatizadas (unitarias + integración sobre H2 modo Oracle)
mvn test
 
# Levantar la API con los 4 pagos de muestra del enunciado
mvn spring-boot:run -Dspring-boot.run.profiles=demo
```
 
> En **Windows PowerShell** el argumento `-D` debe ir entre comillas:
> `mvn spring-boot:run "-Dspring-boot.run.profiles=demo"`
 
### Probar la API (perfil `demo`)
 
**Linux / macOS / Git Bash:**
 
```bash
# 1) Diagnóstico de SOLO LECTURA: qué haría la conciliación
curl -s localhost:8080/api/support/payments/PAY-BX17/diagnosis
 
# 2) Conciliar (aplica solo correcciones seguras)
curl -s -X POST localhost:8080/api/support/payments/PAY-BX17/reconciliation
 
# 3) Repetir: no genera nuevos efectos (outcome=CONSISTENT, changed=false)
curl -s -X POST localhost:8080/api/support/payments/PAY-BX17/reconciliation
 
# Caso contradictorio: no se corrige nada, se devuelve el motivo
curl -s -X POST localhost:8080/api/support/payments/PAY-CQ22/reconciliation
```
 
**Windows (PowerShell)** — `curl` es un alias de `Invoke-WebRequest`; se usa `Invoke-RestMethod`:
 
```powershell
# En una terminal: levantar la API
mvn spring-boot:run "-Dspring-boot.run.profiles=demo"
 
# En otra terminal:
# 1) Diagnóstico de solo lectura
Invoke-RestMethod http://localhost:8080/api/support/payments/PAY-BX17/diagnosis | ConvertTo-Json
 
# 2) Conciliar
Invoke-RestMethod -Method Post http://localhost:8080/api/support/payments/PAY-BX17/reconciliation | ConvertTo-Json
 
# 3) Repetir: CONSISTENT, changed=false
Invoke-RestMethod -Method Post http://localhost:8080/api/support/payments/PAY-BX17/reconciliation | ConvertTo-Json
 
# Caso contradictorio: MANUAL_REVIEW
Invoke-RestMethod -Method Post http://localhost:8080/api/support/payments/PAY-CQ22/reconciliation | ConvertTo-Json
 
# Pago que solo requiere alinear estado (PENDING -> CONFIRMED)
Invoke-RestMethod -Method Post http://localhost:8080/api/support/payments/PAY-AX91/reconciliation | ConvertTo-Json
```
 
Pago inexistente: devuelve `404` (PowerShell lo muestra como error en rojo; es el comportamiento esperado).
 
Respuesta típica de `PAY-BX17` la primera vez (sin ids internos ni detalles de BD):
 
```json
{
  "externalRef": "PAY-BX17",
  "outcome": "RECONCILED",
  "reasonCode": "DUPLICATE_DEBIT_REVERSED",
  "message": "Habia debitos duplicados: se registran reversos para dejar un unico debito neto.",
  "changed": true,
  "paymentStatus": "CONFIRMED",
  "expectedAmount": 89000.00,
  "netDebitBefore": 178000.00,
  "netDebitAfter": 89000.00,
  "actions": ["REVERSAL_POSTED"]
}
```
 
| Endpoint | Efecto | Resultados |
|---|---|---|
| `GET  /api/support/payments/{ref}/diagnosis` | Solo lectura | `CONSISTENT`, `CORRECTABLE`, `MANUAL_REVIEW` |
| `POST /api/support/payments/{ref}/reconciliation` | Aplica correcciones seguras | `CONSISTENT`, `RECONCILED`, `MANUAL_REVIEW` |
 
Errores (formato RFC 7807, sin trazas ni SQL): `400` referencia inválida · `404` pago inexistente ·
`409` otra operación en curso sobre el mismo pago (es seguro reintentar) · `500` genérico.
 
### Contra Oracle real
 
```bash
# 1. Aplicar (una vez, revisados): db/schema/02_integrity_and_indexes.sql (y opcional 03_oracle_check_constraints.sql)
#    y db/plsql/sp_reconcile_payment.sql. Ajustar START WITH de las secuencias a MAX(id)+margen.
# 2. Credenciales SOLO por entorno (nunca en el repo):
ORACLE_URL='jdbc:oracle:thin:@//host:1521/SERVICIO' ORACLE_USER=... ORACLE_PASSWORD=... \
  mvn -Poracle spring-boot:run -Dspring-boot.run.profiles=oracle
```
 
---
 
## 2. Reglas de conciliación
 
Implementadas en `analysis/ReconciliationAnalyzer` (Java puro, sin Spring ni BD) y replicadas en
`db/plsql/sp_reconcile_payment.sql`. **Nunca** se borra ni se actualiza el ledger; la única corrección contable
es **agregar** un `REVERSAL` relacionado (`related_entry_id`) con el débito duplicado.
 
| Evidencia del proveedor | Pago / ledger | Acción |
|---|---|---|
| APPROVED **y** DECLINED | cualquiera | **Revisión manual** (`EVIDENCE_CONTRADICTORY`). No se decide por fecha ni por cantidad |
| Estado/evento fuera del dominio, o ledger anómalo (reverso parcial, huérfano, doble, monto ≤ 0) | cualquiera | **Revisión manual** |
| Solo APPROVED | `PENDING`, 1 débito vigente = monto | `status → CONFIRMED` (**PAY-AX91**) |
| Solo APPROVED | 2+ débitos vigentes = monto | Se conserva el más antiguo y se **reversan los posteriores** (**PAY-BX17**) |
| Solo APPROVED | sin débito vigente, o monto ≠ pago, o pago `REJECTED` | **Revisión manual** (no se postea un débito por cuenta propia) |
| Solo APPROVED | `CONFIRMED`, 1 débito = monto | Consistente (**PAY-DK44**) |
| Solo DECLINED | `PENDING` y débito neto 0 | `status → REJECTED` |
| Solo DECLINED | débito neto ≠ 0, o `CONFIRMED` | **Revisión manual** |
| Sin eventos | `PENDING` sin movimientos | Consistente (esperando al proveedor) |
| Sin eventos | cualquier otro caso | **Revisión manual** (`NO_PROVIDER_EVIDENCE`) |
 
### Idempotencia y concurrencia (resumen)
 
* **Idempotencia por convergencia:** el resultado se deriva del estado actual. Tras corregir, el mismo análisis
  devuelve `CONSISTENT` y no inserta nada. La auditoría de revisión manual usa un texto determinista y se inserta
  una sola vez por `(pago, acción, razón)`.
* **Concurrencia:** `SELECT … FOR UPDATE` sobre la fila de `PAYMENT` serializa ejecuciones del mismo pago; la segunda
  espera, ve lo ya confirmado y no hace nada. Como red de seguridad, `row_version` (`@Version`) y un
  **índice único** sobre `related_entry_id` impiden un doble reverso aunque otro proceso escriba sin pasar por el bloqueo.
* **Atomicidad:** reversos + cambio de estado + auditoría se confirman o se revierten juntos (una transacción).
---
 
## 3. Artefactos de base de datos
 
| Archivo | Qué es | Cuándo se usa |
|---|---|---|
| `db/queries/01_detect_inconsistencies_24h.sql` | Consulta de detección (últimas 24 h): diferencia monto/débito neto, estado incompatible con la evidencia, evidencia contradictoria | Soporte / job de monitoreo. **Se ejecuta en las pruebas** |
| `db/plsql/sp_reconcile_payment.sql` | Procedimiento `sp_reconcile_payment(p_external_ref, p_outcome OUT, p_reason_code OUT)`, atómico (SAVEPOINT + bloqueo `WAIT 10`), sin `COMMIT` interno | Soporte con acceso directo a BD o si la app no está disponible |
| `db/plsql/validate_sp_reconcile_payment.sql` | Prueba de humo del PL/SQL (BX17, CQ22, AX91, repetición, inexistente) que termina en `ROLLBACK` | Validación reproducible en un Oracle de pruebas |
| `db/schema/01_base_schema.sql` | Modelo del enunciado + 2 secuencias, para H2/entornos de prueba | No se ejecuta en producción |
| `db/schema/02_integrity_and_indexes.sql` | **Opcional.** Índice único `related_entry_id` (un débito se reversa una sola vez) + índices de lectura/ventana. Incluye pre-chequeo de duplicados históricos | Revisar y aplicar con DBA |
| `db/schema/03_oracle_check_constraints.sql` | **Opcional, solo Oracle.** `CHECK` de dominios, `ENABLE NOVALIDATE` | Revisar y aplicar con DBA |
| `db/seed/demo_sample_data.sql` | Los 4 casos de muestra (fechas relativas a "ahora") | Solo perfil `demo` |
 
**Cuándo el procedimiento modifica datos:** únicamente en los tres casos corregibles de la tabla anterior
(estado → `CONFIRMED`/`REJECTED`, y `REVERSAL` de débitos duplicados) y, en revisión manual, con **un** `INSERT`
en `SUPPORT_AUDIT`. Detalle completo en el encabezado del propio script.
 
### Validación del PL/SQL (limitación declarada)
 
El procedimiento y la consulta están escritos para Oracle, pero **no se ejecutó el PL/SQL contra una instancia Oracle**
en esta entrega (no se dispuso de una). La consulta de detección sí se ejecuta en las pruebas sobre H2 en modo Oracle.
Estrategia reproducible para el PL/SQL:
 
```bash
docker run -d -p 1521:1521 -e ORACLE_PASSWORD=local gvenzl/oracle-free
# Conectar (usuario de pruebas) y ejecutar en orden:
#   db/schema/01_base_schema.sql  ->  db/schema/02_integrity_and_indexes.sql
#   db/plsql/sp_reconcile_payment.sql  ->  db/plsql/validate_sp_reconcile_payment.sql
# Esperado: "OK: todas las verificaciones pasaron"
```
 
---
 
## 4. Pruebas
 
`mvn test` ejecuta:
 
| Clase | Tipo | Qué demuestra |
|---|---|---|
| `ReconciliationAnalyzerTest` | Unitaria (sin Spring/BD) | Cada regla de negocio y los bordes (reverso parcial/huérfano/doble, montos, estados fuera de dominio, evidencia contradictoria) |
| `ReconciliationServiceTest` | Integración (H2 modo Oracle) | Los 4 casos de muestra; ledger existente **inmutable**; **repetición no duplica** reversos, auditoría ni `row_version`; **6 hilos simultáneos** sobre el mismo pago → exactamente 1 efecto; bordes (inexistente, reverso parcial, ya reversado, estado desconocido) |
| `SupportPaymentControllerTest` | Integración (MockMvc) | Contrato HTTP, 400/404/405, idempotencia vía API, respuesta sin datos internos |
| `DetectionQueryTest` | Integración (SQL real) | Ejecuta el archivo `.sql` entregado: clasifica AX91/BX17/CQ22, excluye DK44, respeta la ventana de 24 h y converge tras conciliar |
 
**Nota técnica:** todas las pruebas con `@SpringBootTest` comparten **un único contexto** (no se usa `@AutoConfigureMockMvc`)
porque la base en memoria es compartida y un segundo contexto volvería a ejecutar el DDL.
 
---
 
## 5. Supuestos sobre la base de datos
 
* Oracle 12c+ (usa `FOR UPDATE WAIT`, `LOCALTIMESTAMP`, `INTERVAL`). La consulta y el esquema se ejercitan en H2 modo Oracle.
* `LEDGER_ENTRY.related_entry_id` solo lo usan los reversos y cada débito se reversa **como máximo una vez y por su monto completo**.
* `PAYMENT.updated_at`, `received_at` y `created_at` están en la misma zona horaria que la sesión de BD.
* No existe, en este modelo, un vínculo entre un movimiento del ledger y el `provider_event` que lo originó. Por eso se
  asume que el débito **más antiguo** corresponde al intento original. Riesgo y cómo validarlo: `docs/DIAGNOSTICO.md`.
* Las secuencias `LEDGER_ENTRY_SEQ` y `SUPPORT_AUDIT_SEQ` se crean/ajustan fuera de la aplicación (en Oracle real,
  `START WITH` > `MAX(id)`).
* La API no incluye autenticación: en producción debe ir detrás de la seguridad de la organización (rol N3) y
  registrar el usuario ejecutor (ver `docs/DECISIONES.md`, ADR-9).
---
 
## 6. Qué queda pendiente y siguiente paso
 
| Pendiente | Por qué se priorizó así | Siguiente paso |
|---|---|---|
| Ejecutar y validar el PL/SQL en Oracle real | Sin instancia Oracle disponible; se prefirió dejar una prueba de humo reproducible | Correr `validate_sp_reconcile_payment.sql` en un contenedor `gvenzl/oracle-free` (idealmente automatizado con Testcontainers) |
| Corregir la **causa raíz** (check-then-act en el reintento) | Excede el alcance de "conciliar"; el código del flujo de pago no está en la evidencia | Clave de idempotencia en la llamada `APPROVE` + `provider_event_id` en `LEDGER_ENTRY` con índice único (ADR-7) |
| Autenticación/autorización y usuario en la auditoría | Fuera del alcance del reto | Integrar con el SSO de la organización y guardar el actor en `SUPPORT_AUDIT` |
| Conciliación por lote (job sobre el resultado de la consulta 24 h) | El reto pide operar por pago | Job programado que invoque el diagnóstico y encole solo los `CORRECTABLE` |
| Confirmar con el proveedor que el 2.º `APPROVED` de BX17 no fue un segundo cobro real | Dato externo no disponible | Ver "Validaciones en producción" en `docs/DIAGNOSTICO.md` |