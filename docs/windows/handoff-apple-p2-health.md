# Tareas para Apple: canal P2 seguro y salud (relevo del lado Windows/Kotlin)

19 de octubre de 2026. Ejecutar en `development_codex` (rama de Apple); integrar en `developer`. El lado reloj (0.6.0-lab) ya expone el canal; falta el lado iPhone. Contrato: `contracts/proposals/p2-channel-draft.md` (Revisión 3, candidato a congelar; **confirmar o corregir**). Detalle de mensajes: `docs/windows/p2-seguridad-y-salud-kotlin.md`.

## Qué hace ya el reloj (para probar contra él)
- Servicio `…36a01` con tres características: Capabilities `…36a02` (READ cifrado, 16 bytes), Requests `…36a03` (WRITE con respuesta, cifrado), Events `…36a04` (NOTIFY, CCCD cifrado).
- Responde como *responder* Noise_XX_25519_ChaChaPoly_SHA256. Prólogo: `"WB-P2|1.0|p2-cand-1|6f06b8b1-21d9-4c65-9d7e-118880e36a01|"` + los 16 bytes de Capabilities leídos.
- Mensajes de handshake en frames HANDSHAKE con `messageId` 1 (iPhone→reloj), 2 (reloj→iPhone), 3 (iPhone→reloj). Sin ACK de aplicación para HANDSHAKE; sí ACK para cada DATA válido.
- DATA: `contador(u64 BE) ‖ ChaCha20-Poly1305(JSON)`, AD = `frameVersion ‖ type ‖ flags ‖ messageId(u16) ‖ totalLength(u32)`; el contador debe ser exactamente el esperado. `messageId` de DATA desde 16.
- El reloj acepta una identidad nueva solo con la ventana «Vincular iPhone» abierta (120 s) y tras pulsar «Coincide» con el código de 6 dígitos. Después la clave estática del iPhone queda fijada; otra clave ⇒ `E_IDENTITY_CHANGED`.
- Capabilities JSON: `hello` (responde `sessionId` = 8 primeros bytes de `h`, en hex) y `health.sync` (`cursor`, `ack`).
- El MTU debe negociarse **antes** de habilitar el CCCD de Events (el reloj fija el tamaño de frame ahí).

## Tareas
| ID | Tarea | Hecho cuando |
|---|---|---|
| A-P2-01 | Revisar y confirmar/corregir la Revisión 3 del contrato (sobre JSON, ids, contador explícito, sin ACK en handshake, prólogo). Responder en `docs/apple/` | Documento de decisión |
| A-P2-02 | Portar `NoiseXX` (iniciador) a Swift con CryptoKit (Curve25519, ChaChaPoly, SHA256, HMAC) y reproducir `test/fixtures/shared/p2-noise-xx-vectors.json` (ver `NoiseVectorsTest.kt`; el nombre del protocolo mide 32 bytes, `h` = nombre sin hash) | Pruebas Swift con los vectores en PASS |
| A-P2-03 | Sesión DATA en Swift (cifrado/descifrado con AD, contador, JSON estricto: sin claves duplicadas, profundidad 8, enteros ±2^53−1) sobre tu `P2Framing` | Pruebas ida y vuelta y manipulación/repetición |
| A-P2-04 | Capa Core Bluetooth: descubrir características, leer Capabilities, negociar MTU, habilitar CCCD y esperar `didUpdateNotificationStateFor`, escribir frames con respuesta de uno en uno | Registro en dispositivo |
| A-P2-05 | Pantalla de registro: mostrar el código de 6 dígitos (`SHA256("WB-SAS-v1"‖h)`, primeros 4 bytes BE mod 1 000 000, 6 dígitos con ceros) y guardar la clave estática del reloj en Keychain (`AfterFirstUnlockThisDeviceOnly`, sin iCloud) solo tras confirmar | Emparejamiento real con el reloj 0.6.0-lab |
| A-H01 | Libro local de salud (H-A01): almacenamiento persistente excluido de copias, idempotente por `sampleId`; envía `health.sync cursor` al conectar y **solo después de guardar** envía `ack` | Pruebas S01–S06 + datos reales del reloj |
| A-H02 | Pantalla Salud con datos del libro; permisos de Apple Salud solo por métrica y bajo demanda (H-A02, opcional) | UI con datos reales |
| A-H03 | ADR-009 (libro y retención) | Documento |
| A-H04 | Registrar PASS/FAIL/NOT_RUN en `evidence/` y actualizar `SEED.md` | Evidencia |

