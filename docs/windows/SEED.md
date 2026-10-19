# SEED · estado y continuación del lado Windows/Kotlin

Archivo de relevo para cualquier agente (Claude, Codex u otro) que continúe el trabajo del reloj. **Léelo primero, actualízalo al terminar cada bloque de trabajo y no lo uses para secretos ni datos personales.** Última actualización: 18 de octubre de 2026 (build 0.4.0-lab (9)), America/Mexico_City.

Lectura obligatoria previa: `AGENTS.md`, `Doc/00_LEEME_Y_COORDINACION.md`, `Doc/01_CONTRATO_COMPARTIDO.md`, `Doc/02_WINDOWS_KOTLIN.md`, `docs/windows/handoff-G0.md`.

## Reglas que no se rompen

- Windows/Kotlin escribe `apps/watch`, `core/*`, Gradle, `docs/windows`, `test/hil/windows`, `evidence/*/windows`. No tocar Swift, `apps/ios`, `packages/WristBridgeProtocol` ni firma Apple.
- `Doc/**` no se modifica (sus hashes se verifican). Nunca ejecutar `git add --renormalize` sobre `Doc`; `.gitattributes` ya lo protege.
- Sin identificadores personales, direcciones, seriales, claves ni contenido real de avisos en archivos versionados ni en logs.
- Sin IA, Mac, salud ni comandos sensibles del canal propio antes de sus gates. Las acciones ANCS que ofrece iOS (P2-07) se habilitaron en 0.2.0-lab por pedido del usuario de avanzar el prototipo; siempre como «solicitado», nunca prometiendo el efecto final. Sin pantalla completa, Telecom, superposiciones ni desactivar keyguard para simular llamadas (plan §6.5).
- Estados honestos: `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN`. Un simulador o un fake nunca aprueba un gate físico.
- Flujo de ramas: trabajo en la rama de la sesión → `development_claude` → validar sobre una copia limpia → integrar en `development` → subir ambas. Commits con la línea `Co-Authored-By` indicada por la sesión.

## Dónde estamos

| Área | Estado | Evidencia |
|---|---|---|
| Base Gradle (AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21, JDK 21), módulos `core/*` y `apps/watch` | PASS | 48 pruebas JVM, lint sin errores, `evidence/windows-g0/windows/build.json` |
| Parser ANCS, ensamblador de Data Source, cola GATT, motor de sesión (U01–U20, lógica de N01–N08) | PASS en JVM | `core/*/src/test` |
| APK de laboratorio instalado en el Galaxy Watch7 (Wear OS 6, API 36) | PASS | instalación verificada por `dumpsys package` |
| Servicio foreground `connectedDevice`, anuncio del UUID, servidor GATT mínimo | PASS en dispositivo | log `WristBridge`: «Servicio iniciado», «Anunciando servicio … nombreValido=true» |
| Selector de AccessorySetupKit del iPhone encuentra el reloj | PASS (con nombre `WristBridge`) | observado por el usuario |
| Vínculo LE con el iPhone | PASS | log del Bluetooth del reloj: `BOND_BONDING => BOND_BONDED` |
| ANCS: Data Source y Notification Source confirmados, «ANCS listo» | PASS | log: «Data Source confirmado», «ANCS listo gen=1» |
| Tarjetas nativas con avisos sintéticos de prueba (F001–F005) | PASS (parcial) | log: «Tarjeta mostrada … fixture=run-…/F001» |
| Arreglo de vínculo (BondWait) e instalación de la build 0.1.1-lab (2) | PASS; aceptación funcional del usuario | `docs/windows/release-0.1.1-lab.md` |
| Identificación de app (nombre, color, monograma) y llamadas diferenciadas, build 0.1.2-lab (3) | PASS en JVM/lint/assemble; instalación y pruebas físicas NOT_RUN | `docs/windows/release-0.1.2-lab.md`, `evidence/windows-p1-update-3/` |
| Prototipo 0.2.0-lab (4): acciones ANCS (aceptar/rechazar/borrar), música AMS, descarte sincronizado opcional, pantalla renovada | PASS en JVM (70), lint, assemble e instalación con checksum; casos físicos NOT_RUN | `docs/windows/release-0.2.0-lab.md`, `evidence/windows-p1-update-4/` |
| 0.2.1-lab (5) en el reloj: reanuda solo tras actualizar/reiniciar; ANCS y AMS listos; backlog silencioso; corrige «Recuperar» sin reenvío de avisos y Service Changed atascado | PASS observado por logcat; repetir Recuperar y R03 NOT_RUN | `release-0.2.0-lab.md` (sección 0.2.1), `evidence/windows-p1-update-5/` |
| 0.3.0-lab (7): música como sesión de medios nativa, UI estilo Apple Watch (tiles, llamada a pantalla completa), generación persistida entre reinicios del servicio | PASS compilación/instalación; sesión de medios y pantalla de inicio vistas en el reloj; resto NOT_RUN | `evidence/windows-p1-update-6/` |
| 0.3.1-lab (8): llamada con CallStyle nativo, la notificación abre la pantalla de llamada, corona = volumen del iPhone (AMS), llamada y música menos apretadas. Límites de compatibilidad en `docs/windows/compatibilidad-iphone.md` (sin audio/micrófono de llamada, sin responder WhatsApp ni abrir apps sin Swift) | PASS compilación/instalación; pruebas físicas NOT_RUN | `evidence/windows-p1-update-7/` |
| 0.4.0-lab (9): diario de campaña en el reloj (N09–N14, P2-08), propuesta de contrato P2 en borrador (`contracts/proposals/p2-channel-draft.md`, sin congelar, pendiente de revisión Apple), ADR-001 con evidencia, traspaso a Apple (`docs/windows/handoff-to-apple.md`) | PASS compilación (88 tests JVM) y lint; instalada en el reloj (checksum verificado); diario de campaña comprobado en el dispositivo sin contenido | `evidence/windows-p1-update-8/` |
| Respuesta a la propuesta de salud P7 de Apple (Watch7 expone pasos y ritmo cardiaco por APIs estándar; ECG/BIA solo con permiso privilegiado de Samsung) | Respuesta de diseño, inspección por ADB; sin código ni prueba con app de terceros | `docs/windows/respuesta-salud-p7.md` |
| 0.5.1-lab (12): accesibilidad (TalkBack: descripciones, encabezados, estados, regiones vivas, objetivos de 48 dp); núcleo P2 de framing (draft-2, 68 vectores, sin cifrado), dominio del asistente/voz/herramientas sin conectar; 217 pruebas JVM | PASS compilación, lint e instalación con checksum; TalkBack y pruebas físicas NOT_RUN | `evidence/windows-update-12/`, `docs/windows/p2-decisions.md`, `docs/windows/phase-status.md` |
| Avisos reales de otras apps, iPhone bloqueado, UI cerrada, 30 min (H05) | NOT_RUN | pendiente |
| R01–R04 (reconexión, Bluetooth off/on, Service Changed, corte durante consulta) | NOT_RUN | pendiente |
| ADR-001 (elegir A o B) | Pendiente de redactar con la evidencia anterior | `docs/adr/ADR-001-roles-ble.md` |
| G1 | NO aprobado | faltan campaña completa H/N/R y revisión conjunta |

