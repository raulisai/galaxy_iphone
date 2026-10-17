# Campaña Windows P1 · lado Watch7 · NOT_RUN

Guion del lado reloj para la campaña conjunta. Complementa `test/hil/apple/mvp.md`; no lo reemplaza. Solo datos sintéticos. Una sola ruta (A) activa y la misma revisión integrada en ambos lados.

## Precondiciones

- APK instalado con commit y SHA-256 del handoff; la app muestra el mismo commit (H01).
- `adb logcat -s WristBridge` grabándose en `evidence/<run>/windows/logcat.txt`.
- Inventario del reloj (`getprop`, ver installation.md) en `evidence/<run>/windows/watch.json`, seudonimizado.
- Sin exploradores BLE ni otras apps de laboratorio compitiendo por el enlace.

## Estímulo del Mac/iPhone → salida observable en el reloj

| Caso | Estímulo (iPhone) | Observable en el reloj | Estado |
|---|---|---|---|
| H02 | Selector ASK filtra por UUID `…36a01` y nombre `WristBridge`; elegir reloj | Canal propio «iPhone conectado (peer-…)»; logcat `Central conectado` | NOT_RUN |
| H02/H04 | Aceptar emparejamiento y compartir avisos | Vínculo «Vinculado»; ANCS «Data Source confirmado» y luego «Listo gen=N» | NOT_RUN |
| H04 | Negar compartir avisos o no emparejar | ANCS nunca «Listo»: «No disponible», «Suscribiendo…» o «Error» con código | NOT_RUN |
| N01 | Fixture breve `runId · F001` | Tarjeta nativa; logcat `Tarjeta mostrada … fixture=runId/F001` | NOT_RUN |
| N02 | Fixture con cuerpo >1024 bytes | Tarjeta con «…»; `bytesCuerpo` ≤1024 | NOT_RUN |
| N03 | Avisos de dos apps seguidos | Dos tarjetas, títulos y cuerpos sin mezclar | NOT_RUN |
| N04 | Aviso que la app de origen actualiza | `Tarjeta actualizada` mismo uid, sin nueva vibración | NOT_RUN |
| N05/N06 | Descartar el aviso en el iPhone | Tarjeta retirada; no reaparece; contador `descartes` si llegó tarde | NOT_RUN |
| N07/N09 | Recuperar o reconectar con avisos previos | Nueva `gen`; tarjetas viejas retiradas y backlog silencioso (`previos`) | NOT_RUN |
| N08 | App sin nombre | Etiqueta legible (categoría o sufijo del bundle) | NOT_RUN |
| H05 | iPhone bloqueado, app del reloj cerrada, pantalla dormida ≥30 min | Tarjetas llegan; servicio sigue activo | NOT_RUN |
| R01 | Salir/volver al alcance ×20 | Desconexión y reconexión; medir con `elapsedRealtime` del reloj | NOT_RUN |
| R02 | Bluetooth off/on en cada dispositivo | Estado honesto; sin bucle rápido | NOT_RUN |
| R03 | Service Changed | logcat `Service Changed: nueva generación` y «Listo» con gen+1 | NOT_RUN |
| R04 | Cortar enlace durante consulta | Sin contaminación: siguiente aviso correcto; consultas viejas ignoradas | NOT_RUN |

Al terminar, copiar el bloque **Diagnóstico** de la app (contadores) a la evidencia. «Programado» en iOS no cuenta como entregado: correlacionar por `runId/fixtureId`.
