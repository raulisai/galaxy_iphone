# Qué acepta el iPhone para salud (lado Apple, 0.3.5)

18 de octubre de 2026 · Apple. Responde al [handoff de salud de Windows](../windows/health-android-handoff.md). Es **lo que el libro del iPhone ya valida y guarda**; no define el formato de mensaje por el aire (eso es el contrato P2, aún sin congelar). Código: `packages/WristBridgeProtocol/Sources/WristBridgeProtocol/HealthLedger.swift`.

## Decisión sobre los pasos (punto 2 de Windows)

El iPhone **acepta instantáneas diarias explícitas** (`stepsDaily`) y también deltas por intervalo (`steps`). Para pasos del Watch7 conviene enviar la instantánea tal como la entrega Health Services:
- `start` = inicio del día del reloj, `end` = momento de la instantánea, `value` = pasos acumulados hoy (≥ 0, ≤ 300 000), duración ≤ 25 h.
- El total del día en el iPhone es **la instantánea más reciente de ese día** (por `end`), nunca una suma. Una instantánea posterior más baja (reinicio del contador) se guarda tal cual; no se resta ni se fabrica actividad negativa.
- El iPhone **no** convierte instantáneas en deltas ni escribe en Apple Salud desde ellas (HealthKit solo recibiría deltas; copia opcional futura).

## Campos de una muestra

| Campo | Regla |
|---|---|
| `deviceId` | ASCII `[A-Za-z0-9._:-]`, 1–64. Sin datos personales. |
| `metric` | `stepsDaily`, `steps`, `distance`, `activeEnergy`, `heartRate`. Unidad fija por métrica (`count`, `m`, `kcal`, `bpm`). |
| `start`/`end` | `end > start`; duración ≤ 6 h (≤ 25 h en `stepsDaily`); `end` no más de 5 min en el futuro; `end` dentro de la retención (7/30/90 días) o se rechaza (`expired`). |
| `value` | Finito, ≥ 0 y ≤ tope por métrica. |
| ritmo cardiaco | `value` = promedio 20–250; además `minimum` ≤ `value` ≤ `maximum`, todos 20–250. En las demás métricas `minimum`/`maximum` deben faltar. |
| `source` | ASCII ≤ 32 (p. ej. `watch-sensor`). |
| `syncId` | ASCII `[A-Za-z0-9._:-]`, 1–128, **estable**: igual en cada reenvío de la misma muestra. |
| `syncVersion` | ≥ 1. Mayor reemplaza; igual es duplicado; menor se ignora. |

El iPhone **no** deduplica por contenido: la identidad es `syncId`. Aprovechar el hash por métrica+instante+valor que ya usa el reloj es válido si es estable entre reintentos.

## Cuándo puede el reloj borrar/confirmar (ACK)

Un lote solo se confirma cuando `HealthStore.ingest` devuelve `true`: **todas** las muestras válidas (insertadas, actualizadas, duplicadas o de versión anterior) **y guardadas en disco**. Si hubo rechazos (`invalidIdentifier`, `invalidInterval`, `invalidValue`, `unitMismatch`, `expired`, `ledgerFull`) o el interruptor «Recibir datos de salud» está apagado, devuelve `false` y el reloj debe conservar el lote. Reenviar tras caerse entre guardar y confirmar es seguro (idempotente, prueba S03).

## Huecos y cursor

- `HealthGap(metric, start, end, missingIntervals)`: se muestra «sin datos», sin interpolar.
- El cursor es la muestra más reciente por métrica (`HealthLedger.cursor(for:)`); el iPhone lo enviará para pedir solo lo nuevo.

## Lo que falta para ver datos en el iPhone

1. Canal propio aprobado (G2): características GATT, framing y sesión autenticada. Ver [`handoff-to-windows.md`](handoff-to-windows.md) K1–K5.
2. El reloj arma lotes con la tabla de arriba y espera la confirmación (K6).
3. Apple cablea el canal a `HealthStore.ingest` (Apple, después de 1).
