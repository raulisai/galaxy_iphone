# Traspaso Apple → Windows/Kotlin · tareas abiertas

18 de octubre de 2026 · Apple (`development_codex`, iPhone 0.3.3 (10) instalado y abierto en el iPhone 15). Para el agente/computadora que continúe el lado del reloj. Lee primero `docs/windows/SEED.md`, [`p2-draft-response.md`](p2-draft-response.md) y [`health-find-integration-status.md`](health-find-integration-status.md). Apple no escribe Kotlin ni `contracts/**`.

## Bloqueadas por una decisión tuya (primero)

| # | Tarea Kotlin / editor del contrato | Por qué |
|---|---|---|
| K1 | Elegir (A) o (B) para la carga por frame (contradicción en §3.5 del borrador) y cerrar Q-A3 (sin intercalado) y Q-A9 (sin DATA sin AEAD) | Sin esto no hay vectores F02/F03 ni codec |
| K2 | Revisar los 10 vectores a mano de `p2-draft-response.md` y fijar el formato del manifiesto en `contracts/` | Apple los usa para el codec Swift |
| K3 | Pedir la revisión independiente de §8 (seguridad) y elegir biblioteca Noise/X25519/ChaCha20 para Android | Ningún dato de salud ni comando sensible antes |

## Reloj (Kotlin), cuando se abra el gate P2/G2

| # | Tarea | Notas |
|---|---|---|
| K4 | Exponer las tres características reservadas con las propiedades del borrador (Capabilities READ, Requests WRITE, Events NOTIFY + CCCD cifrado) y registro de capacidades de 16 bytes | Hoy solo existe el servicio primario |
| K5 | Framer/codec según el contrato aprobado y pruebas JVM contra los vectores; Keystore para la identidad; registro con código de seguridad (SAS) | No empezar antes de K1–K3 |
| K6 | **Salud** (formato que el iPhone ya acepta: [`health-ingest-spec.md`](health-ingest-spec.md); pasos como instantánea diaria `stepsDaily` están aceptados): Health Services pasivo (pasos, ritmo cardiaco; distancia/energía solo si hay datos), intervalos de 5 min, outbox persistente 7 días con marcadores de hueco, borrar solo tras confirmación de guardado, cursor por métrica | Permisos por métrica en el reloj. iPhone ya tiene el libro (`HealthLedger`) y valida: rechaza negativos, duplica nada, exige `syncId` ASCII ≤128, `deviceId` ≤64, valores acotados |
| K7 | **Buscar iPhone**: botón en la app del reloj y comando «buscar iPhone» por el canal seguro, con resultado `solicitado` (no «sonó») | iPhone: `FindPhoneAlarm` ya suena 60 s; falta solo la orden |
| K8 | **Buscar reloj** (iPhone → reloj): recibir el comando y sonar/vibrar en el reloj con confirmación | Pantalla del iPhone bloqueada hasta que exista |

## Campaña física pendiente (sin depender del contrato)

- H05 (30 min con pantallas dormidas), R01 (veinte ciclos), R03, R04 y borrado de duda sobre el cuerpo largo de 256 bytes (N02). Ver `docs/windows/SEED.md`.
- Instalar ADR-001 definitivo con evidencia conjunta: [`adr-001-review.md`](adr-001-review.md) trae la revisión Apple.

## Qué entrega el iPhone para probar contigo

- Diagnóstico con `att-capacity` (`maximumWriteValueLength`) tras verificar el servicio: pásalo por «Compartir diagnóstico» para fijar los límites de lote (Q-A5).
- Recuperación de enlace sin relanzar la app (0.3.1+): al apagar y encender el reloj con la app abierta debe volver a «Conectado»; repórtalo con la traza si no.
- Ensayo de 20 reconexiones (pantalla Reconexión) con exportación JSON.

## Integración

`origin/developer` y `origin/development_codex` están en el mismo commit; fusiona `developer` en `development`/`development_claude` antes de seguir. Apple no toca esas ramas.
