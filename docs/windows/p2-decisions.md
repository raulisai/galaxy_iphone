# Decisiones Windows sobre el borrador P2 (Revisión 2) · K1, K2, K3

18 de octubre de 2026 · Windows/Kotlin (editor del contrato) · responde a [`docs/apple/p2-draft-response.md`](../apple/p2-draft-response.md) y a las tareas K1–K3 de [`docs/apple/handoff-to-windows.md`](../apple/handoff-to-windows.md). Edita [`contracts/proposals/p2-channel-draft.md`](../../contracts/proposals/p2-channel-draft.md) (Revisión 2).

**Alcance y estado.** Son decisiones sobre el **borrador**, no un congelado. Solo tocan el **framing sin cifrado**: no hay claves, ni Noise, ni cambios de GATT, ni características BLE expuestas, ni datos sensibles. El estado del contrato sigue siendo **NO CONGELADO** y el estado conjunto de F01–F12 sigue **NOT_RUN** (no hay ejecución cruzada con Swift). Los comandos sensibles y la salud siguen desactivados.

## 1. Resumen

| Tarea | Decisión / estado |
|---|---|
| K1 · carga por frame (3.5) | **Opción (A).** Contradicción resuelta reescribiendo la regla de longitud. |
| Q-A3 · intercalado | **Cerrada: sin intercalado en v1.** |
| Q-A9 · DATA sin AEAD | **Cerrada: prohibido en producción;** solo con indicador de compilación de laboratorio y clave rotulada «solo pruebas». |
| K2 · vectores de Apple | **10 de 10 CONFIRMADOS, 0 corregidos**, con 4 precisiones (sección 5). Manifiesto `draft-2` publicado. |
| K3 · revisión de §8 y biblioteca Noise | **ABIERTA.** La revisión independiente **no está hecha** (necesita una persona o tercero) y la biblioteca **no está elegida**. |
| K4–K8 | **BLOQUEADAS** hasta cerrar K3 (sección 7). |

## 2. K1 · carga por frame: opción (A)

**Problema (hallazgo de Apple, confirmado).** El borrador decía a la vez que el primer frame lleva `max−13` bytes de carga y los demás `max−9`, y que todas las cargas salvo la última tienen «igual longitud». Con `max = 20` son 7 y 11: no pueden ser iguales.

**Decisión.** Se adopta (A) con una redacción exacta:

- El emisor llena cada frame hasta la capacidad de su posición: primero `min(L, max−13)`, siguientes `min(restante, max−9)`.
- El receptor exige que **todo frame no final sea exactamente completo para su posición** (primero `max−13`, resto `max−9`). El frame final lleva el resto, `1..max−9`, **nunca vacío**. Cualquier desviación de longitud → `E_MALFORMED_FRAME`; suma ≠ `totalLength` → `E_LENGTH_MISMATCH`.
- `fragCount` queda determinado por `totalLength` y la carga del primer frame; si no coincide → `E_LENGTH_MISMATCH` en el primer frame, antes de reservar memoria.

**Razones.** (1) Es determinista: dado `L` y `max` solo existe una fragmentación válida, así que F02/F03 se comprueban sin ambigüedad y no hay dos implementaciones «compatibles» que difieran. (2) No desperdicia 4 bytes por frame: con `max = 20` y `L = 24`, (A) usa 3 frames (7+11+6) y (B) usa 4 (7+7+7+3); a capacidad mínima es un 33 % más de escrituras ATT con respuesta. (3) Es lo que Apple propuso.

**Coste conocido.** La capacidad del emisor no viaja en la cabecera, así que el receptor la **deduce del primer frame** de un mensaje de varios frames (`max = carga + 13`) y exige a los siguientes `carga + 4`. Un primer frame corto no se puede distinguir de una capacidad menor, salvo que el receptor conozca la capacidad por otra vía (el codec admite ese valor conocido y entonces exige primer frame exactamente lleno; vectores F03-m y F03-n). Esto no afecta a la seguridad: un mensaje truncado o con huecos nunca se entrega (suma ≠ `totalLength`), y la integridad real la dará el AEAD cuando exista.

