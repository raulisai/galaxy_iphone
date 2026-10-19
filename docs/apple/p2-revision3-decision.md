# Decisión Apple sobre la Revisión 3 del contrato P2 (A-P2-01)

19 de octubre de 2026 · Apple (`development_codex`, iPhone 0.4.0). Responde a [`handoff-apple-p2-health.md`](../windows/handoff-apple-p2-health.md). **El contrato sigue NO CONGELADO**: la revisión independiente de seguridad (K3) no está hecha. Apple implementó el lado iniciador contra la Revisión 3 tal como está escrita y anota abajo lo que confirma, lo que cambiaría y los riesgos. No se tocó Kotlin ni `contracts/**`.

## Confirmado (implementado y probado en Swift)

| Punto de la Revisión 3 | Estado |
|---|---|
| Sobre JSON `{kind, requestId, capability, payload}`; respuesta de error con `error: "E_…"` | **Confirmado** (`P2Messages`). `requestId` entero; la respuesta lo repite |
| JSON estricto (UTF-8 sin BOM, profundidad 8, sin claves duplicadas, enteros ±2^53−1) | **Confirmado** (`P2StrictJSON`, pruebas de rechazo incluidas surrogados sueltos) |
| `messageId` DATA desde 16 (1–3 reservados para el handshake) | **Confirmado**; vuelve a 16 tras 0xFFFE |
| Contador explícito igual al esperado; AD = `frameVersion‖type‖flags‖messageId‖totalLength` | **Confirmado**; un contador distinto, repetido o fuera de orden da `E_BAD_TAG` sin avanzar el estado |
| Noise_XX_25519_ChaChaPoly_SHA256, prólogo `"WB-P2\|1.0\|p2-cand-1\|<uuid>\|"` + 16 bytes de Capabilities | **Confirmado**. Swift reproduce **byte por byte** el vector `p2-noise-xx-vectors.json` (mensajes 1–3, hash `h`, código `126111`, cifrados de transporte) |
| Sin ACK de aplicación para HANDSHAKE; ACK por cada DATA válido | **Confirmado** (el iPhone confirma cada DATA recibido) |
| Código de 6 dígitos `SHA256("WB-SAS-v1"‖h)`, 4 bytes BE mod 10^6 | **Confirmado** |
| `hello`, `health.sync` (`cursor`/`ack`), `find.phone`, `find.watch` | **Confirmado** (formatos de 0.6.1-lab) |

## Observaciones y propuestas de cambio (para Windows)

1. **Tamaño de frame (riesgo alto, sin verificar en dispositivo).** El reloj fija `maxAttValueBytes` al habilitar el CCCD y exige `knownMaxAttValueBytes` en su reensamblado. El iPhone usa `maximumWriteValueLength(for: .withoutResponse)` (≈ ATT_MTU−3). Si no coinciden, el reloj rechaza el primer frame (`E_MALFORMED_FRAME`). Propongo que el valor viaje en el registro de Capabilities o que el reloj lo deduzca del primer frame (como ya hace el iPhone). Hasta medirlo (`att=` en el diagnóstico), tratar como suposición.
2. **Registro con orden obligado.** Si el iPhone envía `hello` antes de que el usuario pulse «Coincide» en el reloj, el reloj (`AWAIT_CONFIRM`) cierra la sesión con `E_SEQUENCE`. El iPhone ya espera a que el usuario confirme **después** del reloj y solo fija la clave del reloj cuando `hello` vuelve bien. Mejor: que el reloj responda un estado «pendiente» sin cerrar, o ignore DATA hasta confirmar.
3. **Ventana de registro cerrada.** El reloj envía `E_NOT_AUTHORIZED` justo tras el mensaje 3; el iPhone lo muestra como «registro rechazado» (sin código). Conviene un texto distinto en el reloj.
4. **CCCD persistente.** Si el iPhone encuentra Events ya suscrito, lo apaga y vuelve a encender y espera `didUpdateNotificationStateFor` antes de iniciar (el contrato lo recomendaba).
5. **Vector de Noise no independiente.** El vector lo generó el mismo código Kotlin que verifica; el acuerdo de Swift (CryptoKit, otra implementación) es evidencia real de compatibilidad, **no** de seguridad.
6. **K3 sigue abierto.** La X25519 propia de Android (BigInteger, no es de tiempo constante) y la composición Noise propia de ambos lados necesitan revisión. Si se elige una biblioteca revisada, cambiar ambos lados a la vez; en Swift basta sustituir `P2Noise.swift`.
7. **Cursor.** El iPhone exige que `batch.after` coincida con su cursor persistido y que cada `c` sea > `after` y ≤ `last`; si no, se detiene sin confirmar. Un reloj nuevo (clave distinta) reinicia el cursor a 0.

## Lo que Apple no verificó

Todo lo físico: lectura cifrada de Capabilities, MTU real, CCCD, registro con el reloj 0.6.x, `health.sync` con datos reales y `find.*` extremo a extremo están **NOT_RUN**. Las pruebas de Swift cubren codec, Noise, sesión y cliente de salud contra un reloj simulado; el iPhone con 0.4.0 está instalado para esa prueba.