## Estado del código

0.2.1-lab (5) está instalada en el Watch7 (checksum verificado) y en el commit que acompaña a este SEED. El puente se reanuda solo tras `adb install -r` (`StartupReceiver`, MY_PACKAGE_REPLACED/BOOT_COMPLETED), aunque el reloj esté bloqueado: ya no hace falta abrir la app después de actualizar.

Lecciones del reloj real: (1) cerrar y reabrir el cliente GATT no corta el enlace físico, así que iOS mantiene el CCCD activo y no repite PreExisting; por eso con peer vinculado se escribe disable→enable en Notification Source y en AMS. (2) Service Changed puede llegar con el MTU en curso y el redescubrimiento en el mismo cliente queda sin respuesta; por eso se rehace el enlace cliente (máx. 3 automáticos hasta READY). (3) Con el reproductor del iPhone inactivo, AMS solo anuncia VOLUME_UP/DOWN.

Arquitectura añadida: `core/protocol-kotlin/.../ams` (codec y `MediaState`), `AncsSessionEngine.performAction` + `SessionEffect.ActionResult`, `AmsSession` (comparte la cola GATT; solo arranca con ANCS listo; sus fallos no tocan ANCS), `CardPresenter` (botones con etiquetas del iPhone, timbre que se corta al pulsar, descarte sincronizado solo en avisos que no son llamadas), `MediaPresenter`, `CommandReceiver` (botones y descartes por broadcast interno al servicio en marcha), `SharedState.kt` (`ActiveCards`, `MediaStatus`) y la UI en `ui/` (`MainActivity`, `Sections`, `StatusText`).

## Qué sigue, en orden

1. **Prueba conjunta de golpe** con la sección «Prototipo 0.2.0-lab» de `test/hil/windows/p1-campaign.md` (UI01, N10–N18, M01–M06), registrando logcat y resultados reales. Vigilar: corte del timbre al pulsar, descarte sincronizado con el reloj inactivo, controles de música con reproducción activa, y repetir «Recuperar conexión» y R03 con 0.2.1.
2. **Perfiles de app**: si el usuario aporta logotipos con derecho de uso, sustituir los monogramas.
3. **Tile/complicación**: requiere añadir `androidx.wear.tiles`/`protolayout`, que no están en la caché offline de esta PC; descargarlos con red antes de implementarlos.
4. **Pruebas físicas con avisos reales**: Telegram, Netflix, Spotify, Gmail y una llamada real; registrar cada caso en `test/hil/windows/p1-campaign.md` y en `evidence/<run>/windows/`.
5. **Cerrar P1 Windows**: ejecutar H05 (30 min con pantallas dormidas), R01 (veinte ciclos), R03 y R04; redactar ADR-001; actualizar `handoff-G0.md` (o crear `handoff-G1.md`) con commit, APK y SHA-256 reales.
6. **No avanzar a P2** (codec propio, seguridad, persistencia) hasta que G1 esté aprobado de forma conjunta con el lado Apple.

