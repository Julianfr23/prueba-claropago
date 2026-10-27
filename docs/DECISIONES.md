# Decisiones técnicas

Formato: **Contexto → Alternativas → Decisión → Trade-offs/Riesgos → Verificación.**

---

## ADR-1 · Concurrencia: bloqueo pesimista de la fila de `PAYMENT`

**Contexto.** Dos solicitudes sobre el mismo pago pueden llegar casi a la vez; ambas leerían "2 débitos" e insertarían
cada una su reverso, el neto quedaría en 0, otro estado inválido.

**Alternativas.** (a) Solo bloqueo optimista con `row_version`: detecta el conflicto pero hace fallar a una de las dos y
exige reintento. (b) Aislamiento `SERIALIZABLE`: más costoso y con errores `ORA-08177` difíciles de explicar a soporte.
(c) Tabla de "solicitudes en curso": infraestructura extra. (d) **`SELECT … FOR UPDATE` sobre `PAYMENT`.**

**Decisión.** (d) como mecanismo principal, con `@Version` (`row_version`) como segunda barrera y espera acotada
(10 s; en Oracle `FOR UPDATE WAIT 10`). Tras obtener el bloqueo se **refresca** la fila y se lee el ledger *con el
bloqueo ya tomado*, así la segunda solicitud ve lo que confirmó la primera.

**Trade-offs/Riesgos.** Serializa por pago (aceptable: el volumen es manual/N3). Si la espera supera 10 s se devuelve
`409` y es seguro reintentar. Un proceso externo que escriba en el ledger sin tomar el bloqueo no queda cubierto por (d):
para eso existe el índice único de ADR-7.

**Verificación.** `ReconciliationServiceTest` lanza 6 hilos simultáneos sobre `PAY-BX17` y `PAY-AX91`: exactamente un
`RECONCILED`, el resto `CONSISTENT`, un solo reverso, una sola actualización de estado y una sola auditoría.

---

## ADR-2 · Idempotencia por convergencia (sin tabla de claves de idempotencia)

**Contexto.** "La misma solicitud puede repetirse por error operativo o reintento técnico" y no debe duplicar efectos
contables ni auditoría.

**Alternativas.** (a) Tabla de `request_id` ya procesados. (b) Cabecera `Idempotency-Key` obligatoria. (c) **Derivar el
resultado del estado actual**: la conciliación recalcula y solo aplica la diferencia.

**Decisión.** (c). Tras corregir, el análisis converge a `CONSISTENT` y no se inserta nada. Para la auditoría de
revisión manual, el texto de la razón es **determinista** y se inserta solo si no existe ya `(pago, acción, razón)`.

**Trade-offs/Riesgos.** No distingue "misma solicitud" de "nueva solicitud sobre el mismo estado": no es necesario,
porque ambas producen el mismo efecto (ninguno). Si la evidencia cambia (llega un evento nuevo), la razón puede cambiar y
se registra una auditoría nueva: es lo deseado. Requiere que la razón no incluya datos volátiles (por eso no lleva hora
ni montos).

**Verificación.** Repetir 5 veces `PAY-BX17` no agrega movimientos, auditoría ni cambia `row_version`; repetir
`PAY-CQ22` 4 veces deja una sola auditoría; lo mismo vía API y vía PL/SQL (`validate_sp_reconcile_payment.sql`).

---

## ADR-3 · Evidencia contradictoria o insuficiente → revisión manual, nunca se decide

**Contexto.** `PAY-CQ22` tiene un `APPROVED` y un `DECLINED`. "El sistema no debe adivinar".

**Alternativas.** Gana el evento más reciente; gana la mayoría; gana `DECLINED` por prudencia. **Decisión:** ninguna.

**Decisión.** Cualquier contradicción, estado fuera de dominio, ledger anómalo o ausencia de evidencia que respalde el
estado devuelve `MANUAL_REVIEW` con un código estable; se conserva todo y se registra **una** auditoría. Cada código está
documentado en `ReasonCode` y en el README.

**Trade-offs/Riesgos.** Más casos terminan en manos humanas (falsos "manuales") a cambio de **cero** correcciones
incorrectas sobre dinero. El orden temporal no es fiable: un `DECLINED` posterior puede ser una anulación y un
`APPROVED` posterior un reintento exitoso.

**Verificación.** Pruebas unitarias por cada código de `MANUAL_REVIEW` y la prueba de integración de `PAY-CQ22`
(sin cambios de estado, sin movimientos, 1 auditoría).

---

## ADR-4 · Forma de la corrección contable

**Contexto.** Los movimientos son inmutables; una corrección es un reverso relacionado.

**Decisión.**
* Duplicados: se **conserva el débito vigente más antiguo** y se inserta un `REVERSAL` por el **monto completo** de cada
  débito vigente posterior (`related_entry_id` = ese débito). Solo si todos los débitos vigentes tienen el monto del pago.
* **No** se postea un débito faltante (`MISSING_DEBIT` → manual): podría existir una contabilización asíncrona en curso
  y se duplicaría después.
