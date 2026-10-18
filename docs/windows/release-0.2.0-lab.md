# WristBridge Watch 0.2.0-lab (4) · prototipo para prueba conjunta

18 de octubre de 2026 · America/Mexico_City. Rama development. Parte de 0.1.2-lab (3). Pensada para probar de una vez casi todo lo que el reloj puede hacer sin el canal propio P2. No requiere cambios ni reinstalación en el iPhone: usa solo servicios del sistema iOS (ANCS y AMS) sobre el vínculo existente.

## Qué incluye

| Área | Funcionalidad | Origen en el plan |
|---|---|---|
| Avisos | Nombre, color y monograma por app; agrupación por app; llamadas entrante/perdida/buzón diferenciadas; timbre insistente que se detiene con `Removed` (máx. 90 s) | P1-08, P3 |
| Acciones | Botones con las etiquetas que da el iPhone (p. ej. Aceptar/Rechazar en llamadas, Borrar en mensajes), en la tarjeta y dentro de la app. Resultado «solicitado», «falló» o «no disponible»; el efecto final lo confirma el iPhone con `Removed`/`Modified` | P2-07 |
| Descarte sincronizado | Opcional (apagado por defecto): deslizar un aviso en el reloj pide «Borrar» en el iPhone. Nunca se aplica a llamadas | P2-07 |
| Música | Cliente AMS: app, título, artista, álbum, duración y tiempo transcurrido sin sondeo; anterior, reproducir/pausar, siguiente, volumen; recupera títulos truncados; notificación persistente «Música del iPhone» con controles | P3 (AMS) |
| Pantalla | Estado en lenguaje claro, llamada entrante destacada, música, avisos activos con acciones, ajustes (ocultar contenido, acciones, descarte sincronizado, notificación de música), conexión y diagnóstico plegable | P3 |
| Privacidad | «Ocultar contenido» oculta llamante, remitente, texto y título de la canción; los logs solo llevan ids, tamaños y tipos | P3 |

AMS es independiente de ANCS: si el iPhone no expone música o falla su suscripción, los avisos siguen funcionando y la sección Música muestra su propio estado.

## Fuera de este prototipo

- Canal propio P2 (codec, seguridad, persistencia), ajustes desde el iPhone y abrir apps del iPhone: requieren el contrato conjunto con Swift.
- IA (P4), voz (P5), agente Mac (P6), salud y contactos (P7): requieren sus gates y decisiones (credenciales, ADR).
- Responder con texto a mensajes: ANCS no lo permite; solo las acciones positiva/negativa que ofrece iOS.
- Tile y complicación: requieren dependencias nuevas que no están en la caché offline de esta PC.

## Cómo se construyó

Núcleo puro con pruebas JVM: codec y estado AMS (`core/protocol-kotlin/.../ams`) y acciones en el motor ANCS (`AncsSessionEngine.performAction`, efecto `ActionResult`, una sola transacción en el Control Point y prioridad sobre las consultas pendientes). En el reloj: `AmsSession` comparte la cola GATT serial del enlace ANCS y arranca solo cuando ANCS está listo; `CardPresenter` y `MediaPresenter` publican tarjetas y música; `CommandReceiver` entrega botones, descartes y controles de música al servicio en marcha sin iniciar un servicio en primer plano desde segundo plano; `ActiveCards` y `MediaStatus` alimentan la pantalla.

## Resultados

| Comprobación | Resultado |
|---|---|
| JVM (protocolo, AMS, notificaciones, acciones, asistente) | PASS, 70 tests, 0 fallos |
| Lint debug | PASS, 0 errores, 2 avisos (`DataExtractionRules` previo, `UseKtx`) |
| assembleDebug | PASS |
| Instalación en el Watch7 por actualización `-r` | PASS; checksum en el reloj idéntico al APK |
| Proceso de la app tras abrirla | PASS |
| Puente activo tras la actualización | NOT_RUN: el reloj estaba bloqueado; abrir WristBridge e iniciar el puente |
| Casos físicos UI01, N10–N18, M01–M06 | NOT_RUN |
| G1 | NOT_RUN |

APK: `build/releases/wristbridge-watch-0.2.0-lab-4.apk` (35 624 638 bytes, ignorado por git). SHA-256: `80512f3c3ce6f13d254dd613a8c5dc4c4f750d79b40e2136501670d635ab8e8a`. Evidencia: `evidence/windows-p1-update-4/windows/`.

## Prueba física

Guion completo en `test/hil/windows/p1-campaign.md`, sección «Prototipo 0.2.0-lab» (UI01, N10–N18, M01–M06). Puntos a vigilar: que el timbre se corte al pulsar una acción (depende de volver a publicar en el canal silencioso), que el descarte sincronizado llegue con el reloj inactivo y que AMS aparezca tras «Listo» (si el iPhone no lo expone, Música lo indica y los avisos siguen).