## Cómo operar en esta máquina (Windows 11)

- Java: Temurin 21 (`JAVA_HOME` de usuario). El JBR de Android Studio (JDK 25) no se usa para compilar.
- Compilar y probar desde Bash o terminal: `./gradlew.bat :core:protocol-kotlin:test :core:notification-domain:test :core:assistant-domain:test :apps:watch:lintDebug :apps:watch:assembleDebug`. El APK queda en `apps/watch/build/outputs/apk/debug/watch-debug.apk`. Si Gradle sigue bloqueado por la caché global, usar `--gradle-user-home build/g3 --project-cache-dir build/gradle-p1-project-3 --offline --no-watch-fs --no-configuration-cache --init-script scripts/windows/native-output.init.gradle -Pwristbridge.nativeBuildRoot=<carpeta local>` con `GRADLE_RO_DEP_CACHE=build/gradle-android-p1-home/caches` (así se compiló 0.2.0-lab).
- ADB inalámbrico al reloj: el puerto de **conexión** y el de **vinculación** son distintos y cambian al reactivar. Descubrirlos con `adb mdns services` (`_adb-tls-connect` y `_adb-tls-pairing`); `adb pair <ip:puerto-vinculación> <código>` y luego `adb connect <ip:puerto-conexión>`. El Wi-Fi del reloj pierde muchos paquetes y ADB cae a `offline`: reintentar en bucle y mantener el reloj cargando con la pantalla encendida. Las pantallas se leen con `uiautomator dump`, el log con `adb logcat -s WristBridge`.
- Para que el selector del iPhone vea el reloj, su nombre Bluetooth debe contener `WristBridge` (botón «Nombre WristBridge» de la app; «Restaurar nombre» lo revierte). Si el nombre vuelve al original, el selector responde «No accessory found».
- El iPhone concede ANCS solo tras el vínculo y con «Compartir notificaciones del sistema» activo para el reloj en Ajustes → Bluetooth.

## Problemas conocidos

- **Gradle bloqueado en esta máquina.** Un `java.exe` de Gradle quedó en estado «Unknown» e imposible de terminar, y retiene bloqueos de `~/.gradle/caches/journal-1` y de `.gradle/9.8.1/fileHashes`. Un reinicio de Windows lo libera. Mientras tanto se puede compilar con `GRADLE_USER_HOME` y `--project-cache-dir` en carpetas nuevas, aunque la primera vez descarga todas las dependencias y puede tardar más de 10 minutos. En Windows, PowerShell y `git` lanzados desde PowerShell se han colgado: preferir Bash.
- **Wi-Fi del reloj inestable** (ver arriba). Instalar el APK exige varios reintentos.
- El nombre de anuncio `WristBridge` depende de renombrar el adaptador del reloj; confirmar con el Mac en ADR-001 si hay alternativa (datos de servicio y máscara).
- Un cuerpo de aviso sintético largo llegó con 256 bytes en el log; verificar si lo recorta iOS o nuestro límite antes de dar N02 por aprobado.

## Último commit conocido de cada rama

Ver `git log` de `development` y `development_claude`; el commit con builds 2 y 3 y este SEED los integra.

## Trabajo integrado · 18 octubre 2026 (0.5.0-lab)

HECHO: corrección de CardPresenter sin CallStyle (Android 14+ lo rechaza sin servicio en primer plano ni pantalla completa y tumbaba el puente en cada llamada), recuperación ANCS acotada, prioridad de llamadas entrantes y captura local de salud con Health Services (opcional; permisos por métrica concedidos por el usuario en la pantalla Salud). Validado: 95 pruebas JVM, lint y compilación PASS. El envío de salud al iPhone sigue sin existir (necesita contrato P2 y sesión segura).

## Música estilo Spotify · 18 octubre 2026 (0.5.2-lab, build 13)

HECHO: `MusicScreen` rediseñada (anillo de progreso en el bisel, color de la app como fondo, chip con el nombre del reproductor, play grande en el color de la app, anterior/siguiente planos, volumen debajo y en la corona). Arreglo de play: con Spotify en pausa larga, iOS deja de anunciar reproductor pero sigue aceptando PLAY; antes la pantalla ocultaba los controles y no había cómo darle play. Ahora muestra «Nada en reproducción» con botón Reanudar (`resumeCommand`). El botón cambia al instante tras el toque (3 s máximo) para que un segundo toque no mande el comando contrario. Validado: 217 pruebas JVM, lint sin errores, assemble PASS; instalada en el Watch7 (SHA-256 `198137f1…4ef8`); en el reloj, PLAY desde la nueva pantalla puso Spotify a reproducir (el tiempo avanzó). El caso de reanudar sin reproductor es NOT_RUN en físico.