**Efectos secundarios documentados en el borrador:** orden de validación determinista (3.5); límites por tipo antes de reservar, con el flag `PRIORITY` como único discriminador de control frente a lote antes de descifrar (interpretación de Windows, **a confirmar por Apple**); mínimos `totalLength ≥ 24` (DATA) y `≥ 1` (HANDSHAKE); formas exactas de ACK/CANCEL (9 bytes) y ERROR (13 bytes, `messageId` de cabecera 0).

## 3. Q-A3 · sin intercalado en v1

**Decisión.** Un solo mensaje DATA/HANDSHAKE abierto por dirección, frames contiguos. Control ≤ 8 KiB; los lotes de salud se dividen en mensajes de ≤ 8 KiB de texto plano. `maxPartialMessages = 1` en v1. Un primer frame ajeno con uno abierto → `E_TOO_MANY_PARTIALS` y el abierto se conserva. La ventana de 64 contadores y `SetNonce` quedan como **opción futura** (texto conservado), no v1.

**Razones.** Quita la dependencia de `SetNonce` (que ninguna biblioteca Noise garantiza) y la ventana anti-replay, que son justo la superficie que K3 aún no ha revisado. El coste es latencia de control de, como mucho, un mensaje de 8 KiB; se reevalúa tras medir.

**Precisiones.** (a) «Un mensaje en vuelo por dirección» se refiere al **reensamblado**; la ventana de envío `maxInflightMessages = 8` (mensajes pendientes de ACK) se conserva. (b) ACK, ERROR y CANCEL son de un solo frame y **sí** pueden aparecer entre los frames de un mensaje abierto: un ACK del otro sentido comparte la tubería con los DATA de ese sentido y no puede esperar. (c) Un ACK de un mensaje que no está en vuelo se ignora y se cuenta (no es error ni cierra la sesión): los ACK duplicados o tardíos son normales tras retransmisiones. (d) En 8.5 el contador AEAD es estrictamente creciente, sin ventana.

## 4. Q-A9 · DATA sin AEAD

**Decisión.** Ningún DATA viaja sin AEAD en producción. Solo existe en un build de laboratorio tras un indicador de compilación, y cualquier prueba con cifrado usa una sesión con clave fija rotulada «solo pruebas» (test-only) que no debe compilarse en producción. Los vectores de framing de este documento son independientes del cifrado y no necesitan ninguno de los dos.

## 5. K2 · verificación a mano de los diez vectores de Apple

Método: cada hex se descompone campo a campo según 3.2 (`frameVersion` u8 · `type` u8 · `flags` u8 · `messageId` u16 · `fragIndex` u16 · `fragCount` u16, 9 bytes, big-endian; y, solo en `fragIndex = 0` de DATA/HANDSHAKE, `totalLength` u32 → 13 bytes) y se compara con el resultado esperado y con el código de §7.1. Los vectores tal como los envió Apple están en `docs/apple/p2-draft-response.md`.

