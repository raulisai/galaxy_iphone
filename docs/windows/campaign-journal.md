# Registro de campaña en el reloj (P2-08.K)

Respuesta del lado Kotlin a la petición de Apple: "instrumentación watch para correlacionar
recibido/presentado/retirado" (`test/hil/apple/following-phases.md`, filas N09–N14 y Energía/G8).
Es un diario acotado en el reloj. **No sustituye ni valida los resultados de Apple**: la evidencia del
iPhone (qué se generó, cuándo y con qué runId) sigue siendo la del iPhone; este diario solo aporta la
vista del reloj para comparar.

No cambia el formato de cable, no añade características BLE y no altera el comportamiento de ANCS.

## Qué se registra

Una línea JSON por evento (`filesDir/campaign/journal.jsonl`). Campos presentes solo cuando aplican:

| Campo | Significado |
| --- | --- |
| `seq` | Número de secuencia del diario (crece; se reinicia con "Reiniciar registro"). |
| `wall` | Hora de pared del reloj, ms epoch. Sirve para correlacionar con el iPhone. |
| `mono` | Lectura monotónica en ms (`elapsedRealtime`). Sirve para intervalos dentro de un mismo arranque. |
| `type` | `SESSION_START`, `SESSION_END`, `CARD_RECEIVED`, `CARD_UPDATED`, `CARD_REMOVED`, `ACTION`, `LINK_DOWN`, `LINK_UP`. |
| `gen`, `uid` | Generación de sesión ANCS y UID de la notificación (contrato §3). |
| `cat` | Categoría ANCS (`SOCIAL`, `INCOMING_CALL`, ...). |
| `run`, `fix` | `FixtureTag(runId, fixtureId)` solo si el título cumplió `<runId> · F001`. |
| `bytes` | Tamaño en bytes UTF-8 del cuerpo recibido (no el cuerpo). |
| `alert` | `true` si la tarjeta sonó/vibró; `false` para silenciosas, previas (backlog) y Modified. |
| `by` | En `CARD_REMOVED`: `ANCS` (el iPhone la retiró) o `WATCH_DISMISS` (la descartó el usuario en el reloj). |
| `action`, `status` | En `ACTION`: `POSITIVE`/`NEGATIVE` y `REQUESTED`/`FAILED`/`UNAVAILABLE`. |
| `reason` | En `SESSION_END` el motivo interno (`desconexión estado=19`, `nuevo enlace`, ...); en `LINK_DOWN` `gatt=<estado>`. |
| `ms` | En `LINK_UP`: duración del corte desde el `LINK_DOWN` previo. En `SESSION_START`: tiempo desde la última caída hasta tener ANCS listo. |

Ejemplo (sintético):

```json
{"seq":12,"wall":1760000123456,"mono":88123456,"type":"CARD_RECEIVED","gen":5,"uid":31,"cat":"SOCIAL","run":"R20261018A","fix":"F003","bytes":18,"alert":true}
```

## Qué NO se registra

Títulos, subtítulos, cuerpos, nombres de app, identificadores de app, nombres de llamante ni el
`sourceDeviceId`. La API del diario no tiene ningún parámetro de texto libre salvo el motivo interno de
cierre. El `runId`/`fixtureId` solo existe si el título coincide con el patrón sintético de los fixtures
de Apple; el título completo no se guarda.

## Límites y almacenamiento

* En memoria: 2000 eventos como máximo (se descartan los más antiguos y se cuentan en `descartados`).
  Los contadores, las etiquetas de fixture (tope 10 000) y las muestras de latencia (tope 2000) se
  agregan aparte, de modo que las estadísticas no dependen del tope de eventos.
* En disco: `filesDir/campaign/journal.jsonl`, un `append` con vaciado por cada lote de efectos. Al
  llegar a 512 KB rota a `journal.jsonl.1` (sustituye la copia anterior; solo se conserva una). Al
  reiniciar el proceso se vuelven a leer ambos archivos para reconstruir las estadísticas; lo que ya
  rotó fuera de esos dos archivos se pierde. La aplicación tiene `allowBackup=false`, así que el
  diario no entra en copias de seguridad en la nube.
* Una línea truncada por un corte brusco se ignora al releer.

## Definiciones de las estadísticas

