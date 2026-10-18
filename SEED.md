# SEED · semilla del proyecto WristBridge

> **Primer archivo que lee cualquier agente (Claude, Codex, otro) y último que actualiza.**
> Contiene el estado vivo del proyecto para que cualquier agente continúe sin perder contexto.
> Si algo aquí contradice un documento antiguo, esto se verifica con `git log` y se corrige aquí.

Última actualización: 2026-10-18 · por: Codex · rama: `development_codex`

## 1. Qué es
App iPhone (Swift) que configura el accesorio y genera avisos de prueba para un Galaxy Watch7 (Kotlin, equipo Windows). El reloj consume ANCS directamente. Tres productos: iPhone (este repo, Apple), reloj (Windows), Mac (P6, futuro).

## 2. Protocolo para cualquier agente (obligatorio)
1. **Leer**: este archivo → `AGENTS.md` → los docs que `AGENTS.md` indica. No reabrir decisiones listadas en §6.
2. **Antes de editar**: `git status` y `git log -5`. Si hay cambios sin commit de otro agente, listarlos en §4 y continuarlos; no descartarlos.
3. **Reclamar tarea**: poner una fila en §4 (`EN CURSO`, agente, fecha) antes de empezar. Un solo agente por conjunto de archivos.
4. **Al terminar o ser interrumpido**: dejar la fila como `HECHO` o `PARCIAL` con lo que falta, actualizar §3 y la fecha de arriba. Sin esto el trabajo se considera incompleto.
5. **Verificar antes de afirmar**: `bash scripts/apple/test-package.sh` y `bash scripts/apple/check.sh`. Registrar PASS/FAIL/BLOCKED/NOT_RUN reales. Simulador, mocks y compilación nunca aprueban G1 ni gates físicos.
6. **No tocar**: `Doc/**` (hashes del manifiesto), Kotlin/Gradle/`contracts/**` (Windows), ni secretos/identidad de firma en archivos versionados (usar `apps/ios/Signing.local.xcconfig`, ignorado).
7. Tras añadir fuentes Swift: `python3 scripts/apple/generate_project.py`.
8. Commits solo si el usuario lo pide. Rama de trabajo `development_codex`; integración en `developer`.

## 3. Estado actual
| Área | Estado | Evidencia |
|---|---|---|
| Candidato firmado instalado en iPhone | 0.3.3 (10): firma e instalación finales PASS; apertura final BLOCKED (se bloqueó de nuevo). Candidato previo de la misma versión: apertura y servicio PASS, ATT 512/512 medido, con huellas separadas. Ensayo de 20 reconexiones NOT_RUN | `evidence/apple-0.3.3/apple/run.json` |
| Integración con Windows | `origin/developer` y `origin/development_codex` contienen `5c79d64`: Apple 0.3.1/0.3.2, Windows 0.4.0-lab y respuesta P7, sin conflictos. El borrador P2 sigue sin congelar | `git log` y `git fetch`, 2026-10-18 |
| Código actual | 0.3.3 (10): continuación de accesibilidad y diagnóstico ATT persistido; validación local PASS, entrega instalada | `docs/apple/handoff-0.3.3.md` |
| Pruebas del paquete Swift | PASS, 26 pruebas (2026-10-18) | `scripts/apple/test-package.sh` |
| Compilación + UI tests simulador | 0.3.3: PASS build y 8/8 UI; tras último ajuste, build aislado y 2/2 pruebas afectadas PASS (iPhone 17 Pro iOS 26.2) | 2026-10-18 |
| 0.3.1 (8) en iPhone físico | Firmado e **instalado: PASS**; apertura **BLOCKED** (iPhone bloqueado); reconexión en vivo y aceptación **NOT_RUN** | `evidence/apple-0.3.1/apple/run.json` |
| 0.3.0 en iPhone físico (histórico) | Firmado y **instalado: PASS**; apertura **BLOCKED** (iPhone bloqueado); ensayo de 20 reconexiones y aceptación **NOT_RUN** | `evidence/apple-0.3.0/apple/run.json` |
| Campaña P2 20 ciclos / 19-de-20 en 30 s | **NOT_RUN** física | `test/hil/apple/p2-recovery.md` |
| Gates G1/G2 y campaña conjunta | **PENDIENTES** (necesitan reloj + Windows) | `docs/apple/iphone-phases.md` |
| Wire format / seguridad / propiedades BLE | **Bloqueado**: contrato conjunto `p1-lab-1` no congelado | `Doc/01_CONTRATO_COMPARTIDO.md` |