| ID | Descomposición | Resultado | Veredicto |
|---|---|---|---|
| F01-a | `01` ver · `81` DATA · `00` flags · `0001` msg 1 · `0000` idx 0 · `0001` count 1 · `00000018` total 24 (0x18) · 24 B de cuerpo = 9 + 4 + 24 = 37 B | Aceptado, cabecera de 13 B | **CONFIRMADO.** Precisión: Apple no fijó el cuerpo; el manifiesto fija `00 01 … 17`. Un único frame de 37 B exige `max ≥ 37` (carga `max−13 ≥ 24`); con menos capacidad el mismo mensaje se fragmenta. |
| F01-b | `01 82 00 0001 0000 0001` = 9 B | ACK de msg 1, sin cuerpo | **CONFIRMADO** (9 B exactos, 3.2) |
| F01-c | `01 83 00` · `0000` msg 0 · `0000` · `0001` · `0007` code · `0001` related = 9 + 4 = 13 B | `E_SEQUENCE` (0x0007, tabla 7.1 ✓), relacionado con 1 | **CONFIRMADO** (`messageId` de cabecera 0 ✓; cuerpo `u16 u16` ✓) |
| F01-d | `01 84 00 0005 0000 0001` = 9 B | CANCEL del msg 5 | **CONFIRMADO** |
| F10-a | `01 81 02 …`: byte 2 = 0x02, bit 1 reservado | `E_MALFORMED_FRAME` | **CONFIRMADO.** Precisión: el vector no trae carga; el error es el de flags porque la validación de cabecera precede a la de carga vacía (orden fijado en 3.5). |
| F10-b | `02 81 00 …`: byte 0 = 0x02 | `E_INCOMPATIBLE_FRAME`, cerrar sesión (0x0002 ✓) | **CONFIRMADO.** Misma precisión de orden (versión primero; vale incluso con 3 bytes, vector F10-j). |
| F10-c | `01 86 00 0001 0000 0001`: tipo 0x86, bit 7 = crítico, no definido (0x81–0x85) | `E_UNSUPPORTED_TYPE`, cerrar sesión (0x0004 ✓) | **CONFIRMADO** |
| F07-a | `01 81 00 0001 0000 0001 00010000`: total = 0x00010000 = 65536 | `E_OVERSIZE` antes de reservar | **CONFIRMADO.** Precisión: depende del límite 8192 indicado por Apple, que ha de pasarse como contexto (`limitsUniform`); con los límites por defecto (lote 64 KiB + 24 = 65560) 65536 **no** excedería, porque el tipo lógico va cifrado y antes de descifrar solo distinguen `type` y `PRIORITY` (3.5). El `totalLength` incluye contador y tag (+24). |
| F02-a | `max = 20`: primer frame `20−13 = 7`, siguientes `20−9 = 11`; `24−7 = 17`; `17−11 = 6` ≤ 11 → final | Cargas 7 / 11 / 6; 3 frames; ningún ATT > 20 | **CONFIRMADO.** Precisión: 7/11/6 son **cargas**; los frames miden 13+7 = 20, 9+11 = 20 y 9+6 = 15 bytes. |
| F02-b | `max = 13 < 14 = 13 + 1` | `E_CAPACITY_TOO_SMALL` (0x000C ✓), canal no se abre | **CONFIRMADO** |

**Resumen K2: 10 CONFIRMADOS, 0 CORREGIDOS.** No se encontró ningún error de hex ni de derivación en los vectores de Apple. Las cuatro precisiones (cuerpo de F01-a, orden de validación de F10-a/b, contexto de límites de F07-a, cargas frente a tamaños de frame de F02-a) están incorporadas al manifiesto o a 3.5. El error real estaba en el **borrador** (la regla de «igual longitud»), no en los vectores, y también se corrige (K1). Además se cambió en 7.1 la fila de `E_LENGTH_MISMATCH`: la carga intermedia de longitud distinta pasa a `E_MALFORMED_FRAME`.

### Vectores nuevos de Windows

El manifiesto [`test/fixtures/shared/p2-framing-vectors.json`](../../test/fixtures/shared/p2-framing-vectors.json) (`wristbridge.p2-framing-vectors`, `draft-2`, 68 vectores, todos `"status": "PROPUESTO"`) contiene los diez de Apple (`origin` «a mano (Apple)») y 58 nuevos («a mano (Windows, revisado)»): ACK/CANCEL/ERROR mal formados, HANDSHAKE y PRIORITY de un frame, capacidades 14/20/185/512, fronteras N−1/N/N+1, **último frame exactamente lleno** (F03-d), **último frame de 1 byte** (F03-c, F03-e), **frame intermedio corto o largo** (F03-f, F03-g → `E_MALFORMED_FRAME`), último frame corto o largo, `fragCount` incoherente, **`fragIndex` fuera de orden** (F04-a, F04-f), **`fragCount = 0`** (F10-g), **frame duplicado** (F04-b, F04-c), conflictos F05, límite de parciales F06, oversize F07, cabeceras truncadas F10, parcial caducado F11 y CANCEL/ACK entre frames F12, y **ACK de un mensaje desconocido** (F01-k).

Formato: arreglo de `{ id, case, schema, bytesHex | framesHex, context?, expected, origin, status }` dentro de `{ schema, version, vectors }`; los multi-frame usan `framesHex` (arreglo de valores ATT) más la longitud/contenido del cuerpo reensamblado. `schema` ∈ `p2.frame` (un valor ATT), `p2.stream` (secuencia de frames con `expected.steps`), `p2.encode` (entrada del codificador que debe rechazarse). Si un `p2.stream` trae `context.encode`, el codificador debe reproducir `framesHex` byte a byte.

