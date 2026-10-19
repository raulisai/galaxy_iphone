# Estado de fases y cola del lado Windows/Kotlin

18 de octubre de 2026 · HEAD `6b30455`, build 0.5.0-lab (11). Auditoría de solo lectura. «PASS observado» = visto por log o por el usuario, no campaña formal. G0 es lo único con cierre documentado; G1, G2 y los casos H/R/L/S/E2E conjuntos siguen sin aprobar.

## Resumen

- 95 pruebas JVM (36 protocolo, 58 notificaciones, 1 asistente), 5/5 instrumentadas, lint 0 errores, APK firmado y con checksum verificado.
- **Hecho:** P1 en lo que cubre Windows (ANCS, AMS, acciones, tarjetas, recuperación acotada, diario de campaña), captura local de salud con Health Services, UI nativa del reloj.
- **No existe aún en el código:** codec/framer del canal propio, Keystore, Tile/complicación, SSE/IA (solo `AssistantGate` apagado), audio/voz, cliente P6, outbox de intercambio de salud, SBOM formal, firma de lanzamiento, perfiles de rendimiento.

## Por fase

| Fase | Estado | Qué lo bloquea o falta |
|---|---|---|
| P1 | Implementada; G1 sin aprobar | Campaña física H05, R01–R04, N02 (cuerpo de 256 bytes), regresión con llamada real de Telegram, inventario del reloj (P1-01.K), ADR-001 definitivo |
| P2 | Parcial: P2-07 acciones y P2-08 diario hechos; P2-04/05/06 bloqueadas | Decisiones K1–K3, revisión independiente de seguridad, contrato congelado; después codec, Keystore y almacén |
| P3 | Parcial: UI, música, privacidad | Tile/complicación (falta `androidx.wear.tiles`/`protolayout` en la caché offline), rendimiento (benchmark), TalkBack en el reloj |
| P4 | No iniciada | ADR de proveedor y autenticación, credenciales; el dominio con proveedor ficticio se puede programar sin red |
| P5 | No iniciada | Spike de WebRTC y audio necesitan el reloj; `ai-gateway` necesita ADR y destino |
| P6 | No iniciada | Canal P2 y decisión sobre el agente Mac |
| P7 | Parcial: captura local | Outbox de intercambio, cursor y ACK necesitan el canal seguro; contactos sin empezar |
| P8 | Parcial | Firma de lanzamiento (clave fuera de Git), SBOM formal (plugin sin red), regresión final |

## Cola que dejó Apple (`docs/apple/handoff-to-windows.md`)

| # | Tarea | Estado |
|---|---|---|
| K1–K2 | Carga por frame, Q-A3, Q-A9, vectores y manifiesto | Decididas por Windows como editor (ver `p2-decisions.md`), borrador no congelado |
| K3 | Revisión independiente de seguridad y biblioteca Noise | Bloqueada: requiere un revisor externo y decisión del usuario |
| K4–K5, K7–K8 | Características GATT, codec con cifrado, buscar iPhone/reloj | Bloqueadas por K3 |
| K6 | Salud: intercambio por cursor | Captura local hecha; intercambio bloqueado por K3 |
| Campaña física | H05, R01, R03, R04, N02, `att-capacity` (512 B informados por el iPhone, no usar como constante) | NOT_RUN: requiere usuario con iPhone y reloj |

## Bloqueos que no se resuelven programando

Aprobación de G1/G2, revisión independiente de §8, ADR de proveedor de IA y credenciales, ADR del `ai-gateway`, ADR-009 de salud (Apple redacta, Windows revisa), contrato P2 congelado con Apple, decisión del Mac (P6), clave de firma de lanzamiento, y toda campaña con hardware.

## Qué se puede programar ya (sin iPhone ni contrato nuevo)

1. Framer P2 sin cifrado contra los vectores revisados (`contracts/`, `test/fixtures/shared/`).
2. Pruebas JVM del catálogo que faltan (A05–A07, N10, N13, N14, R10/R11, Q10).
3. Dominio del asistente con proveedor ficticio (SSE, cancelación, errores) detrás de `AssistantGate`.
4. Lógica pura de voz (buffers, foco) y controlador de herramientas (Q11/Q12).
5. Accesibilidad estática, script de validación reproducible e inventario de dependencias.
6. Con red: dependencias de Tile/complicación y benchmark.