## Aviso de seguridad
La implementación Noise del reloj es propia y **no está revisada** (K3). Mientras no se cierre K3, usar solo datos del propio usuario en pruebas. Si Apple prefiere una biblioteca revisada, avisar para cambiar ambos lados a la vez.

## Prompt listo para pegar en la sesión de Apple
> Lee `docs/windows/handoff-apple-p2-health.md`, `docs/windows/p2-seguridad-y-salud-kotlin.md` y la sección «Revisión 3» de `contracts/proposals/p2-channel-draft.md`. Implementa A-P2-01 a A-P2-05 y A-H01 en Swift sin tocar Kotlin ni contratos; usa `test/fixtures/shared/p2-noise-xx-vectors.json` como oráculo. Documenta cualquier discrepancia en `docs/apple/` y no cambies el contrato sin dejarlo anotado para Windows.

## Actualización 0.6.1-lab (19 de octubre de 2026): alineado con `health-ingest-spec.md` y K7/K8
APK `build/releases/wristbridge-watch-0.6.1-lab-15.apk` instalado en el reloj; la autoprueba criptográfica del reloj (handshake Noise + ChaCha20-Poly1305 + código) da **correcta**. Keystore y pairing real: NOT_RUN (falta el iPhone).

- **X25519 propio** (RFC 7748, BigInteger): el proveedor de Android rechaza las especificaciones que acepta el JDK. Produce los mismos bytes (vector RFC y vector Noise sin cambios). No es de tiempo constante: entra en la revisión K3.
- **Muestras `health.sync` ya en formato del libro de Apple**. Respuesta `batch`: `{op, deviceId, after, last, more, samples[]}`; cada muestra `{c, m, s, e, v, src:"watch-sensor", id, ver:1}` y, solo en `heartRate`, `min` y `max` (= `v`). `m` ∈ {`stepsDaily`, `heartRate`}; `deviceId` = `watch-` + 12 hex derivados de la clave pública del reloj; `id` = `syncId` (SHA-256 hex, 64 caracteres). El pulso del reloj llega con inicio = fin; el reloj fija `s = e − 1000 ms` para cumplir `end > start`.
- El reloj **no envía** muestras que el iPhone rechazaría (pulso fuera de 20–250, pasos > 300 000 o duración > 25 h, intervalo vacío en pasos). Aun así avanza `last` más allá de ellas: el iPhone debe pedir el siguiente lote con `after = last`, y solo enviar `ack` con `through = last` cuando `HealthStore.ingest` devuelva `true`.
- **K7 Buscar iPhone**: el reloj envía `{kind:"req", requestId, capability:"find.phone", payload:{}}` (botón «Buscar iPhone» en Conexión › Vincular iPhone, con sesión lista). El iPhone responde con `{kind:"res", requestId, capability:"find.phone", payload:{status:"started"}}` o `error:"E_..."`. El reloj muestra «Solicitado al iPhone» al recibir el ACK del mensaje y «aceptó la orden» con el `res`; nunca dice «sonó».
- **K8 Buscar reloj**: el iPhone envía `{kind:"req", requestId, capability:"find.watch", payload:{}}`; el reloj suena y vibra 30 s (botón «Detener sonido») y responde `payload:{status:"requested"}`.
- Tareas nuevas para Apple: A-K7 (`find.phone` → `FindPhoneAlarm`), A-K8 (enviar `find.watch`), y actualizar el parser de lotes a los campos de arriba.

## Estado de las tareas Apple en Swift (19-oct-2026, escritas en Windows, SIN compilar)
Escrito pero **NOT_RUN** (no hay Swift en la PC Windows): `packages/WristBridgeProtocol/Sources/WristBridgeProtocol/P2Noise.swift` (A-P2-02: Noise XX con CryptoKit) y `Tests/.../P2NoiseTests.swift` (reproduce `test/fixtures/shared/p2-noise-xx-vectors.json`). En el Mac: `git pull origin developer && bash scripts/apple/test-package.sh`; si los vectores no coinciden, revisar primero el orden de tokens y el relleno de `h`. Pendientes: A-P2-03 (sesión DATA y JSON estricto), A-P2-04/05 (Core Bluetooth, Keychain, pantalla de código), A-H01 (cableado a `HealthStore.ingest`), A-K7/K8.
