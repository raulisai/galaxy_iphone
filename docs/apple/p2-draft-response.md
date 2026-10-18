# Respuesta Apple al borrador P2 de Windows

18 de octubre de 2026 · Apple (`development_codex`) · responde a [`contracts/proposals/p2-channel-draft.md`](../../contracts/proposals/p2-channel-draft.md) y a [`docs/windows/handoff-to-apple.md`](../windows/handoff-to-apple.md). **Posición de revisión, no aprobación.** No cambia `p1-lab-1`, no implementa codec, claves ni mensajes (G1/G2 pendientes). Estado vivo: [`SEED.md`](../../SEED.md).

## Resumen

| Tema | Posición Apple |
|---|---|
| GATT (§1) | De acuerdo con Read / Write con respuesta / Notify. Pendiente de medir. |
| Compatibilidad (§2) | De acuerdo con el registro binario de 16 bytes. |
| Framing (§3) | De acuerdo en lo esencial. **Hallazgo:** contradicción en 3.5 (abajo). |
| Flujo (§5), recuperación (§6), errores (§7) | De acuerdo; observaciones menores. |
| Seguridad (§8) | **Sin aprobar.** Aceptable como candidato para evaluar; exige revisión independiente. |
| Vectores (§9) | Apple propone el formato y aporta vectores hechos a mano para el framing (sin cifrado). |

## Hallazgo bloqueante en 3.5 (carga por frame)

El borrador dice a la vez que (a) el primer frame lleva `maxAttValueBytes − 13` bytes de carga y los demás `− 9`, y (b) todas las cargas salvo la última tienen **igual longitud**. Ambas no pueden cumplirse: el primer frame siempre lleva 4 bytes menos. Con `maxAttValueBytes = 20` el primero lleva 7 y los demás 11.

Apple propone (A): **el primer frame puede llevar menos; el receptor exige solo que ningún frame intermedio supere la capacidad ni sea menor que el máximo permitido para su posición** (primero `max−13`, resto `max−9`, el último puede ser menor y nunca vacío). Es determinista y más rápido. Alternativa (B): carga uniforme `max−13` en todos (desperdicia 4 bytes por frame). Hay que elegir una; F02/F03 dependen de ello. Ejemplo con `totalLength = 24` y `max = 20`: (A) 7 + 11 + 6 = 3 frames; (B) 7 + 7 + 7 + 3 = 4 frames.

## Preguntas Q-A1 … Q-A10

| ID | Respuesta |
|---|---|
| Q-A1 | **Solo cifrado.** iOS no expone a una app el método de emparejamiento ni la fuerza del vínculo; no puede afirmar protección MITM. Declarar «cifrado de enlace» y apoyar la autenticación en la capa de aplicación (8.4). |
| Q-A2 | **Aceptado** el registro binario fijo de 16 bytes (cabe en una lectura incluso con MTU 23, sin parsear JSON antes de autenticar). |
| Q-A3 | **Sin intercalado** al inicio: mensajes de control ≤8 KiB y lotes divididos en mensajes. Evita la ventana de contadores. Se reevalúa tras medir latencia. |
| Q-A4 | **Sí**: 4 parciales × 64 KiB = 256 KiB acumulados por sesión. |
| Q-A5 | **Medido en iPhone físico, 0.3.3 (10):** CoreBluetooth informó 512 bytes con respuesta y 512 sin respuesta tras descubrir el servicio. Evidencia fechada en `evidence/apple-0.3.3/apple/physical-restoration-trace.json`. Son máximos de escritura informados por la API en ese enlace, no una medición de throughput, fragmentación real ni capacidad de notificación. CoreBluetooth no expone directamente al central la capacidad de notificación; comprobarla con el receptor cuando exista el perfil P2. No fijar estos números como constantes del contrato ni restar tres bytes otra vez. |
| Q-A6 | CryptoKit ofrece `Curve25519.KeyAgreement`, `ChaChaPoly`, `SHA256` y `HKDF`, **no** el patrón Noise. Apple acepta un componente propio mínimo **solo** si pasa los vectores publicados de Noise como oráculo y la revisión independiente. No se elige biblioteca aquí. |
| Q-A7 | Los elementos del Keychain de iOS suelen **conservarse** al desinstalar la app; por eso la app debe detectar una instalación nueva (marca local ausente + clave presente) y pedir al usuario conservar u olvidar la identidad. Comportamiento exacto: **verificar en el iPhone físico** antes de congelar. «Olvidar dispositivo» borra la clave del par, invalida sesiones y vacía outbox y tabla de resultados de ese par. |
| Q-A8 | Un nuevo vínculo LE **no** debe cambiar la identidad de aplicación. Quitar el accesorio en AccessorySetupKit equivale a «olvidar» y exige registro nuevo con SAS. |
| Q-A9 | **Sí, eliminar** DATA sin AEAD en producción. En laboratorio, solo tras un indicador de compilación y con clave de prueba rotulada «solo pruebas». |
| Q-A10 | **Confirmado** el nombre `WristBridge` y el filtro ASK actual: servicio `6F06B8B1-…36A01` más subcadena de nombre `WristBridge` (`NSAccessorySetupBluetoothServices`/`Names`; `ASDiscoveryDescriptor`). Respaldado por la observación del usuario (selector encuentra el reloj con ese nombre). Alternativa a evaluar: filtrar solo por servicio o datos de servicio, para no depender del nombre del adaptador. |