Fases P1–P8: ver tabla en `docs/apple/iphone-phases.md` (no se duplica aquí).

## 4. Registro de trabajo (más reciente arriba)

Revisión Apple de ADR-001: completada en `docs/apple/adr-001-review.md`; recomendación A condicionada, sin aprobar G1.
| Fecha | Agente | Tarea | Estado | Notas / qué falta |
|---|---|---|---|---|
| 2026-10-18 | Codex | Continuación del relevo: accesibilidad de interfaz, validación 0.3.3, candidato físico y actualización del estado integrado | HECHO | 0.3.3 instalada; 26 Swift, 8 UI + 2 UI finales PASS. Servicio/ATT físico PASS en candidato previo, huellas separadas. Apertura final BLOCKED por bloqueo del iPhone. Ensayo físico pendiente. |
| 2026-10-18 | Claude | Revisión de lo que dejó Windows (`docs/windows/handoff-to-apple.md`, `contracts/proposals/p2-channel-draft.md`): respuesta Apple en `docs/apple/p2-draft-response.md` (Q-A1…Q-A10, hallazgo de contradicción en 3.5 sobre carga por frame, 10 vectores a mano NOT_RUN). 0.3.2 (9): diagnóstico registra `att-capacity` (solo lectura) para Q-A5 | HECHO (sin commit; build 0.3.2 no instalada) | Sin codec/claves/mensajes. Falta: medir `att-capacity` en el iPhone real, respuesta de Windows a (A)/(B), revisión independiente de §8, verificar Keychain tras desinstalar (Q-A7). |
| 2026-10-18 | Claude | Salud P7: investigación y propuesta `docs/apple/health-sync-proposal.md` (reloj lee Health Services → outbox; iPhone pide por cursor, guarda en libro local y confirma; Apple Salud opcional). Samsung Health/Health Connect son solo Android | HECHO (solo propuesta, sin código) | Bloqueado por G2/contrato/sesión segura y por saber si el Watch7 expone pasos/ritmo a terceros. Preguntas abiertas para Windows en el documento. No se implementó HealthKit ni mensajes BLE. |
| 2026-10-18 | Claude | 0.3.1 (8): (a) reconexión en vivo: el tiempo de espera de descubrimiento ya no detiene la recuperación, `LinkResumePolicy` + `BridgeModel.resumeLinkIfNeeded()` al volver a primer plano y cada 8 s; (b) pantallas `Views/FeatureScreens.swift`: Esferas (borrador local), Salud, Buscar reloj (última conexión local `lastLinkedAt`), Música; acciones que requieren canal seguro siguen bloqueadas | HECHO (sin commit) | Causa de (a) inferida del código (stop() tras 20 s sin servicio dejaba el enlace muerto hasta relanzar), NO reproducida en dispositivo: verificar apagando/encendiendo el reloj con el iPhone abierto. Esferas/Salud/Buscar/Música no envían ni leen nada del reloj. |
| 2026-10-18 | Claude | Animaciones: `Views/Motion.swift` (resorte, aparición escalonada, profundidad al desplazar, fondo MeshGradient vivo), reloj con brillo que respira, marcas de segundero, radar al conectar, onda + háptica al verificar e inclinación 3D; acciones con morph de vidrio y efectos de símbolo; transiciones zoom; anillo y 20 puntos en Reconexión; resumen animado en Notificaciones; barra de pestañas que se minimiza | HECHO (sin commit) | Revisado en simulador claro/oscuro en demo. Todo efecto continuo se congela con Reducir movimiento (no verificado visualmente). Falta iPhone físico y medir batería/CPU del fondo animado. |
| 2026-10-18 | Claude | Rediseño UI: pestañas Reloj/Notificaciones/Ajustes, reloj redondo vectorial con estado, acciones rápidas en vidrio (Liquid Glass), tarjetas de funciones, hoja "Próximamente" bloqueada (esferas, salud, buscar, música) | HECHO (sin commit) | Revisado en simulador en modo demo. Falta verlo en iPhone físico y modo oscuro. Funciones bloqueadas NO se simulan: esperan contrato/gates. |
| 2026-10-18 | Claude | Terminar ensayo de reconexión 0.3.0 + crear SEED | HECHO (código y docs) | Revisado: compila y pasa pruebas. Falta firmar/instalar 0.3.0 y ejecutarlo en el iPhone físico (requiere usuario con iPhone desbloqueado). Sin commit. |
| 2026-10-18 | Codex | Ensayo de reconexión: `RecoveryCampaign`, repositorio, controlador, sección UI, pruebas, integración en `BridgeModel`/`ContentView`, bump 0.3.0 | PARCIAL → cerrado arriba | Dejó sin commit ni docs ni evidencia. |
| 2026-10-18 | Codex | 0.2.1: lotes 10/25/50, instalación firmada, traza física | HECHO, commit `8b066f2` | `docs/apple/handoff-0.2.1.md` |