**Origen y honestidad.** Ningún hex salió del codec Kotlin bajo prueba. Las longitudes de carga de cada frame se eligieron a mano (columna «cargas» del razonamiento de arriba) y un guion Python independiente, escrito solo a partir de la tabla 3.2 (no del código Kotlin), los ensambló en hex y comprobó con `assert` que las sumas cuadran; otro guion independiente, un puerto aparte de las reglas, confirmó que cada `expected` es coherente con las reglas escritas y que el codificador de referencia reproduce los 12 `framesHex` con `encode`. Eso **no** convierte estos vectores en evidencia: siguen siendo PROPUESTO hasta que Apple los revise y su codec Swift los reproduzca. Las pruebas Kotlin `P2F00…P2F12` solo afirman PASS por contraste con estos vectores; no se han ejecutado en este entorno (sin Gradle).

## 6. K3 · revisión independiente de §8 y biblioteca Noise (ABIERTA)

**Revisión independiente de §8: NO realizada.** Requiere una persona o tercero con criterio criptográfico ajeno al autor del diseño; un agente que ha escrito o leído el diseño no la sustituye. Hasta que exista por escrito, §8 sigue «candidato sin revisar» y no puede habilitar datos sensibles, salud ni comandos con efectos.

**Elección de biblioteca: ABIERTA.** Evaluación de opciones para Android `minSdk 33` con lo que se puede afirmar sin red:

| Pieza | Estado |
|---|---|
| X25519 en JCA | Se indica que `KeyAgreement` con `"XDH"`/`"X25519"` está disponible en Android 13+. **No verificado en este equipo** (sin dispositivo ni documentación local); hay que confirmarlo con una prueba instrumentada en el Galaxy Watch7 real, no solo en emulador. |
| ChaCha20-Poly1305 en JCA | Se indica `Cipher.getInstance("ChaCha20-Poly1305")` con nonce de 12 bytes. **No verificado en el reloj.** Precaución general de la API de JCA: algunos proveedores prohíben reutilizar clave+nonce al cifrar con una misma instancia; Noise crea el cifrador por mensaje, pero hay que probarlo. |
| X25519 en Android Keystore | **No verificado.** No se asume. Mientras no se compruebe, §8.6 se mantiene: clave estática X25519 como bytes cifrados con una clave AES-256-GCM no exportable del Keystore. |
| SHA-256 / HMAC / HKDF | SHA-256 y HMAC-SHA256 existen en JCA; HKDF de Noise (dos o tres salidas con HMAC) es una función corta que se puede escribir y comprobar con vectores. |
| Codificación de claves XDH | La conversión entre `PublicKey`/`PrivateKey` de JCA y los 32 bytes crudos de Noise (little-endian, `XECPublicKeySpec` con `BigInteger`) es una fuente típica de errores: debe comprobarse con los vectores de RFC 7748 y con los de Noise. |
| JVM de pruebas | El JDK 17/21 del proyecto también ofrece XDH y ChaCha20-Poly1305, lo que permite ejecutar los vectores en JVM; pero eso **no** prueba el comportamiento del proveedor de Android. |

Opciones, con su compromiso:

| Opción | Ventaja | Contra |
|---|---|---|
| A. Biblioteca Noise de terceros (p. ej. una implementación Java existente de `Noise_XX`/`IK`) | Menos código propio | Estado de mantenimiento y de revisión **no verificable offline**; suele traer criptografía propia en vez de la del sistema; añade dependencia |
| B. Primitivas por biblioteca (p. ej. Bouncy Castle) más máquina de estados propia | Primitivas con auditoría | Dependencia pesada en un reloj; Android incluye una versión recortada del proveedor; sigue haciendo falta la máquina de estados propia |
| **C. Componente Noise mínimo propio sobre las primitivas del sistema (JCA)** | Superficie pequeña (solo XX e IK, sin `SetNonce`, sin `Rekey`, sin PSK), sin dependencias nuevas, alineado con Q-A6 de Apple | Es criptografía propia: **solo es aceptable** con vectores como oráculo y revisión independiente |

