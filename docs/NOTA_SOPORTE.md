# Nota operativa para soporte N3

## 1. Antes de ejecutar: ¿es un caso seguro?

Siempre empiece con el **diagnóstico** (solo lectura):

```
GET /api/support/payments/{ref}/diagnosis
```

| `outcome` | Qué significa | Qué hacer |
|---|---|---|
| `CONSISTENT` | Nada que corregir | Nada |
| `CORRECTABLE` | Hay una corrección segura y acotada (`actions` indica cuál) | Se puede ejecutar la conciliación (ver §2 antes) |
| `MANUAL_REVIEW` | Evidencia contradictoria/insuficiente o datos anómalos | **Detenerse** y escalar con el `reasonCode` |

Un caso es **seguro de conciliar** cuando: el proveedor tiene solo aprobaciones (o solo rechazos), el ledger no tiene
reversos raros, los débitos vigentes valen exactamente el monto del pago y el diagnóstico dice `CORRECTABLE`.

## 2. Verificar antes de pulsar el botón (casos con reverso)

* Confirmar con el proveedor que el segundo `APPROVED` **no** fue un segundo cobro real al cliente. Si lo fue, el reverso
  en nuestro ledger no devuelve el dinero: hay que reembolsar por el proveedor.
* Confirmar que el reintento ya no está corriendo y que no se liquidó ya el débito duplicado.

## 3. Cuándo detenerse (no ejecutar y escalar)

* `MANUAL_REVIEW` con cualquier `reasonCode`. Los más frecuentes:
  `EVIDENCE_CONTRADICTORY` (aprobado y rechazado) · `LEDGER_ANOMALY` (reverso parcial/huérfano/doble) ·
  `MISSING_DEBIT` · `LEDGER_AMOUNT_MISMATCH` · `DECLINED_WITH_NET_DEBIT` · `NO_PROVIDER_EVIDENCE`.
* Respuesta `409`: hay otra operación sobre el mismo pago. Espere unos segundos, **vuelva a ejecutar el diagnóstico** y
  recién entonces decida (puede que la otra ya lo resolvió).
* Respuesta `500`: no reintente en bucle; anote la hora y avise al equipo de desarrollo.

Repetir la conciliación es **seguro**: sobre un caso ya resuelto responde `CONSISTENT` y no cambia nada.

## 4. Qué revisar después de ejecutar

1. La respuesta: `outcome=RECONCILED`, `changed=true`, `netDebitAfter == expectedAmount`, `paymentStatus` correcto.
2. Que el ledger solo tenga **un movimiento nuevo** (`REVERSAL`) por débito duplicado y que los anteriores sigan igual.
3. `SUPPORT_AUDIT` del pago: una fila `RECONCILE_REVERSAL_POSTED` / `RECONCILE_STATUS_UPDATED` (o una sola
   `MANUAL_REVIEW_REQUIRED`).
4. Volver a correr el diagnóstico: debe devolver `CONSISTENT`.
5. Correr `db/queries/01_detect_inconsistencies_24h.sql`: el pago ya no debe aparecer.

> Si algo no cuadra, **no** vuelva a ejecutar a ciegas: los movimientos no se editan; cualquier ajuste adicional es un
> nuevo reverso/movimiento y debe revisarlo el equipo contable.