* **No** se reversa un débito de un pago `DECLINED`/`REJECTED`: implica mover dinero sin confirmación (→ manual).
* Un reverso parcial/huérfano/doble preexistente es una anomalía (→ manual), no se "compensa" encima.

**Alternativas.** Reversar *todos* los débitos y re-debitar uno (2 movimientos de más, más superficie de error);
reversar el más reciente por tiempo (equivale a lo elegido, pero sin regla explícita de desempate: aquí `created_at, id`).

**Riesgo.** S1/S2 de `DIAGNOSTICO.md` (qué débito es el "original" y si hubo un segundo cobro real).

**Verificación.** Pruebas: el reverso apunta al débito posterior con su mismo monto; los movimientos previos quedan
byte a byte iguales (foto del ledger antes/después); tres débitos → dos reversos.

---

## ADR-5 · Reglas puras separadas de la persistencia

**Contexto.** Las reglas son lo más delicado y lo que más se debe poder probar y explicar.

**Decisión.** `ReconciliationAnalyzer` recibe *hechos* (estado/monto, estados de eventos, movimientos) y devuelve una
`Decision`; no conoce JPA ni Spring. `ReconciliationService` solo orquesta: bloquea, lee, decide, aplica inserciones.
El diagnóstico (`GET`) y la conciliación (`POST`) usan **la misma** decisión: lo que se previsualiza es lo que se aplica.

**Trade-offs.** Una capa más (mapeo entidad → hechos), a cambio de pruebas unitarias sin base de datos.

---

## ADR-6 · Dos implementaciones (Java y PL/SQL) de las mismas reglas

**Contexto.** El enunciado pide una API Spring Boot **y** un procedimiento PL/SQL invocable.

**Decisión.** Ambos implementan la misma tabla de reglas con los **mismos códigos y los mismos textos de auditoría**,
de modo que ejecutar uno y luego el otro sobre el mismo caso no duplica registros.

**Riesgos.** Divergencia con el tiempo. Mitigación: la tabla de reglas está en el README, y el script
`validate_sp_reconcile_payment.sql` reproduce los casos de las pruebas Java. **Limitación declarada:** el PL/SQL no se
ejecutó contra Oracle en esta entrega (ver README §3).

---

## ADR-7 · Esquema: cambios opcionales y causa raíz

**Contexto.** "No es obligatorio modificar el esquema". La API no depende de ellos para ser correcta.

**Decisión.** Se entregan como scripts **separados y opcionales**:
* `UX_LEDGER_ONE_REVERSAL_PER_ENTRY` (único en `related_entry_id`): un débito se reversa una sola vez, incluso ante
  escritores que no pasen por el bloqueo. Incluye pre-chequeo de duplicados históricos.
* Índices por `payment_id` y por las columnas de fecha usadas en la ventana de 24 h.
* `CHECK` de dominios en Oracle con `ENABLE NOVALIDATE` (protegen escrituras nuevas sin fallar por histórico).

**Causa raíz (propuesta, no implementada):** `provider_event_id` en `LEDGER_ENTRY` con índice único
`(payment_id, provider_event_id)` + clave de idempotencia en la llamada `APPROVE`. Ver `DIAGNOSTICO.md` §4.

---

## ADR-8 · Pruebas sobre H2 en modo Oracle

**Contexto.** No se debe exigir infraestructura extra; Oracle puede no estar disponible durante la evaluación.

**Decisión.** H2 `MODE=Oracle` ejecuta **los mismos** scripts de `db/` y la **misma** consulta de detección que se
entrega. El PL/SQL (propio de Oracle) se valida con un script de humo reproducible.

**Riesgos.** H2 no es Oracle: el comportamiento de bloqueo/espera y algunas funciones pueden diferir; por eso la
estrategia incluye ejecutar el PL/SQL y, idealmente, la suite completa contra `gvenzl/oracle-free` (Testcontainers) como
siguiente paso. Las pruebas `@SpringBootTest` comparten un solo contexto porque la base en memoria es compartida.

---

## ADR-9 · API, errores, logs y seguridad

* **Dos operaciones:** `GET diagnosis` (solo lectura) y `POST reconciliation` (aplica). Soporte puede ver antes de actuar.
* **Resultados de negocio con HTTP 200** (`CONSISTENT`/`RECONCILED`/`MANUAL_REVIEW`): una revisión manual no es un error
  del servidor. `409` solo para conflictos de concurrencia; `404`/`400` para referencia inexistente/inválida.
* **Respuesta sin internos:** códigos y mensajes estables, sin ids de ledger, SQL, tablas ni trazas (verificado por prueba).
* **Entrada validada con lista blanca** (`[A-Za-z0-9_-]{1,40}`): impide inyección en logs y coincide con `VARCHAR2(40)`.
* **Logs:** `externalRef`, `outcome`, `reasonCode`, `changed`, `actions`. Sin montos, sin payloads del proveedor, sin
  secretos. Los detalles de errores inesperados solo van al log del servidor.
* **Seguridad (pendiente):** el ejercicio no incluye autenticación. En producción debe ir tras la seguridad de la
  organización (rol N3) y registrar el **usuario ejecutor** (`SUPPORT_AUDIT` no tiene columna de actor: se propone añadirla).
