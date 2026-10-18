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

## Regresión Android de emparejamiento · 18 de octubre

Build de esta corrección: ver evidence/windows-p1-pairing/windows/build.json cuando finalice la validación. Casos físicos **NOT_RUN**; el reloj no está visible por ADB al preparar esta entrega. Conservar la pareja funcional y no borrar el vínculo por rutina.

| Caso | Estímulo del operador | Resultado esperado | Estado |
|---|---|---|---|
| H02-bond-delay | En un emparejamiento nuevo autorizado, aceptar después de 10 s y antes de 60 s | Espera acotada; al vincular se abre otra generación GATT, confirma DS y NS y llega a Listo sin recuperación manual | NOT_RUN |
| H03-bond-reject | Rechazar ese emparejamiento | Error comprensible; sin suscripciones ni estado Listo; reintento voluntario | NOT_RUN |
| H03-bond-expire | Dejar pasar 60 s desde que entra en espera de vínculo | Cierra esa generación y muestra fallo; no prolonga la espera con fallos repetidos | NOT_RUN |
| H09-bond-remove | Retirar el vínculo solo como prueba negativa explícita | Deja de mostrar Listo; ignora callbacks antiguos; nueva autorización necesaria | NOT_RUN |

No confundir retirar el vínculo con revocar únicamente Compartir notificaciones en iOS: son estímulos distintos. Los tests JVM de BondWait no aprueban estos casos.

## Prototipo 0.2.0-lab · prueba conjunta de golpe

Build 0.2.0-lab (4): perfiles de app, llamadas, acciones ANCS, música AMS y pantalla renovada. Todo **NOT_RUN** hasta ejecutarlo en el Watch7 con el iPhone real. Registrar en `evidence/<run>/windows/` el logcat (`adb logcat -s WristBridge`), sin contenido de avisos.

| Caso | Estímulo del operador | Resultado esperado en el reloj | Estado |
|---|---|---|---|
| UI01 | Abrir WristBridge con el puente activo | Estado en lenguaje claro («Listo · avisos y música»); versión 0.2.0-lab en Diagnóstico | NOT_RUN |
| N10 | Aviso real de Telegram, WhatsApp, Gmail, Spotify y Netflix | Nombre de la app, color y monograma propios; agrupados por app; aparecen en «Avisos activos» | NOT_RUN |
| N11 | Llamada entrante real | «Llamada entrante», timbre y vibración repetidos; botones con las etiquetas del iPhone (p. ej. Aceptar/Rechazar) | NOT_RUN |
| N12 | Pulsar Rechazar en el reloj (tarjeta o app) | El timbre se detiene al instante; el iPhone rechaza; la tarjeta se retira con `Removed`; logcat `Acción NEGATIVE REQUESTED` | NOT_RUN |
| N13 | Otra llamada: pulsar Aceptar en el reloj | El iPhone contesta (audio en el iPhone); el timbre se detiene | NOT_RUN |
| N14 | Colgar desde el llamante sin tocar el reloj | El timbre se detiene con `Removed` (o a los 90 s como máximo) | NOT_RUN |
| N15 | «Ocultar contenido» activo + llamada y mensaje | No se ve llamante ni texto; solo tipo de aviso y app | NOT_RUN |
| N16 | Pulsar Borrar en un mensaje | El aviso desaparece también del iPhone; la tarjeta se retira | NOT_RUN |
| N17 | Activar «Borrar en iPhone al descartar» y deslizar un mensaje | El aviso se borra del iPhone; deslizar una llamada nunca la rechaza | NOT_RUN |
| N18 | Reconectar con avisos previos y con uno modificado | No vuelven a sonar ni vibrar | NOT_RUN |
| M01 | Reproducir música en el iPhone (Apple Music o Spotify) | Sección Música con título, artista, app y tiempo; notificación «Música del iPhone» | NOT_RUN |
| M02 | Pausar/reanudar, siguiente y anterior desde el reloj | El iPhone obedece; el estado del reloj se actualiza sin sondeo | NOT_RUN |
| M03 | Subir y bajar volumen desde el reloj | Cambia el volumen del iPhone; porcentaje actualizado | NOT_RUN |
| M04 | Cambiar de pista desde el iPhone | Título y duración nuevos en el reloj | NOT_RUN |
| M05 | Cerrar la app de música en el iPhone | La sección y la notificación de música desaparecen | NOT_RUN |
| M06 | iPhone sin música / AMS no disponible | Los avisos siguen funcionando; Música muestra su estado sin afectar a ANCS | NOT_RUN |