**Camino recomendado (C), por orden:**

1. Fijar los vectores de prueba publicados de Noise como oráculo (los de la comunidad Noise para `Noise_XX_25519_ChaChaPoly_SHA256` e `Noise_IK_25519_ChaChaPoly_SHA256`) más los de RFC 7748 (X25519) y RFC 8439 (ChaCha20-Poly1305); registrar su origen y hash en `test/fixtures/` **antes** de escribir código, igual que los vectores a mano de framing. Confirmar origen y licencia al adoptarlos (no verificado aquí).
2. Escribir solo `SymmetricState`/`CipherState`/`HandshakeState` para XX e IK. Sin diseño propio: seguir la especificación de Noise al pie de la letra.
3. Pruebas de la implementación únicamente contra los vectores, más negativas (mensaje alterado, nonce repetido, clave equivocada, prólogo distinto).
4. Prueba instrumentada en el Galaxy Watch7: XDH y ChaCha20-Poly1305 disponibles y con los mismos resultados que en JVM; si falla, volver a la opción A o B antes de seguir.
5. **Revisión independiente por escrito** de §8 y del componente (aprobar, cambiar o rechazar), con Apple como contraparte. Sin ella: ningún dato de salud ni comando sensible.
6. Sólo entonces derivar los vectores con AEAD y de handshake en el manifiesto y empezar el codec cifrado.

## 7. K4–K8: bloqueadas hasta cerrar K3; qué desbloquea cada una

«K3 cerrada» significa: biblioteca/componente elegido y probado con vectores oficiales, prueba instrumentada en el reloj OK y revisión independiente de §8 aprobada por escrito.

| # | Tarea | Qué la desbloquea exactamente |
|---|---|---|
| K4 | Exponer Capabilities / Requests / Events y el registro de 16 bytes | K3 cerrada; aprobación escrita de Apple de §1 y §2 (incl. Q-A1 y la medición de capacidad de notificación, Q-A5, aún solo medida en escritura); gate P2/G2 abierto; contrato materializado (`framing.md`, `capabilities.json`, `compatibility.md`); `profileRevision` nueva y actualización de `contracts/ble-profile.json` y de las pruebas de coherencia |
| K5 | Framer/codec cifrado, identidad en Keystore, registro con SAS | K3 cerrada; Apple aprueba §8.3–§8.6 y confirma Q-A7/Q-A8; verificar si Keystore admite X25519 (si no, §8.6 con envoltura AES-GCM); vectores de handshake y AEAD derivados e intercambiados con Swift; K4 hecha. El framing sin cifrado de esta revisión ya está listo para reutilizarse |
| K6 | Salud: Health Services, outbox de 7 días, cursor por métrica | K5 completa y la sesión autenticada probada; esquema de mensajes de salud (`control.schema.json`) aprobado por ambos; tabla de resultados persistente (P2-06, Q07–Q09); permisos por métrica en el reloj; gate P7 y G2 abiertos |
| K7 | Buscar iPhone (orden por el canal, resultado `solicitado`) | K5 completa; capacidad `findPhone` con `idempotency` definida en el esquema de control; comprobación de que el comando no filtra datos del usuario; G2 abierto (comando con efecto, 8.7); Apple acepta la orden en `FindPhoneAlarm` |
| K8 | Buscar reloj (iPhone → reloj) | K5 completa; capacidad `findWatch` definida y aprobada; Apple implementa el envío; G2 abierto |

Todo lo anterior mantiene ANCS/AMS independiente: un error del canal propio nunca desconecta el enlace.

## 8. Qué debe hacer el coordinador

- Copiar los ficheros de `s2/` a las mismas rutas del repositorio (ver informe) y ejecutar `./gradlew :core:protocol-kotlin:test`. **Nada de este lote se ha compilado ni ejecutado.**
- `contracts/proposals/p2-channel-draft.md` es el único fichero existente que se edita; usa LF.
- Si Apple discrepa de la interpretación de límites por `PRIORITY` (3.5) hay que cambiar `ReassemblyLimits.limitFor` y los vectores F07-f/F07-h, no la regla K1.
