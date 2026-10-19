# P2 seguro y sincronización de salud · lado Kotlin (18 de octubre de 2026)

Estado: **implementado en JVM puro y probado; NO conectado todavía al GATT del reloj ni a la UI.** El contrato P2 sigue marcado «candidato a congelar»: el usuario pidió dejarlo estable para este alcance, pero Apple debe confirmar y la revisión independiente (K3) sigue sin hacerse. No se transmite salud real hasta cerrar esas dos cosas.

## Qué existe (core/protocol-kotlin, paquete `dev.wristbridge.protocol.p2`)
| Pieza | Archivo | Pruebas |
|---|---|---|
| Noise_XX_25519_ChaChaPoly_SHA256 con primitivas JCA (X25519, ChaCha20-Poly1305, HMAC/HKDF-SHA256), código de seguridad de 6 dígitos | `secure/Noise.kt` | Vector RFC 7748, tamaños 32/96/64, acuerdo de claves e identidad, prólogo distinto, manipulación, repetición, AD incorrecto, fuera de turno |
| JSON estricto (UTF-8 sin BOM, profundidad 8, sin claves duplicadas, enteros ±2^53−1) | `secure/StrictJson.kt` | Rechazos |
| Sesión DATA autenticada: cuerpo `contador(u64) ‖ cifrado ‖ tag`, AD según §3.3, fragmentación con `FrameCodec` | `secure/SecureSession.kt` | Ida y vuelta con fragmentación, repetición y manipulación |
| Mensajes `health.sync`: `cursor` (pide lote tras un cursor), `batch`, `ack` (solo tras guardar), idempotencia por `sampleId` | `health/HealthSync.kt` | 450 muestras en lotes de 200, ack, capacidad desconocida |
| Reloj: lectura por cursor y ack sobre `HealthStore` (`HealthStoreSource`) | `apps/watch/.../health/HealthStore.kt` | Compila; sin prueba instrumentada nueva |
| Vector cruzado de Noise con claves fijas | `test/fixtures/shared/p2-noise-xx-vectors.json` (PROPOSED) | Estable en JVM |

## Esquema del mensaje (propuesto)
Sobre: `{kind: "req"|"res", requestId, capability: "health.sync", payload}`.
- `{op:"cursor", after, max≤200}` → `{op:"batch", after, last, more, samples:[{c,m,s,e,v,id}]}`; métricas `steps_daily_snapshot` (instantánea acumulada diaria, no delta) y `heart_rate_bpm`.
- `{op:"ack", through}` → `{op:"acked", through}`. El reloj no borra por ack (la pantalla local lo necesita); la retención de 7 días sigue.

## Para Apple
1. Portar `NoiseXX` y reproducir `p2-noise-xx-vectors.json` (claves y efímeras fijas). Si no coincide, ver primero el orden de tokens y el relleno de `h` (el nombre del protocolo mide exactamente 32 bytes).
2. Implementar el libro (H-A01) con `sampleId` como clave de idempotencia y ack tras guardar.
3. Confirmar o corregir §3.3 (`contador` explícito, estrictamente igual al esperado), ids de mensaje DATA desde 16, y el sobre JSON.

## Lo que falta antes de usarlo con datos reales
- Revisión independiente (K3) y, si se decide, sustituir esta implementación por una biblioteca revisada.
- Verificar en el reloj que Android 16 ofrece `XDH`/X25519 y `ChaCha20-Poly1305` (en JVM funciona; en dispositivo NOT_RUN).
- Exponer las tres características GATT, almacenar la clave estática con Keystore, pantalla de registro con el código de 6 dígitos, bucle de petición/ack con el iPhone (H-W07).
