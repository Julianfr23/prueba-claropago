# Diagnóstico del incidente N3

> El extracto de logs es **evidencia parcial** y solo cubre `PAY-BX17`. Este documento separa lo observado de lo
> supuesto. Nada de lo que aparece en "Hipótesis" se trata como hecho en el código.

## 1. Hechos observados

**Logs de `PAY-BX17` (2026-10-13):**

| Hora | Hecho |
|---|---|
| 09:41:22.104 | Inicia la llamada `APPROVE` al proveedor (intento 1) |
| 09:41:23.983 | `timeout after 1800ms` en esa llamada |
| 09:41:24.106 | Se programa un reintento (`reason=provider-timeout`) |
| 09:41:25.317 | Llega el evento `EVT-B2` con estado `APPROVED` |
| 09:41:25.390 | Se crea el movimiento de ledger `5003` tipo `DEBIT` (73 ms después del evento) |
| 09:41:25.611 | `WARN`: el pago **ya estaba `CONFIRMED`** durante el reintento (221 ms **después** de crear el débito) |
| 09:43:08.512 | El job de conciliación detecta `netDebit=178000` vs `expected=89000` |

**Datos de muestra:** `PAY-BX17` está `CONFIRMED` por 89.000 con **2 eventos APPROVED** y **2 débitos**;
`PAY-AX91` está `PENDING` con 1 `APPROVED` y 1 débito correcto; `PAY-CQ22` está `PENDING` con un `APPROVED` y un
`DECLINED` y sin ledger; `PAY-DK44` es un caso sano.

**Lo que el extracto NO muestra:** el primer débito ni el primer evento de `BX17`; cuándo/quién pasó el pago a
`CONFIRMED`; ningún log de `AX91` ni de `CQ22`; si el proveedor cobró una o dos veces al cliente.

## 2. Hipótesis de causa (sin confirmar)

* **H1 — Timeout del cliente ≠ fallo del proveedor (la más probable para BX17).** El primer `APPROVE` sí llegó y fue
  aprobado; el timeout fue solo de espera del cliente. Esa aprobación confirmó el pago y generó el primer débito. El
  reintento volvió a pedir `APPROVE`, el proveedor emitió `EVT-B2` y el manejador **insertó un segundo débito sin
  comprobar de forma atómica** que ya existía uno. Apoyo: el débito `5003` se creó **antes** de que el reintento
  detectara el estado `CONFIRMED` — el chequeo ocurre después de la escritura (o fuera de la misma transacción/bloqueo).
* **H2 — Carrera entre dos procesadores de eventos** que leen "sin débito" a la vez (check-then-act sin bloqueo).
* **H3 — El proveedor reenvió la misma aprobación con otro `event_id`** (duplicado del lado del proveedor): en ese
  caso no hubo doble cobro real, solo doble registro nuestro.
* **AX91:** evento y débito persistidos pero la actualización de estado se perdió (caída o rollback entre pasos).
* **CQ22:** el proveedor emitió dos estados finales opuestos; el orden no basta para interpretarlos (un `DECLINED`
  posterior a un `APPROVED` puede ser una anulación; un `APPROVED` posterior a un `DECLINED`, un reintento exitoso).

## 3. Validaciones que haría en producción **antes** de aplicar la corrección

1. **Con el proveedor:** cuántas capturas reales hay para `PAY-BX17` y si `EVT-B2` comparte autorización/clave de
   idempotencia con el primer evento. *Si fueron dos cobros reales, el reverso en nuestro ledger NO basta: hay que
   reembolsar al cliente por el canal del proveedor.* (Es el mayor riesgo de esta corrección.)
2. Correlacionar por hilo/trace-id qué proceso creó cada débito (`5002`/`5003`) y a qué evento corresponde cada uno.
3. Verificar que ningún proceso aguas abajo (liquidación a comercios, extractos, reportes) ya consumió el débito
   duplicado: debe tratar los `REVERSAL` y, si ya se liquidó 178.000, planear el ajuste correspondiente.
4. Confirmar que el reintento **ya no está activo** (o que quedó protegido por bloqueo) para que no cree otro débito
   mientras se concilia.
5. Ejecutar `GET …/diagnosis` y la consulta `db/queries/01_detect_inconsistencies_24h.sql` para dimensionar el
   alcance; **respaldar** (export) las filas de los pagos afectados.
6. Probar primero en una copia no productiva; en producción, un solo pago (`BX17`), revisar auditoría y recién
   después el resto. Antes de crear el índice único de reversos, correr el pre-chequeo de duplicados históricos
   (incluido en `db/schema/02_integrity_and_indexes.sql`).
7. Para `CQ22`: pedir al proveedor el estado final real; **no** resolver desde nuestro lado.

## 4. Corrección propuesta

**De datos (implementada en esta entrega):**

| Pago | Acción | Por qué es segura |
|---|---|---|
| `PAY-BX17` | Insertar **un** `REVERSAL` por 89.000 relacionado con el débito `5003` (el posterior); queda 1 débito neto | No modifica ni borra movimientos; el neto vuelve al valor del pago |
| `PAY-AX91` | `status: PENDING → CONFIRMED` | Evidencia solo-aprobada y débito correcto; no toca el ledger |
| `PAY-CQ22` | **Ninguna.** Se audita `MANUAL_REVIEW_REQUIRED` una sola vez | Evidencia contradictoria: no se adivina |
| `PAY-DK44` | Ninguna | Ya consistente |

**De causa raíz (propuesta, fuera del alcance de esta herramienta):**

1. Enviar una **clave de idempotencia** al proveedor en `APPROVE`, de modo que un reintento tras timeout devuelva la
   misma aprobación en vez de crear otra.
2. Procesar cada evento de forma idempotente: leer-y-escribir **dentro de una transacción con bloqueo** sobre el pago
   (o con una restricción única), y no registrar un débito si ya existe uno vigente.
3. Agregar `provider_event_id` a `LEDGER_ENTRY` con índice único `(payment_id, provider_event_id)` para impedir
   estructuralmente el segundo débito por el mismo evento.
4. Alerta sobre débito neto ≠ monto (la consulta de detección ya sirve de base) en lugar de descubrirlo por un job
   que corre minutos después.

## 5. Supuestos y riesgos

| # | Supuesto | Riesgo si es falso | Cómo se mitiga |
|---|---|---|---|
| S1 | El débito **más antiguo** es el del intento original; el posterior es el duplicado | Se reversa el "equivocado" (mismo monto: el neto es igual, pero la trazabilidad contra el evento puede quedar invertida) | Validación 2; el reverso queda relacionado y auditado |
| S2 | El segundo `APPROVED` **no** es un segundo cobro real | El libro quedaría bien pero el cliente seguiría cobrado dos veces | Validación 1 (con el proveedor) **antes** de ejecutar |
| S3 | `PROVIDER_EVENT` está completo (sin purgas ni eventos faltantes) | Una decisión basada en evidencia incompleta | Validación 1/2; casos sin evidencia van a revisión manual |
| S4 | Un débito se reversa por su monto completo y una sola vez | Reversos parciales legítimos serían marcados como anomalía | Va a revisión manual (decisión conservadora, no destructiva) |
| S5 | `CONFIRMED` sin eventos o con ledger vacío puede ser una contabilización asíncrona en curso | Postear un débito "por cuenta propia" duplicaría después | No se postean débitos faltantes: revisión manual (`MISSING_DEBIT`) |
| S6 | Todas las marcas de tiempo comparten zona horaria | Ventana de 24 h y orden de débitos desplazados | Revisar `SESSIONTIMEZONE`/columnas antes de usar |
