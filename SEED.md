# SEED · semilla del proyecto WristBridge

> **Primer archivo que lee cualquier agente (Claude, Codex, otro) y último que actualiza.**
> Contiene el estado vivo del proyecto para que cualquier agente continúe sin perder contexto.
> Si algo aquí contradice un documento antiguo, esto se verifica con `git log` y se corrige aquí.

Última actualización: 2026-10-18 · por: Claude (Opus 5.5) · rama: `development_codex`

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
| Candidato firmado instalado en iPhone | 0.2.1 (6): PASS instalación y servicio restaurado | `evidence/apple-0.2.1/apple/` |
| Código 0.3.0 (7) | Ensayo de reconexión de 20 intentos (commit `cef2c29`) + rediseño UI estilo Galaxy Wearable/iOS 26 (sin commit) | §4 |
| Pruebas del paquete Swift | PASS, 23 pruebas (2026-10-18) | `scripts/apple/test-package.sh` |
| Compilación + UI tests simulador | PASS build; PASS 5/5 UI tests en iPhone 17 Pro iOS 26.2 (`WRISTBRIDGE_TEST_DESTINATION=id=<sim>`) | 2026-10-18 |
| 0.3.0 en iPhone físico | **NOT_RUN** (no firmado ni instalado) | — |
| Campaña P2 20 ciclos / 19-de-20 en 30 s | **NOT_RUN** física | `test/hil/apple/p2-recovery.md` |
| Gates G1/G2 y campaña conjunta | **PENDIENTES** (necesitan reloj + Windows) | `docs/apple/iphone-phases.md` |
| Wire format / seguridad / propiedades BLE | **Bloqueado**: contrato conjunto `p1-lab-1` no congelado | `Doc/01_CONTRATO_COMPARTIDO.md` |

Fases P1–P8: ver tabla en `docs/apple/iphone-phases.md` (no se duplica aquí).

## 4. Registro de trabajo (más reciente arriba)
| Fecha | Agente | Tarea | Estado | Notas / qué falta |
|---|---|---|---|---|
| 2026-10-18 | Claude | Rediseño UI: pestañas Reloj/Notificaciones/Ajustes, reloj redondo vectorial con estado, acciones rápidas en vidrio (Liquid Glass), tarjetas de funciones, hoja "Próximamente" bloqueada (esferas, salud, buscar, música) | HECHO (sin commit) | Revisado en simulador en modo demo. Falta verlo en iPhone físico y modo oscuro. Funciones bloqueadas NO se simulan: esperan contrato/gates. |
| 2026-10-18 | Claude | Terminar ensayo de reconexión 0.3.0 + crear SEED | HECHO (código y docs) | Revisado: compila y pasa pruebas. Falta firmar/instalar 0.3.0 y ejecutarlo en el iPhone físico (requiere usuario con iPhone desbloqueado). Sin commit. |
| 2026-10-18 | Codex | Ensayo de reconexión: `RecoveryCampaign`, repositorio, controlador, sección UI, pruebas, integración en `BridgeModel`/`ContentView`, bump 0.3.0 | PARCIAL → cerrado arriba | Dejó sin commit ni docs ni evidencia. |
| 2026-10-18 | Codex | 0.2.1: lotes 10/25/50, instalación firmada, traza física | HECHO, commit `8b066f2` | `docs/apple/handoff-0.2.1.md` |

## 5. Siguiente trabajo, por prioridad
1. **(usuario + iPhone)** `bash scripts/apple/prepare-release.sh /tmp/WristBridge-iPhone-0.3.0` y `python3 scripts/apple/install-candidate.py /tmp/WristBridge-iPhone-0.3.0 --launch`; ejecutar el ensayo de 20 reconexiones y compartir el informe. Registrar PASS/FAIL en `evidence/apple-0.3.0/apple/run.json` (carpeta aún inexistente; no inventar resultados).
2. Actualizar `docs/provenance/apple-inventory.json` y `evidence/**` al generar el candidato 0.3.0 (hoy menciona 0.2.1).
3. Quitar la entrada de `README.md` "Candidato actual 0.2.1" cuando 0.3.0 se instale y pruebe; hasta entonces dice 0.2.1 por ser lo verificado en dispositivo.
4. P3 local: revisar modo oscuro y Dynamic Type del rediseño; añadir prueba UI de la pantalla Reconexión. P8 empaquetado autorizado.
5. Esperar a Windows: wire format, seguridad, UUID/propiedades finales, nombre anunciado `WristBridge` (propuesta Apple pendiente de confirmar).
6. Cuando exista el canal seguro: convertir las tarjetas "Próximamente" de `Views/HomeView.swift` en funciones reales, una por gate aprobado.

## 6. Decisiones firmes (no reabrir)
- ANCS lo consume el reloj; el iPhone no puede tratar su autorización de notificaciones ni un enlace CoreBluetooth como "ANCS listo".
- Sin códec inventado ni parseo de estado del reloj hasta contrato conjunto. UUID reservados solo del contrato original.
- El ensayo de 20 reconexiones mide **solo canal de configuración**, en primer plano; su exportación declara `ancsVerified=false` y `jointGateApproved=false`. No aprueba entregas de avisos ni gates.
- IA, macOS, salud y comandos BLE sensibles esperan sus gates.
- Correcciones a `Doc/**` van en `docs/apple/review.md` y handoffs, nunca editando el original.
- Sin identificadores personales, certificados, tokens ni contenido real de notificaciones en Git.

## 7. Mapa mínimo del repo
- `apps/ios/` app SwiftUI (`Views/HomeView.swift` pestaña Reloj, `WatchHeroView.swift` reloj dibujado, `Theme.swift` estilos y estado visual) + proyecto Xcode generado (`scripts/apple/generate_project.py`).
- `packages/WristBridgeProtocol/` modelos y repositorios Swift con pruebas.
- `scripts/apple/` check, test-package, prepare-release, install-candidate, validate_structure.
- `docs/apple/` handoffs y revisión · `test/hil/apple/` guías de pruebas físicas · `evidence/` resultados.
- `Doc/` original inmutable · `core/ apps/watch contracts/` son de Windows.