* **Recibidas / actualizadas / retiradas**: conteos acumulados desde el último reinicio.
* **Duplicadas**: una misma etiqueta de fixture recibida de nuevo en la misma generación, o recibida de
  nuevo con alerta (sonaría dos veces).
* **Repetidas**: misma etiqueta recibida de nuevo y en silencio en una generación posterior. Es lo
  esperado al reconstruir el backlog tras reconectar (PreExisting); no es pérdida ni duplicado.
* **Faltantes** (solo en el resumen JVM con lista esperada): `fixtureId` esperados de un `runId` que
  nunca se recibieron. El reloj no conoce la lista esperada; se compara fuera de línea con el
  `stats(expected)` / `summaryJson(expected)` de `CampaignJournal` o con el JSONL.
* **Reconexión p50/p95**: percentiles (rango más cercano) de `LINK_DOWN` a `LINK_UP` (GATT conectado).
* **Listo p50/p95**: de `LINK_DOWN` a `SESSION_START` (ANCS listo de nuevo).
* **Mayor corte**: el mayor `LINK_DOWN`→`LINK_UP`, incluyendo un corte aún en curso.

Limitaciones: `LINK_DOWN` se registra en la desconexión del enlace cliente ANCS; un cierre decidido por
el propio puente (parada, reconstrucción del enlace) cierra la sesión (`SESSION_END`) pero no cuenta
como caída. `WATCH_DISMISS` solo se registra cuando el descarte se reenvía al iPhone (ajuste "Borrar en
iPhone al descartar" activo, nunca en llamadas). El diario mide lo que el reloj recibió y presentó; la
ausencia de una tarjeta en el centro de notificaciones del iPhone no se deduce de aquí.

## Ver en el reloj

Ajustes → Diagnóstico → sección "Campaña": eventos, descartados, sesiones, recibidas/actualizadas/
retiradas, duplicadas, repetidas, acciones, caídas, p50/p95 de reconexión y de "listo", mayor/último
corte y el recuento de fixtures por `runId`. "Reiniciar registro" pide una segunda pulsación
("Confirmar reinicio") y borra la memoria y los dos archivos.

## Extraer el archivo

Solo compilaciones debug (`isDebuggable = true`):

```
adb shell run-as dev.wristbridge.watch cat files/campaign/journal.jsonl.1 > journal.jsonl.1
adb shell run-as dev.wristbridge.watch cat files/campaign/journal.jsonl   > journal.jsonl
```

(`.1` es la parte más antigua; concatenar `.1` y luego el actual.) Extraerlo antes de "Reiniciar
registro" y antes de desinstalar. En compilaciones release no hay `run-as`; allí se usa solo la
pantalla.

## Correlación con la exportación del iPhone

1. En el iPhone, cada fixture sintético lleva el título `<runId> · F001` y se exporta con su `runId`
   y `fixtureId` (`FixtureTag(runId, fixtureId)` en `core/notification-domain`).
2. En el JSONL del reloj, filtrar por `run` y `fix`: `CARD_RECEIVED` = recibido, `CARD_UPDATED` =
   modificado, `CARD_REMOVED` con `by` = retirado. "Presentado" se infiere de `CARD_RECEIVED`/
   `CARD_UPDATED` con `alert` (la tarjeta se publicó en el reloj); el log redactado de Diagnóstico
   muestra además `Tarjeta mostrada`.
3. Unir por `(runId, fixtureId)`, no por hora. Las horas (`wall`) de ambos relojes difieren; comparar
   siempre con la incertidumbre de reloj explícita (deriva no medida entre iPhone y reloj).
4. Las caídas (`LINK_DOWN`/`LINK_UP`/`SESSION_*`) se alinean con las fases "antes/durante/después de
   alejar y reconectar" de N09–N14 por `wall`, dentro de esa incertidumbre.
5. Una tarjeta ausente del reloj y presente en el iPhone es una pérdida candidata; una tarjeta ausente
   del centro de notificaciones del iPhone no prueba pérdida (ver fila N09–N14). Apple decide los
   veredictos PASS/FAIL; este diario es evidencia de apoyo del lado del reloj.

Pruebas: `core/notification-domain/src/test/kotlin/dev/wristbridge/notification/CampaignJournalTest.kt`
(C01..C18).
