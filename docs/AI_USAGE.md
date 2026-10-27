# Uso de inteligencia artificial

**Herramienta:** Claude (Anthropic), como asistente de programación, en una única conversación durante la prueba.
**Información compartida con la herramienta:** el enunciado de la prueba y la evidencia que trae (logs y datos de
muestra, que son ficticios). **No se compartieron secretos, credenciales ni datos sensibles**, y el repositorio no
contiene ninguno (las credenciales de Oracle se leen del entorno).

## 1. En qué actividades se usó

| Actividad | Qué se pidió a la IA | Qué se hizo con el resultado |
|---|---|---|
| Análisis de la evidencia | Separar hechos observados de hipótesis en los logs de `PAY-BX17` y datos de muestra | Revisado; quedó en `docs/DIAGNOSTICO.md` marcando explícitamente lo no comprobable |
| Diseño de la solución | Proponer reglas de conciliación, estrategia de concurrencia e idempotencia | Aceptado con ajustes (ver §2); decisiones y alternativas en `docs/DECISIONES.md` |
| Código Java (Spring Boot) | Generar analizador de reglas, entidades, servicio, API y manejo de errores | Aceptado; revisado por capas antes de cada commit y verificado con pruebas (ver §3) |
| SQL / PL/SQL | Generar la consulta de detección 24 h, `sp_reconcile_payment`, scripts de esquema y datos de demo | Aceptado con correcciones (§2). La consulta se ejecuta en las pruebas; el PL/SQL **no se ejecutó en Oracle** (ver §3) |
| Pruebas | Generar pruebas unitarias, de integración, de concurrencia y de bordes | Aceptado; ejecutadas localmente (ver §3) |
| Documentación | Redactar README, ADRs y nota operativa | Aceptado; ajustado y verificado contra el comportamiento real del código |

## 2. Qué se aceptó, corrigió o descartó

**Corregido / ajustado en la revisión de la salida de la IA (antes de entregármela)**

* Se quitó `hibernate.jdbc.time_zone=UTC` de la configuración: con `LocalDateTime` solo añadía riesgo de desfase entre
  lo que inserta JDBC y lo que inserta Hibernate.
* `@ExceptionHandler(Exception.class)` se acotó a `RuntimeException`: el original convertía en 500 los 404/405 propios de
  Spring MVC (hay una prueba que fija el 405).
* El script de datos de demo usaba `INTERVAL '120' MINUTE`, que **falla en Oracle** (ORA-01873: precisión por defecto de
  2 dígitos); se reescribió con horas y minutos de ≤ 2 dígitos.
* `@AutoConfigureMockMvc` se reemplazó por un `MockMvc` armado a mano: un segundo contexto de Spring volvería a ejecutar
  el DDL sobre la base H2 en memoria compartida ("table already exists").
* Validación de la referencia con una expresión regular propia en el controlador, en lugar de `@Validated` + `@Pattern`
  (evita depender de qué excepción de validación lanza cada versión de Spring).
* Se refresca la fila del pago tras obtener el bloqueo, para no depender de cómo cada motor entrega el valor después de esperar.

**Descartado** (propuestas consideradas y rechazadas, con motivo en `docs/DECISIONES.md`)

* Resolver `PAY-CQ22` por el evento más reciente o por mayoría (sería adivinar).
* Postear automáticamente un débito faltante (podría duplicarse con una contabilización asíncrona).
* Una tabla de claves de idempotencia / cabecera obligatoria (la convergencia por estado es suficiente y más simple).
* Reversar todos los débitos y volver a debitar uno; aislamiento `SERIALIZABLE`.

## 2.1 Cómo integré el código

El código se generó con IA en una sola sesión. Yo lo integré por capas: leí cada capa, ejecuté sus pruebas y recién
entonces hice el commit (base y configuración → reglas con pruebas unitarias → persistencia, servicio y API →
SQL/PL/SQL → pruebas de integración → documentación). Por eso la fecha de los commits corresponde a esa
integración y no al tiempo de escritura del código.

## 3. Cómo se verificó antes de integrar

* **Analizador de reglas:** en el entorno donde la IA generó el código se compiló con `javac --release 17` y se
  ejecutaron sus **19 pruebas unitarias** con un runner mínimo de JUnit → 19/19 pasan. Luego las ejecuté yo con Maven
  (`mvn test -Dtest=ReconciliationAnalyzerTest`) → 19/19.
* **Consulta de detección 24 h:** su lógica se ejecutó en SQLite con una traducción mínima de sintaxis Oracle
  (`LOCALTIMESTAMP - INTERVAL`, `NVL`) sobre los 4 casos de muestra y 4 bordes (pago antiguo fuera de ventana, duplicado
  reciente sobre pago antiguo, rechazado limpio, rechazado con débito): resultado esperado en todos. La prueba
  `DetectionQueryTest` la ejecuta, sin traducir, sobre H2 en modo Oracle.
* **Pruebas de integración / `mvn test` completo:** no pudieron ejecutarse en el entorno donde la IA generó el código
  (sin acceso a Maven Central). Las ejecuté yo en mi máquina el 27/10/2026 (JDK 21 Microsoft, Maven 3.9.16):
  `mvn test` → 45 pruebas, 0 fallos, 0 errores, BUILD SUCCESS (19 unitarias de reglas, 4 de la consulta SQL,
  15 del servicio incluida concurrencia con 6 hilos, y 7 de la API).
* **PL/SQL:** no se ejecutó contra Oracle; se dejó `db/plsql/validate_sp_reconcile_payment.sql` como prueba de humo
  reproducible.