## Observaciones por sección

- **§1 CCCD:** de acuerdo en que no es señal de sesión. En iOS se espera `didUpdateNotificationStateFor` antes de enviar HELLO. Leer una característica `READ_ENCRYPTED` sin vínculo puede abrir un aviso de emparejamiento del sistema; el registro debe ocurrir con el accesorio ya autorizado y vinculado.
- **§3 JSON:** UTF-8 sin BOM, sin claves duplicadas y rango ±2^53−1: de acuerdo. `JSONSerialization` no rechaza claves duplicadas por sí mismo, así que Swift necesitará una validación explícita (vectores F01/F10).
- **§5 plazos:** ACK en 5 s es plausible para CoreBluetooth en primer plano; en segundo plano iOS puede diferir callbacks. Medir antes de congelar.
- **§6 `requestId`:** definir en el esquema formato único (propongo hex minúsculas de 32 caracteres).
- **§8.5 renegociación:** 2^31 mensajes o 24 h; con sin intercalado (Q-A3) desaparece la dependencia de `SetNonce`.
- **§8.6 Keychain:** `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` sin iCloud, de acuerdo; es lo que permite reconectar con el iPhone bloqueado.

## Vectores F01–F12: formato y primeros vectores a mano

Formato de manifiesto propuesto (JSON): `{ "id", "case", "schema", "bytesHex", "expected", "origin" }`, donde `origin` indica cómo se obtuvo el resultado esperado («a mano» o herramienta independiente), nunca el encoder bajo prueba. Primeros vectores del framing **sin cifrado**, calculados a mano con la cabecera de 9 bytes (3.2) y `totalLength` en big-endian. Todos `PROPUESTO`, **NOT_RUN**, a revisar por Windows:

| ID | Caso | Bytes (hex) | Esperado |
|---|---|---|---|
| F01-a | DATA, un frame, `messageId=1`, `totalLength=24` (mínimo), luego 24 bytes de cuerpo | `01 81 00 0001 0000 0001 00000018` + cuerpo | Mensaje completo aceptado (cabecera de 13 bytes) |
| F01-b | ACK del mensaje 1 | `01 82 00 0001 0000 0001` | ACK válido, sin cuerpo |
| F01-c | ERROR `E_SEQUENCE` (`0x0007`) relacionado con el mensaje 1 | `01 83 00 0000 0000 0001 0007 0001` | Error 0x0007, `relatedMessageId=1` |
| F01-d | CANCEL del mensaje 5 | `01 84 00 0005 0000 0001` | Cancelación idempotente |
| F10-a | Flags reservado distinto de 0 | `01 81 02 0001 0000 0001 00000018` | `E_MALFORMED_FRAME` |
| F10-b | `frameVersion = 02` | `02 81 00 0001 0000 0001 00000018` | `E_INCOMPATIBLE_FRAME`, cerrar sesión |
| F10-c | Tipo crítico desconocido `0x86` | `01 86 00 0001 0000 0001` | `E_UNSUPPORTED_TYPE`, cerrar sesión |
| F07-a | `totalLength` mayor que el límite del tipo, un frame | `01 81 00 0001 0000 0001 00010000` (65536) con límite de control 8192 | `E_OVERSIZE` antes de reservar memoria |
| F02-a | `maxAttValueBytes = 20`, `totalLength = 24`, regla (A) | cargas de 7, 11 y 6 bytes | 3 frames; ningún valor ATT > 20 |
| F02-b | `maxAttValueBytes = 13` | — | `E_CAPACITY_TOO_SMALL`, no abrir canal |

Los vectores con AEAD y los de handshake Noise necesitan la biblioteca elegida y los vectores oficiales de Noise; quedan fuera de esta respuesta.

## Lo que Apple NO hace todavía

Sin codec, framer, claves, Keychain de identidad, mensajes ni cambios de GATT. La app solo añade la medición de capacidad de solo lectura (arriba). Salud, contactos y comandos sensibles siguen desactivados.

## Qué decide Windows ahora

1. Elegir (A) o (B) para la carga por frame (3.5).
2. Confirmar Q-A3 (sin intercalado) y Q-A9 (sin DATA sin AEAD).
3. Revisar los vectores propuestos y fijar el formato del manifiesto en `contracts/`.
4. Pedir la revisión independiente de §8 y acordar la biblioteca Noise.