## 5. Siguiente trabajo, por prioridad
1. **Apple + usuario:** comprobar versión 0.3.3 (10), servicio y recuperación al apagar/encender el reloj con la app abierta. Estado de instalación/apertura en `evidence/apple-0.3.3/apple/run.json`.
2. Ejecutar el ensayo de **20 reconexiones** con la app visible y compartir el informe. Sigue NOT_RUN físico; mide solo configuración y no aprueba G1.
3. Q-A5 medido en iPhone: 512 bytes con respuesta y 512 sin respuesta. Ver traza fechada en `evidence/apple-0.3.3/apple/`; Windows debe usar las capacidades negociadas/observadas de su propia API y probar los frames, sin inferir capacidad Notify.
4. Windows debe resolver regla A/B de fragmentación y revisar los vectores/respuestas de `docs/apple/p2-draft-response.md`. Falta revisión independiente de seguridad, contrato final y vectores cruzados antes de codec/Keychain/sesión Swift.
5. Campaña conjunta G1/ADR-001, radio/permisos/distancia y 24/72 h con consumo y referencia. Accesibilidad y CPU/batería aún requieren comprobación física.
6. P7: Windows contestó en `docs/windows/respuesta-salud-p7.md`; es inspección de sensores, no prueba con app de terceros. ADR-009 y ledger/retención se diseñan conjuntamente; no se implementan sensores/HealthKit hasta G2 y canal seguro.
7. «Abrir en iPhone», respuestas mediante Atajos, ajustes remotos y botones bloqueados de `FeatureScreens.swift` esperan contratos y capacidades aprobadas. Los controles AMS y acciones ANCS existentes pertenecen al reloj.

Las anotaciones históricas «sin commit» de §4 describen el momento de su escritura; los commits posteriores 70a30b6 y 181ccf2 ya incorporaron 0.3.1/0.3.2. No repetir ni descartar ese trabajo.

## 6. Decisiones firmes (no reabrir)
- ANCS lo consume el reloj; el iPhone no puede tratar su autorización de notificaciones ni un enlace CoreBluetooth como "ANCS listo".
- Sin códec inventado ni parseo de estado del reloj hasta contrato conjunto. UUID reservados solo del contrato original.
- El ensayo de 20 reconexiones mide **solo canal de configuración**, en primer plano; su exportación declara `ancsVerified=false` y `jointGateApproved=false`. No aprueba entregas de avisos ni gates.
- IA, macOS, salud y comandos BLE sensibles esperan sus gates.
- Correcciones a `Doc/**` van en `docs/apple/review.md` y handoffs, nunca editando el original.
- Sin identificadores personales, certificados, tokens ni contenido real de notificaciones en Git.

## 7. Mapa mínimo del repo
- `apps/ios/` app SwiftUI (`Views/HomeView.swift` pestaña Reloj, `WatchHeroView.swift` reloj dibujado, `Theme.swift` estilos y estado visual, `Motion.swift` animaciones compartidas) + proyecto Xcode generado (`scripts/apple/generate_project.py`).
- `packages/WristBridgeProtocol/` modelos y repositorios Swift con pruebas.
- `scripts/apple/` check, test-package, prepare-release, install-candidate, validate_structure.
- `docs/apple/` handoffs y revisión · `test/hil/apple/` guías de pruebas físicas · `evidence/` resultados.
- `Doc/` original inmutable · `core/ apps/watch contracts/` son de Windows.
