# WristBridge Watch 0.1.2-lab (3)

18 de octubre de 2026 · America/Mexico_City. Rama development; cambios Android/Wear OS. Parte de la build 0.1.1-lab (2), aceptada funcionalmente por el usuario (ver [release-0.1.1-lab.md](release-0.1.1-lab.md)). No requiere cambios ni reinstalación en el iPhone; conserva el perfil P1, el UUID y la ruta ANCS directa.

## Qué cambia

Solo presentación local en el reloj. No hay comandos ANCS nuevos, acciones remotas, codec P2 ni apertura de apps del iPhone (eso exige coordinarlo con Swift).

- **Origen identificable.** Cada tarjeta muestra el nombre de la app (el que entrega el iPhone o, si falta, el del catálogo local), un color de acento y un icono grande generado en el reloj. Las tarjetas se agrupan por app dentro de cada generación de sesión.
- **Catálogo local** (`core:notification-domain`, `SourceProfiles`): Telegram, WhatsApp, Netflix, Spotify, Gmail, Mensajes, Teléfono, FaceTime, Calendario y Mail. Los identificadores se contrastaron con metadatos públicos de Apple; la búsqueda ignora mayúsculas. Las apps desconocidas usan el nombre del iPhone, la última parte del identificador o la categoría ANCS.
- **Iconos sin marcas registradas.** Monograma blanco sobre círculo de color (`SourceBadgeRenderer`), dibujado en el reloj. No se descarga ni incluye ningún logotipo.
- **Llamadas.** Las categorías ANCS `IncomingCall`, `MissedCall` y `Voicemail` muestran «Llamada entrante», «Llamada perdida» o «Buzón de voz», con el llamante en el cuerpo. La entrante usa el canal «Llamadas del iPhone» (tono de llamada, vibración repetida, `FLAG_INSISTENT`) y se detiene con `Removed` (contestada, rechazada o perdida). Un límite de 90 s acota el timbre si ese evento se pierde con el enlace. Contestar/rechazar sigue desactivado hasta P2-07; no hay pantalla completa, Telecom ni superposiciones.

## Privacidad y repetición

- Con «Ocultar contenido» activo, título y cuerpo no muestran llamante, remitente ni texto: solo el tipo de aviso y la app (probado con datos sintéticos).
- Avisos existentes al reconectar, silenciosos y `Modified` siguen sin alertar: el motor publica `alert=false` o `UpdateCard` y se usa el canal silencioso con `setOnlyAlertOnce`.
- El log añade solo el tipo (`llamada=INCOMING|MISSED|VOICEMAIL|NONE`), sin nombres ni contenido.

## Verificación

| Comprobación | Resultado |
|---|---|
| JVM notification-domain (18 motor + 4 BondWait + 7 presentación) | PASS, 29 tests |
| JVM protocolo y asistente | NOT_RUN en esta build (módulos sin cambios) |
| Lint debug y assembleDebug | PASS |
| Firma, instalación y arranque en el reloj | NOT_RUN |
| Avisos reales de Telegram, WhatsApp, Gmail, Spotify, Netflix | NOT_RUN |
| Llamada real: timbre, parada al contestar/rechazar, ocultación | NOT_RUN |
| H05, R01–R04, G1 | NOT_RUN |

APK: `build/releases/wristbridge-watch-0.1.2-lab-3.apk` (35 390 362 bytes, ignorado por git). SHA-256: `3b015f41ffdf029faf44b359fd04b8cef2fe323aeba840fbc927525380ea97d7`. Evidencia: `evidence/windows-p1-update-3/windows/`. El primer intento falló en `processDebugResources` por el bloqueo de archivos en F:; se repitió con `scripts/windows/native-output.init.gradle` y salida local. Las pruebas son sintéticas y no aprueban los casos físicos ni G1.

## Prueba física sugerida

1. Instalar con `adb install -r` sin borrar datos ni vínculo; abrir WristBridge e iniciar el puente si aparece detenido.
2. Avisos de Telegram, WhatsApp, Gmail, Spotify y Netflix: nombre, color, monograma y agrupación por app.
3. Llamada real: suena y vibra de forma repetida; al contestar, rechazar o colgar el llamante, se detiene y la tarjeta se retira.
4. Repetir con «Ocultar contenido»: no debe verse el llamante.
5. Reconectar con avisos ya presentes en el iPhone: no deben sonar de nuevo.
