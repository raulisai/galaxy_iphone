# Propuesta P2 del canal propio · borrador Windows

> **NO CONGELADO · requiere revisión Apple · no implementar hasta aprobar.**
> Esto es una propuesta de ingeniería del editor del contrato (Windows/Kotlin). No es el contrato `p1-lab-1` ni lo modifica. Ninguna construcción criptográfica de este documento está revisada: es un diseño candidato que Apple y una revisión independiente deben aceptar, cambiar o rechazar.

> **Revisión 2 · decisiones K1/K2/K9 (18 de octubre de 2026, Windows).** K1: carga por frame opción (A), §3.5 reescrita (se elimina la contradicción de «igual longitud»). K2: los diez vectores a mano de Apple verificados uno a uno (10 confirmados, 0 corregidos, con 4 precisiones) y manifiesto `test/fixtures/shared/p2-framing-vectors.json` (draft-2, PROPUESTO). K9 = Q-A9: DATA sin AEAD prohibido en producción. Q-A3 cerrada: **sin intercalado en v1** (§3.6, §5, §8.5). Detalle y razones en `docs/windows/p2-decisions.md`. K3 (revisión independiente de §8 y biblioteca Noise) sigue **ABIERTA**; esta revisión no toca nada criptográfico ni el GATT. Estado: **NO CONGELADO**.

Fecha: 18 de octubre de 2026. Responde a [docs/apple/p2-contract-review.md](../../docs/apple/p2-contract-review.md) (base conjunta `0abcd51`, perfil `p1-lab-1`). Referencias de origen: `Doc/01_CONTRATO_COMPARTIDO.md` §2, §4–§7; `Doc/02_WINDOWS_KOTLIN.md` P2-04/P2-05; `Doc/04_MATRIZ_60_TAREAS.md` (P2-04, P2-05, P2-06, F01–F12, Q01–Q12); plan original §15.2 (significado de F01–F12) y §15 de seguridad (Q01–Q10).

## 0. Estado, alcance y reglas de lectura

| Tema | Estado |
|---|---|
| Evidencia física que respalda esta propuesta | Ninguna sobre el canal propio. Solo hay evidencia de ANCS/AMS y del servicio primario (ver ADR-001). Todo lo de este documento es NOT_RUN. |
| Perfil expuesto hoy por el reloj | Solo el servicio primario `…36a01`; las tres características reservadas **no** están en el GATT (`contracts/ble-profile.json`, `p1Exposure`). |
| Codec, framer, claves, Keystore/Keychain | Existe un codec/reensamblador Kotlin **de borrador, solo framing y sin cifrado** (`core/protocol-kotlin/.../p2/`, puro JVM, no expuesto por BLE) para probar los vectores a mano. Claves, Noise, Keystore/Keychain y GATT: no existen y no se crean hasta que Apple apruebe y se cierre K3. |
| Alcance | Canal de configuración y datos propios iPhone ↔ reloj. |
| Fuera de alcance | ANCS/AMS (siguen su formato oficial y no usan este envelope, `Doc/01` §3); efectos de salud y contactos (P7); IA, voz y audio continuo (P4/P5); agente Mac (P6); cualquier comando sensible. **Los comandos sensibles y los efectos sobre salud/contactos permanecen desactivados hasta G2** y hasta que la revisión de seguridad (sección 8) esté aprobada. |

Convenciones: enteros multibyte en **big-endian** (orden de red); «reloj» es el periférico/servidor GATT del camino A; «iPhone» es el central. Los números marcados *(propuesta)* son valores iniciales que deben ajustarse con mediciones físicas y se centralizarán en un único documento de límites (`Doc/01` §4). Para cada área se marca **Recomendado**, alternativas y compromisos.

Hallazgos de campo que condicionan el diseño (todos en `docs/windows/SEED.md` y `release-0.2.0-lab.md`):

1. Cerrar y reabrir un cliente GATT no corta el enlace físico, e iOS conserva el CCCD activo del par vinculado. Hay que tratar el estado de suscripción como potencialmente persistente (4.2).
2. Service Changed puede llegar durante la negociación de MTU; la capacidad efectiva cambia justo al inicio de la vida del enlace (6.4).
3. El enlace GATT es compartido con ANCS/AMS: un error del canal propio **nunca** puede desconectar el enlace ni degradar ANCS (`Doc/00`, «ANCS sigue independiente»).

## 1. GATT: propiedades, permisos y dirección

Dirección lógica: dos tuberías unidireccionales sobre el mismo servicio. Es la forma más simple de no improvisar el mapeo `request` ↔ característica (`Doc/01` §2).

| Característica | UUID | Dirección | Propiedades | Permisos | Contenido |
|---|---|---|---|---|---|
| Capabilities | `…36a02` | reloj → iPhone (lectura) | **READ** | READ_ENCRYPTED (requiere enlace cifrado/vinculado) | Registro de capacidades de 16 bytes (sección 2.1). Sin datos del usuario. |
| Requests | `…36a03` | iPhone → reloj | **WRITE** (con respuesta) | WRITE_ENCRYPTED | Frames (sección 3) enviados por el iPhone. |
| Events | `…36a04` | reloj → iPhone | **NOTIFY** | Descriptor CCCD (0x2902) con READ y WRITE cifrados | Frames enviados por el reloj. |

Nota sobre los nombres: «Requests» y «Events» nombran la **tubería**, no el `kind` lógico. Por Requests viajan todos los frames iPhone→reloj (incluidos `result` a una petición iniciada por el reloj); por Events viajan todos los reloj→iPhone (incluidos `result` a peticiones del iPhone). El `kind` está dentro del mensaje.

Decisiones y alternativas:

| Punto | Recomendado | Alternativa | Compromiso |
|---|---|---|---|
| Escritura de Requests | **Write con respuesta**. Control ≤8 KiB; una escritura en vuelo por vez; el callback de CoreBluetooth/Android da la confirmación GATT (la primera de las tres confirmaciones, `Doc/01` §5). | Write sin respuesta + `canSendWriteWithoutResponse` / `peripheralIsReady` para lotes. | Mayor rendimiento pero la entrega solo se sabe por el ACK de aplicación; más sutil la presión de escritura. Se puede ofrecer más tarde como bit de capacidad `bulkWriteNoResponse` (2.1) sin cambiar el framing. No usarla en P2 inicial. |
| Events | **Notify** + ACK de aplicación por mensaje (sección 5). | Indicate (confirmación de la pila por cada PDU). | Indicate da confirmación de enlace pero una ida y vuelta por frame: el rendimiento cae con lotes de 64 KiB. Notify con ventana de aplicación logra lo mismo con menos latencia. |
| Capabilities | **Read** estático, 16 bytes, cabe en un solo `ATT_READ_RSP` incluso con MTU 23. | Notify de cambios. | No hace falta: un cambio de capacidades implica Service Changed o nueva sesión. |
| Permisos | Exigir cifrado (vínculo LE). **No** declarar MITM-protegido en el perfil hasta medir qué método de emparejamiento usan realmente Watch7 + iPhone; no afirmar protección MITM del enlace. La autenticación fuerte la da la capa de aplicación (sección 8), no el vínculo. | Pedir `*_ENCRYPTED_MITM`. | Puede impedir el emparejamiento si el par solo ofrece Just Works; depende de una medición pendiente. **Pregunta abierta Q-A1.** |

CCCD y permisos revocados:

- El reloj no encola notificaciones para un cliente sin CCCD habilitado; si `notify` falla por falta de suscripción, el mensaje queda en cola sujeto a expiración, no se descarta como éxito.
- Si el iPhone deshabilita el CCCD, el reloj cierra la sesión de aplicación (descarta parciales, cola de salida no reconocida y claves de sesión) sin tocar el enlace.
- Si se pierde el vínculo o se retira el cifrado, lo mismo, más: el estado «canal propio listo» se apaga. «Listo» del canal propio exige HELLO completado (sección 4), no solo CCCD.
- Dado que el CCCD puede persistir entre conexiones para un par vinculado, **el CCCD no cuenta como señal de sesión**. El iPhone debe esperar `didUpdateNotificationStateFor` tras `setNotifyValue(true)` antes de enviar HELLO; ante duda de resincronización puede deshabilitar y rehabilitar, igual que ya se hace con Notification Source de ANCS (evidencia en 0.2.1-lab).
- Un `READ` de Capabilities o `WRITE` sin cifrado recibe el error ATT de autenticación/cifrado insuficiente; no se devuelve información parcial.

## 2. Compatibilidad, versionado y negociación

Versiones separadas (no mezclar):

| Versión | Dónde | Regla |
|---|---|---|
| `frameVersion` (u8) | cabecera de frame | Cambia el layout de bytes. Distinto → no interpretar; error `E_INCOMPATIBLE_FRAME`. |
| `protocolMajor` / `protocolMinor` (u8) | registro de capacidades y HELLO | Major distinto → incompatible, visible. Minor solo añade extensiones opcionales documentadas (`Doc/01` §4). |
| `profileRevision` | `contracts/ble-profile.json` | Identifica el perfil GATT (hoy `p1-lab-1`; la propuesta pasaría a `p2-…` al congelarse). No viaja por el aire. |

### 2.1 Registro de capacidades (característica Capabilities, 16 bytes)

| Offset | Tamaño | Campo | Significado |
|---|---|---|---|
| 0 | 1 | `recordVersion` | `1`. Otro valor → incompatible. |
| 1 | 1 | `protocolMajor` | `1` en P2. |
| 2 | 1 | `protocolMinor` | `0` inicial. |
| 3 | 1 | `frameVersion` | `1`. |
| 4 | 2 | `maxControlMessageBytes` | Texto plano máximo de un mensaje de control (propuesta 8192). |
| 6 | 2 | `maxBatchMessageBytes` | Texto plano máximo de un lote en unidades de 1 KiB (propuesta 64 = 64 KiB). |
| 8 | 1 | `maxPartialMessages` | **1 en v1** (sin intercalado, Q-A3); el campo se conserva para un futuro intercalado. |
| 9 | 1 | `maxInflightMessages` | Ventana de mensajes enviados y pendientes de ACK (propuesta 8); no es el número de mensajes parciales en reensamblado. |
| 10 | 4 | `featureBits` | Bits 0–15 opcionales (ignorar los desconocidos); bits 16–31 **críticos** (un bit crítico desconocido → incompatible). Bit 0: sesión AEAD (obligatorio para efectos). Bit 1 reservado: `bulkWriteNoResponse`. |
| 14 | 2 | `reserved` | Cero al enviar; ignorado al leer. |

Justificación: un registro binario de longitud fija no obliga a parsear JSON antes de saber si ambos lados son compatibles; cabe en 16 bytes (≤ ATT_MTU−1 incluso con MTU 23); no contiene identidad ni datos personales (`Doc/01` §2: el anuncio y el perfil no llevan datos del usuario). **Alternativa:** JSON UTF-8 pequeño (más extensible, pero exige parser acotado antes de autenticar y puede exceder una lectura con MTU 23). El registro **no está autenticado** (se lee antes de la sesión), por eso se repite dentro del handshake autenticado (sección 8.3) para detectar degradaciones forzadas.

### 2.2 Negociación

1. El iPhone lee Capabilities tras vincular y descubrir. Si `recordVersion`, `frameVersion` o `protocolMajor` no son soportados, **no** envía HELLO y muestra «versión incompatible» con ambas versiones; sin interpretar datos desconocidos como éxito.
2. Si el iPhone soporta la versión, la sesión usa `min(protocolMinor)` de ambos; las extensiones solo se usan si el bit está presente en los dos lados.
3. Si un par envía HELLO con major distinto (por ejemplo, caché obsoleta), el receptor responde ERROR `E_INCOMPATIBLE_MAJOR` y cierra la sesión de aplicación. No cierra el enlace.
4. Campos JSON desconocidos dentro de un mensaje válido de la misma major se ignoran (compatibilidad minor); un `kind` o `capability` desconocido nunca produce éxito: devuelve `result` con error `E_UNKNOWN_CAPABILITY` (sección 7).
5. Ausencia de una capacidad no equivale a permiso (`Doc/01` §4, `capability`). Las capacidades con efecto se habilitan por mensaje autenticado tras la sesión, no por bits no autenticados de la característica.

## 3. Mensajes, framing y fragmentación

### 3.1 Elección

`Doc/01` §4 fija el formato lógico inicial como JSON UTF-8 con esquema versionado. Se conserva: el contenido semántico de un mensaje es JSON; el **framing** de transporte es binario y compacto. Se propone el esquema siguiente, adaptado del candidato MIT R05 con las diferencias del plan §6 (magic/versión propios y validación de bytes totales).

**Por qué no un prefijo de longitud de flujo:** GATT conserva los límites del valor (un `Write` o `Notify` es un PDU completo), así que un flujo de bytes con longitud por frame añade solo riesgo de desincronización. Cada frame es exactamente un valor ATT y su longitud la da la longitud del valor. La longitud del **mensaje** sí va en el primer frame (`totalLength`) para rechazar excesos antes de reservar memoria (F07). **Alternativa:** flujo de bytes con prefijo de longitud (más portable si una pila agrupa valores), a costa de resincronización tras corrupción; no recomendado en GATT.

**Por qué AEAD y no CRC:** la integridad útil frente a un atacante la da el tag AEAD de la sesión; el CRC no es seguridad y el enlace ya tiene CRC en capa de enlace. Se evita un CRC por frame. Para el único tráfico sin AEAD (laboratorio y HANDSHAKE) la integridad es la propia del handshake (cualquier alteración lo hace fallar) o un CRC-32C opcional al final del cuerpo en modo laboratorio (3.4).

### 3.2 Cabecera de frame (9 bytes, byte-exacta)

| Offset | Tamaño | Campo | Valores |
|---|---|---|---|
| 0 | 1 | `frameVersion` | `0x01` |
| 1 | 1 | `type` | Bit 7 = crítico. Definidos (todos críticos): `0x81` DATA, `0x82` ACK, `0x83` ERROR, `0x84` CANCEL, `0x85` HANDSHAKE. `0x01–0x7F` reservados para extensiones **no críticas** (un tipo no crítico desconocido se descarta y cuenta; un tipo crítico desconocido → `E_UNSUPPORTED_TYPE`). |
| 2 | 1 | `flags` | Bit 0 `PRIORITY` (carril de control). Bits 1–7 reservados: deben ser 0; distinto de 0 en v1 → `E_MALFORMED_FRAME`. |
| 3 | 2 | `messageId` | u16, por dirección. Identifica un mensaje dentro de la sesión; puede reutilizarse tras la ventana de deduplicación (≥256 ids de distancia). |
| 5 | 2 | `fragIndex` | u16, desde 0. |
| 7 | 2 | `fragCount` | u16 ≥ 1. Igual en todos los frames del mensaje. |

Si `type` es DATA o HANDSHAKE **y** `fragIndex == 0`, siguen 4 bytes `totalLength` (u32): longitud del cuerpo del mensaje (suma de cargas de todos los frames). Después, la carga del frame.

ACK, ERROR y CANCEL son siempre un solo frame (`fragCount=1`, `fragIndex=0`) con cuerpo fijo:

| Tipo | Cuerpo |
|---|---|
| ACK | vacío. Confirma el mensaje `messageId` (reensamblado y validado, 2.ª confirmación de `Doc/01` §5). |
| ERROR | `u16 errorCode` + `u16 relatedMessageId` (0xFFFF si no aplica). `messageId` de la cabecera = 0. |
| CANCEL | vacío. Cancela `messageId`; idempotente. |

Formas exactas (Revisión 2): ACK y CANCEL miden exactamente 9 bytes; ERROR mide exactamente 13 bytes y su `messageId` de cabecera es 0; cualquier otra longitud o forma → `E_MALFORMED_FRAME`. ACK, ERROR y CANCEL son de un solo frame y pueden aparecer entre los frames de un mensaje DATA/HANDSHAKE abierto sin alterarlo (3.6).

### 3.3 Cuerpo de DATA en sesión (AEAD)

`body = counter(u64) ‖ ciphertext ‖ tag(16)`; `totalLength = 8 + len(ciphertext) + 16`. El texto plano es el JSON UTF-8 del mensaje (campos: `kind`, `requestId`, `capability`, `ttlMs`, `payload`; ver 6). Datos asociados autenticados (AD): `frameVersion ‖ type ‖ flags ‖ messageId ‖ totalLength`. Como se autentican los bytes exactos del texto plano, **no se necesita JSON canónico** ni depende el significado del orden de claves.

Reglas de JSON (para que Swift y Kotlin coincidan): UTF-8 válido sin BOM; profundidad máxima 8; sin claves duplicadas (rechazo); enteros solo en el rango seguro ±2^53−1; sin números no finitos; cadenas con longitud acotada por esquema. Alternativa: CBOR determinista o TLV binario (más compacto, parsing más estricto) — requiere cambiar la decisión de `Doc/01` §4; solo si Apple prefiere.

### 3.4 Modos sin AEAD

HANDSHAKE (mensajes del protocolo Noise, sección 8): cuerpo = mensaje de handshake sin añadidos. DATA en texto plano solo existe en builds de laboratorio con un indicador de compilación, nunca en producción ni con efectos. En ese modo `body = payload ‖ CRC-32C(u32)`. **Decisión (Q-A9, Revisión 2):** ningún DATA viaja sin AEAD en producción. El modo laboratorio solo existe tras un indicador de compilación, y las pruebas con cifrado usan una sesión con clave fija rotulada «solo pruebas» (test-only), nunca presente en una compilación de producción. Los vectores de framing de este documento no necesitan ninguno de los dos: son independientes del cifrado.

### 3.5 Capacidad por frame y MTU (regla K1, opción A · Revisión 2)

El framer recibe `maxAttValueBytes` por dirección (nombre acordado en `Doc/01` §4). Un frame es exactamente un valor ATT, así que `len(frame) ≤ maxAttValueBytes`. La carga útil máxima depende de la posición del frame:

- primer frame de DATA/HANDSHAKE (cabecera 9 + `totalLength` 4 = 13 bytes): `maxAttValueBytes − 13`
- cualquier frame posterior (cabecera 9 bytes): `maxAttValueBytes − 9`

Con la capacidad mínima útil de 20 bytes (ATT_MTU 23) quedan 7 bytes en el primer frame y 11 en los demás: válido, aunque lento. Con `maxAttValueBytes < 14` el canal no se abre (`E_CAPACITY_TOO_SMALL`): 13 de cabecera más 1 byte de carga.

Regla de longitud (decisión K1; sustituye a «todas las cargas intermedias tienen igual longitud», que era inalcanzable porque el primer frame lleva 4 bytes menos de carga por su campo `totalLength`):

- **Emisor:** llena cada frame hasta la capacidad de su posición: el primero con `min(L, max−13)` bytes y cada siguiente con `min(restante, max−9)`, donde `L = totalLength`. Número de frames: 1 si `L ≤ max−13`; si no, `1 + ceil((L − (max−13)) / (max−9))`.
- **Receptor:** todo frame **no final** lleva exactamente la carga completa de su posición (primero `max−13`, resto `max−9`). El frame final lleva el resto: `1..max−9` bytes, nunca vacío (si es el único frame, `L ≤ max−13`). Cualquier desviación (frame no final más corto o más largo que su carga completa, frame final vacío o mayor que `max−9`, fragmento vacío) → `E_MALFORMED_FRAME`. Si la suma de cargas no iguala `totalLength` → `E_LENGTH_MISMATCH`.
- **Capacidad del emisor:** no viaja en la cabecera. El receptor la deduce del primer frame de un mensaje de varios frames (`max = carga + 13`) y exige a los siguientes `carga del primero + 4` bytes; si la conoce por otra vía (medición propia), exige además que el primer frame llene exactamente `max−13`. `fragCount` debe coincidir con el número de frames derivado de `totalLength` y la carga del primer frame; si no, `E_LENGTH_MISMATCH`, comprobado en el primer frame y antes de reservar memoria.
- **Ejemplo:** `max = 20`, `L = 24`: cargas 7 + 11 + 6, tres frames de 20, 20 y 15 bytes (vector F02-a). Alternativa (B) descartada: carga uniforme `max−13` en todos (7 + 7 + 7 + 3, cuatro frames, 4 bytes desperdiciados por frame).
- **Límites por tipo:** el tipo lógico (control o lote) va cifrado dentro del cuerpo, así que antes de descifrar el receptor solo distingue por `type` y por el flag `PRIORITY`: HANDSHAKE (propuesta 1 KiB), DATA con `PRIORITY` = carril de control (8 KiB de texto plano + 24 de contador y tag) y DATA sin `PRIORITY` = lote (64 KiB + 24). `totalLength` se compara con ese límite antes de reservar nada (F07). *Interpretación de Windows, a confirmar por Apple.*
- **Mínimos:** `totalLength ≥ 24` para DATA (contador 8 + tag 16) y `≥ 1` para HANDSHAKE.
- **Orden de validación** (determinista, para que los vectores no dependan del orden): valor vacío → `frameVersion` → cabecera truncada (<9) → `type` (bit 7 limpio: se ignora y se cuenta; crítico desconocido: `E_UNSUPPORTED_TYPE`) → flags reservados → `fragCount = 0` o `fragIndex ≥ fragCount` → forma por tipo (ACK/CANCEL/ERROR; primer frame <13) → con estado de reensamblado: límite de `totalLength` (`E_OVERSIZE`) → mínimo de `totalLength` → fragmento vacío → reglas de longitud de arriba.

- Android obtiene el valor del MTU efectivo negociado menos el overhead de la operación; **no** se usa el MTU solicitado. iOS usa `maximumWriteValueLength(for:)` para la tubería Requests y la capacidad efectiva de notificación para Events. Nunca se restan 3 bytes dos veces a un valor ya calculado por iOS. La capacidad es **independiente por dirección** y no viaja en la cabecera.
- Si la capacidad cae a mitad de mensaje (por ejemplo, por una renegociación MTU durante Service Changed), el emisor envía CANCEL y reinicia el mensaje con un `messageId` nuevo y el mismo `requestId`; no reparte el mensaje en frames de tamaño variable.
- Fragmentos vacíos no existen: un mensaje DATA mínimo tiene `totalLength ≥ 24` (contador + tag). F03 trata el límite de cero bytes de texto plano a nivel de payload, no de frame; a nivel de frame, la regla de longitud de arriba hace comprobables F02/F03 sin ambigüedad.

### 3.6 Reensamblado y orden

- Los frames de un mismo `messageId` llegan en orden creciente de `fragIndex` (la tubería es fiable y ordenada). Duplicado, salto o retroceso de `fragIndex` → descartar el parcial y `E_SEQUENCE` (F04).
- **Sin intercalado en v1 (Q-A3, cerrada en Revisión 2):** hay a lo sumo UN mensaje DATA/HANDSHAKE abierto por dirección y sus frames son contiguos. El primer frame de otro mensaje con uno abierto se rechaza con `E_TOO_MANY_PARTIALS` y un contador; el abierto se conserva (sin expulsión silenciosa, F06). `maxPartialMessages` vale 1 en v1.
- ACK, ERROR y CANCEL son de un solo frame y pueden aparecer entre los frames de un mensaje abierto sin alterarlo, salvo un CANCEL de ese mismo `messageId`, que lo descarta. Un ACK de un mensaje que no está en vuelo (repetido, tardío o inexistente) se ignora y se cuenta: no es error ni cierra la sesión.
- Los mensajes de control son ≤ 8 KiB y los lotes (salud) se dividen en mensajes de ≤ 8 KiB de texto plano, con un mensaje en vuelo por dirección; el control espera como máximo a un mensaje (≈ tiempo de 8 KiB). La cola de salida sirve el carril `PRIORITY` entre mensajes, nunca dentro de uno. El contador AEAD es estrictamente creciente: no hace falta ventana de contadores ni `SetNonce` (8.5).
- **Opción futura (no v1):** intercalado de mensajes con `counter` explícito por mensaje, ventana de 64 contadores y `SetNonce` de Noise, solo si la medición de latencia de control lo exige. Se conserva aquí como texto de referencia; requeriría una revisión nueva de 8.5.
- Plazo: un parcial sin progreso durante 15 s o más (reloj monótono; cada frame aceptado reinicia el plazo) se descarta con un contador (`E_TIMEOUT`); un frame posterior de ese mensaje es huérfano y recibe `E_SEQUENCE` (F11).
- Un frame de continuación (`fragIndex > 0`) sin mensaje abierto, o de otro `messageId`, recibe `E_SEQUENCE` sin tocar el mensaje abierto. Un reinicio del mismo `messageId` con `fragCount`, `totalLength`, tipo o flags distintos es el conflicto F05: `E_SEQUENCE` y cierre de sesión; un duplicado idéntico solo descarta el parcial.
- El mensaje se entrega a la capa superior solo cuando está completo, con tag válido y contador aceptado. Nunca se entregan parciales.
- Finalización: el último frame (`fragIndex == fragCount−1`) cierra el mensaje; la suma de cargas debe igualar `totalLength` (si no, `E_LENGTH_MISMATCH`) y el tag debe verificar (la verificación del tag pertenece a la capa de sesión, fuera del framing).
- Ordenación entre mensajes: el orden de entrega a la aplicación es el orden de **finalización**; los efectos que dependen de orden entre mensajes deben declararlo en su capacidad (no se asume).

## 4. Sesión y suscripción

1. Enlace cifrado/vinculado → descubrimiento → leer Capabilities → habilitar CCCD de Events y esperar la confirmación → handshake (sección 8) por frames HANDSHAKE → mensaje DATA `hello` autenticado con `protocolMajor/Minor`, `sessionId`, capacidades efectivas.
2. `sessionId` = primeros 8 bytes del hash final del handshake (`h`), por lo que es único por sesión y común a ambos lados. Nunca se restaura tras un crash ni se reutiliza en otra conexión (`Doc/01` §4).
3. El canal propio está «listo» solo con `hello` autenticado en ambos sentidos.

## 5. Control de flujo

| Aspecto | Recomendado *(propuesta)* | Alternativa / compromiso |
|---|---|---|
| Escritura iPhone→reloj | Una escritura con respuesta en vuelo; el siguiente frame tras el callback. Prioridad: el carril `PRIORITY` se sirve entre mensajes, no dentro de uno (sin intercalado, 3.6). | Sin respuesta + `canSendWriteWithoutResponse`: ver sección 1. |
| Notificación reloj→iPhone | Una `notify` en vuelo; el siguiente frame tras `onNotificationSent`; si `notify` devuelve «ocupado/falso» → pausa y reanudación exacta en el siguiente frame pendiente (F08), sin reordenar ni duplicar. | Indicate: ver sección 1. |
| ACK de aplicación | El receptor responde ACK por mensaje reensamblado y válido. | ACK por frame: más tráfico, sin beneficio frente a la fiabilidad GATT. |
| Ventana | `maxInflightMessages` = 8 mensajes sin ACK por dirección (cola de envío; el reensamblado sigue siendo de un mensaje abierto, 3.6). | Ajustable por capacidades. |
| Cola de salida | Máx. 16 mensajes y 128 KiB por dirección; carril de control separado de ≤8 mensajes. | |
| Política de descarte | Nunca se descarta en silencio: lote antiguo de menor prioridad → CANCEL + contador; control nuevo con carril lleno → `E_BUSY` al emisor. Lo no enviado expira por `ttlMs`. | |
| Plazos | 15 s sin progreso en un parcial → descarte (valor de `Doc/01` §4); ACK esperado en 5 s *(propuesta, medir)*. | |
| Memoria | Techo de 4 parciales × 64 KiB = 256 KiB por sesión, la lectura de «≤256 KiB acumulados» de `Doc/01` §4 (Q-A4 confirmada por Apple). En v1 hay un solo parcial: uso real ≤ 64 KiB + 24 B. | |
| Límite antes de reservar | `totalLength` se compara con el límite del tipo de mensaje antes de reservar cualquier byte (F07). | |

Las tres confirmaciones se mantienen separadas (`Doc/01` §5): el callback GATT no es ACK de mensaje, y el ACK de mensaje no es resultado de dominio (`result`).

## 6. Recuperación e idempotencia

### 6.1 Identificadores

- `messageId` (u16, transporte): deduplicación de retransmisiones dentro de una sesión y de frames repetidos.
- `requestId` (16 bytes aleatorios, texto base64url o hex en JSON; **definir en el esquema**): identifica el **efecto**. Lo genera quien pide, lo persiste en su outbox **antes** de enviar y lo conserva al reenviar o consultar un resultado incierto (`Doc/01` §4).
- `counter` (u64, sesión): anti-replay del AEAD, no visible a la aplicación.

### 6.2 Reintento y caducidad

- `ttlMs` es **relativo**, medido por el receptor con reloj monotónico desde la recepción del mensaje completo; no se compara hora de pared entre dispositivos (`Doc/01` §4: política de relojes distintos; U20 del plan). Máximo 300 000 ms *(propuesta)*. Expirado → `result` `E_EXPIRED` sin efecto.
- El receptor guarda en una tabla (clave: identidad autenticada del par + `requestId`) el `result` durante `ttl + margen` (propuesta 10 min, ≥256 entradas, persistida para peticiones con efecto — P2-06). Un reenvío con el mismo `requestId` devuelve el resultado guardado o «en curso»; no repite el efecto.
- Efecto irreversible con resultado incierto y tabla perdida (crash/borrado): `E_OUTCOME_UNKNOWN`; **no** se reintenta ciegamente (`Doc/01` §5).
- Cada petición con efecto declara en su capacidad `idempotency`: `dedupe` (el receptor deduplica) o `none` (nunca se reintenta sin confirmación del usuario).

### 6.3 Qué sobrevive a una reconexión

| Sobrevive | Se invalida |
|---|---|
| Identidad pinneada de cada par y su clave pública estática. | `sessionId`, claves y contadores de sesión. |
| Outbox del emisor (peticiones pendientes con su `requestId`). | `messageId` en vuelo, parciales, ACKs, ventana, cola de salida no enviada. |
| Tabla de resultados del receptor. | Estado de handshake a medias. |

Tras reconectar, se hace un handshake nuevo y el emisor reenvía las peticiones pendientes (mismo `requestId`, `messageId` y `counter` nuevos). Una sesión anterior no se «reanuda»; cada reconexión empieza por el handshake (sin resumen de sesión en esta propuesta, para reducir superficie).

### 6.4 Eventos de enlace

Service Changed, cambio de MTU, Bluetooth apagado/encendido y pérdida de vínculo cierran la sesión de aplicación (no necesariamente el enlace). Recuperación: reevaluar capacidad (`maxAttValueBytes`), releer Capabilities, rehabilitar CCCD y nuevo handshake. Las generaciones de ANCS/AMS no se reutilizan para el canal propio (`Doc/01` §3 «callbacks de generaciones anteriores no mutan la sesión»; se aplica el mismo principio con una generación de sesión de aplicación).

## 7. Errores y casos límite

### 7.1 Códigos (u16, *propuesta*)

| Código | Nombre | Cuándo | Efecto en la sesión |
|---|---|---|---|
| 0x0001 | `E_MALFORMED_FRAME` | Cabecera truncada (<9 bytes o <13 en primer frame), reservados distintos de 0, `fragCount=0`, `fragIndex ≥ fragCount`, ACK/CANCEL/ERROR con forma o longitud incorrecta, ERROR con `messageId` ≠ 0, fragmento vacío, `totalLength` bajo el mínimo del tipo, frame no final que no llena exactamente la carga de su posición, frame final vacío o mayor que `max−9`. | Descartar el frame; tras 3 consecutivos o 8 en 60 s → cerrar sesión. |
| 0x0002 | `E_INCOMPATIBLE_FRAME` | `frameVersion` desconocido. | Cerrar sesión. |
| 0x0003 | `E_INCOMPATIBLE_MAJOR` | `protocolMajor` distinto. | Cerrar sesión. |
| 0x0004 | `E_UNSUPPORTED_TYPE` | Tipo crítico desconocido. | Cerrar sesión. |
| 0x0005 | `E_OVERSIZE` | `totalLength` > límite del tipo, o mensaje que supera memoria. | Rechazar antes de reservar; descartar el mensaje. |
| 0x0006 | `E_LENGTH_MISMATCH` | Suma de cargas ≠ `totalLength`, `fragCount` incoherente con `totalLength` y la carga del primer frame, o carga mayor que `totalLength`. | Descartar el mensaje. |
| 0x0007 | `E_SEQUENCE` | Duplicado, salto o retroceso de `fragIndex`; continuación sin mensaje abierto; `fragCount` o `totalLength` conflictivos para un `messageId` abierto. | Descartar el parcial; si el conflicto es F05, cerrar sesión. |
| 0x0008 | `E_TOO_MANY_PARTIALS` | Primer frame de otro mensaje con uno abierto (límite 1 en v1; el quinto parcial si se activa el intercalado futuro). | Rechazar el nuevo; contador. |
| 0x0009 | `E_TIMEOUT` | 15 s sin progreso. | Descartar el parcial. |
| 0x000A | `E_BAD_TAG` | Tag AEAD no verifica o contador repetido/fuera de ventana. | Descartar el mensaje; 3 seguidos → cerrar sesión. Nunca ejecutar efecto. |
| 0x000B | `E_BUSY` | Cola o carril llenos. | Reintento posterior del emisor. |
| 0x000C | `E_CAPACITY_TOO_SMALL` | `maxAttValueBytes < 14`. | No abrir el canal. |
| 0x0100 | `E_UNKNOWN_CAPABILITY` | `kind`/`capability` desconocido o deshabilitado. | `result` de error; la sesión sigue. |
| 0x0101 | `E_NOT_AUTHORIZED` | Capacidad sin sesión autenticada o sin permiso. | `result` de error. |
| 0x0102 | `E_EXPIRED` | `ttlMs` vencido. | `result` de error, sin efecto. |
| 0x0103 | `E_OUTCOME_UNKNOWN` | Ver 6.2. | `result` de error; requiere decisión del usuario. |
| 0x0104 | `E_INVALID_PAYLOAD` | JSON/esquema inválido. | `result` de error. |
| 0x0200 | `E_IDENTITY_CHANGED` | Clave estática distinta de la pinneada. | Sesión rechazada; reautorización visible (8.6). |

Los errores nunca incluyen datos del usuario ni contenido del mensaje.

### 7.2 Comportamiento acotado y reproducible

| Condición | Comportamiento |
|---|---|
| Mensaje truncado (menos frames de los declarados, o último frame corto) | Parcial descartado a los 15 s o ante `E_LENGTH_MISMATCH`; nada entregado; el emisor retiene la petición en su outbox. |
| Tamaño excesivo | `E_OVERSIZE` antes de reservar memoria; sin efecto. |
| Entrada desconocida (tipo no crítico, campo JSON, bit de capacidad no crítico) | Se ignora y se cuenta. Tipo crítico, bit crítico o `kind` desconocido: error visible, nunca éxito. |
| Desconexión a mitad de mensaje | Parciales y cola de salida se descartan; sin entrega parcial; tras reconectar, handshake nuevo y reenvío por `requestId`; el receptor no ejecuta nada de un mensaje sin reensamblar. |
| Desconexión tras el último frame y antes del ACK (F09) | El emisor no sabe si el receptor lo recibió: reenvía con el mismo `requestId`; el receptor deduplica y no duplica el efecto. |
| `result` perdido | El emisor reenvía la petición (mismo `requestId`) o la consulta; recibe el resultado almacenado. |
| Flood de frames/requestIds | Límites de tasa y tabla acotados (`Q10`); lo que no cabe recibe `E_BUSY`; el carril de control sigue servido. |

## 8. Seguridad y modelo de amenazas

> **Advertencia.** Esta sección es un diseño candidato **sin revisar**. No debe tratarse como diseño criptográfico aprobado. Antes de habilitar cualquier comando sensible Apple debe revisarla, y se necesita una revisión independiente con biblioteca ya revisada (`Doc/01` §6, plan §6.4). Lo que no supere la revisión queda desactivado, conservando el alcance ANCS comprobado. No se implementa nada de esto hasta aprobar.

### 8.1 Modelo de amenazas

| Amenaza | Supuesto | Mitigación propuesta | Q relacionada |
|---|---|---|---|
| Dispositivo BLE ajeno intenta usar las características | Cualquiera en alcance puede conectar si el reloj anuncia. | Permisos cifrados (vínculo LE) + handshake con identidad pinneada; sin sesión autenticada no hay capacidades con efecto. | Q01 |
| Observador pasivo | El enlace LE cifrado protege, pero un vínculo Just Works no es resistente a MITM activo. | Cifrado de aplicación con claves efímeras: confidencialidad con secreto hacia delante frente a un atacante que obtenga la clave del enlace más tarde. | |
| MITM activo durante el primer emparejamiento | No se confía en el vínculo LE como autenticación del par. | Registro inicial con comparación de código de seguridad (SAS) mostrado en ambas pantallas (8.4). | Q05 |
| Modificación de bytes (ciphertext, cabecera, tag) | | AEAD con la cabecera como datos asociados. | Q02 |
| Repetición de un mensaje válido o de una sesión previa | | Contador monotónico por dirección + ventana; claves por sesión; sesión nueva por reconexión. | Q03, Q04 |
| Degradación de versión/capacidades | El registro de Capabilities se lee sin autenticar. | Se repite dentro del handshake autenticado y se compara. | |
| Robo de clave estática de un lado | | Clave en almacén de plataforma, no extraíble por la app; revocación y rotación (8.6). | Q05, Q08 |
| Denegación de servicio por saturación | | Límites de memoria, tabla y tasa (sección 5). | Q10 |
| Fuga en logs/diagnóstico | | Solo identificadores, tamaños y códigos; nunca claves ni contenido (`Doc/01` §6). | Q06 |
| Compromiso del teléfono o del reloj | Fuera de alcance de esta capa. | Documentarlo; no se promete protección. | |

### 8.2 Autenticación del par: vínculo LE más sesión de aplicación

- El vínculo LE es un **requisito de acceso y cifrado de enlace**, pero **no** se toma como identidad: una app no puede leer la clave del enlace ni vincularla criptográficamente al handshake, y observar el servicio no equivale a identidad autenticada (`docs/apple/p2-contract-review.md`, Seguridad).
- La identidad del par es una clave pública estática de aplicación, fijada durante un registro asistido por el usuario y verificada en cada sesión.

### 8.3 Construcción candidata: patrón Noise sobre X25519 / ChaCha20-Poly1305

**Recomendado:** `Noise_XX_25519_ChaChaPoly_SHA256` (nombre del protocolo Noise) para el **registro inicial**, y `Noise_IK_25519_ChaChaPoly_SHA256` para las **reconexiones** (el iPhone como iniciador conoce la clave estática del reloj; el reloj comprueba que la clave estática del iPhone coincide con la pinneada). Razón: patrón estándar publicado y analizado; autenticación mutua; secreto hacia delante; el transcript queda autenticado en el hash `h`; claves por dirección separadas; nonces gestionados por el estado. Se evita construir un protocolo ad hoc.

- Prólogo del handshake: etiqueta del protocolo, `contractVersion`, `profileRevision`, UUID del servicio y el registro de Capabilities leído; así una degradación de versión o capacidades hace fallar el handshake.
- Carga útil cifrada de los mensajes 2 y 3 del handshake: `protocolMajor/Minor`, `frameVersion`, `featureBits` y `maxAttValueBytes` aceptados.
- Tamaños de referencia derivados del patrón XX con carga vacía: mensaje 1 = 32 bytes; mensaje 2 = 96; mensaje 3 = 64 (más la carga útil y su tag). Deben verificarse con los vectores oficiales de Noise, no asumirse.
- Los mensajes de handshake viajan en frames HANDSHAKE (`messageId` 1, 2, 3 de la sesión).
- Los frames DATA posteriores usan las claves de transporte de Noise (una por dirección), con contador explícito (3.6).

**Alternativas** y compromisos:

| Alternativa | Ventaja | Contra |
|---|---|---|
| **Candidato R05 endurecido**: P-256 / HKDF / AES-GCM (`Doc/01` §6; plan §6.4) | CryptoKit y plataforma Android lo cubren de forma nativa; permite claves en Secure Enclave (P-256). | Hay que diseñar a mano el transcript y la separación de claves (ECDH anónimo + AES-GCM **no** basta). Más superficie para errores. |
| **Noise sobre P-256** | Permite Secure Enclave en iOS. | No es una de las suites estándar de Noise; menor soporte de bibliotecas. |
| **Noise KK** en reconexión (ambos conocen las claves estáticas) | Un viaje menos, ambas estáticas ya conocidas. | Requiere que ambos mantengan el par intacto; falla mal ante pérdida de clave de uno. |
| **Sin capa de aplicación** (solo vínculo LE) | Simple. | No da identidad ni anti-replay; descartada para datos sensibles. |

Bibliotecas: la implementación de Noise debe ser una biblioteca mantenida y revisada en cada plataforma, o un componente propio **solo** con los vectores de prueba publicados de Noise como oráculo. La disponibilidad concreta de X25519/ChaCha20-Poly1305 en Android 13+ (minSdk 33) y su respaldo por Keystore debe **comprobarse** (Q-A6); CryptoKit en iOS ofrece Curve25519, ChaChaPoly y SHA-256 pero no el patrón Noise. No se elige biblioteca en esta propuesta.

### 8.4 Registro inicial y código de seguridad

1. Reloj en modo «registrar» (acción explícita en pantalla, con tiempo límite); iPhone con el accesorio autorizado por AccessorySetupKit.
2. Handshake XX. Ambos lados derivan un código corto (por ejemplo, 6 dígitos a partir de `h` mediante una derivación documentada) y lo muestran. El usuario confirma que coinciden en ambos dispositivos.
3. Solo entonces se guardan las claves públicas estáticas del otro lado como identidad pinneada, junto con la fecha de registro (sin datos personales).
4. Sin confirmación o con diferencia, no se guarda nada y la sesión se cierra.

### 8.5 Contadores y anti-replay

- Contador de 64 bits por dirección dentro de la sesión, estrictamente creciente en emisor. En v1 (sin intercalado, Q-A3) el receptor exige un contador estrictamente mayor que el último aceptado; un duplicado o anterior se rechaza (`E_BAD_TAG` + contador) sin efecto. La ventana de 64 contadores queda solo como opción del intercalado futuro (3.6).
- Límite de vida: renegociar (nuevo handshake) tras 2^31 mensajes o 24 h *(propuesta)*; Noise `Rekey` es una alternativa.
- Cada reconexión implica sesión y claves nuevas: un mensaje de una sesión anterior no verifica (Q04).
- En v1 **no** se requiere `SetNonce` (Q-A3 cerrada: sin intercalado, contador implícito en orden). Solo el intercalado futuro necesitaría contador explícito (`SetNonce` del apartado de transporte fuera de orden de la especificación Noise); sería un punto de revisión nuevo.

### 8.6 Almacenamiento de claves e identidad

| Plataforma | Propuesta | Notas |
|---|---|---|
| Android (reloj) | Clave estática X25519 cifrada con una clave AES-256-GCM no exportable del Android Keystore (con StrongBox si existe); archivo cifrado en almacenamiento privado de la app. | El servicio corre con el reloj bloqueado: la clave no puede exigir desbloqueo (`setUnlockedDeviceRequired` apagado). Usar X25519 nativo de Keystore solo si la verificación Q-A6 lo confirma. |
| iOS (iPhone) | Keychain con `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`, sin sincronización iCloud. | AfterFirstUnlock hace falta para reconectar en segundo plano con el teléfono bloqueado. Secure Enclave solo ofrece P-256: encaja solo con las alternativas P-256. |
| Ambos | Mantener identidad aparte de las claves de sesión; las claves de sesión nunca se persisten; el diagnóstico no registra claves, `h` ni contador. | |

Cambio de identidad, revocación y borrado:

- Si la clave estática del par difiere de la pinneada: `E_IDENTITY_CHANGED`, la sesión no se establece y la UI de ambos muestra «identidad cambiada»; **no** hay degradación a un modo sin identidad. Repetir el registro requiere acción explícita del usuario en ambos dispositivos (8.4). Un nuevo vínculo LE no sustituye una identidad por sí solo.
- Causas legítimas: reinstalación de la app iPhone, restablecimiento de fábrica del reloj, pérdida de la clave en el almacén. Qué ocurre con el Keychain tras desinstalar la app iPhone debe definirse (**Q-A7**).
- Revocación: «Olvidar dispositivo» en cualquiera de los lados borra la clave pública del par, invalida sesiones y vacía outbox y tabla de resultados de ese par (Q08).
- Rotación de clave estática: no definida en P2; propuesta de dejarla para una versión menor posterior.

### 8.7 Sensibilidad de comandos

Hasta aprobar esta sección y pasar G2, el canal solo puede transportar mensajes no sensibles de configuración/diagnóstico con datos sintéticos. Los comandos con efectos (salud, contactos, borrado, acciones sobre el teléfono) tendrán una capacidad propia por mensaje, autorizada por sesión autenticada, y están desactivados.

## 9. Vectores F01–F12 (lista de tareas; sin vectores todavía)

**No se incluyen vectores hexadecimales en este documento.** Los vectores de framing sin cifrado (F01–F07, F10–F12) viven en `test/fixtures/shared/p2-framing-vectors.json` (`draft-2`, todos PROPUESTO, derivados a mano; ver `docs/windows/p2-decisions.md`) y se comprueban en JVM con las pruebas `P2F01…`; no hay ejecución cruzada con Swift, así que el estado conjunto de F01–F12 sigue NOT_RUN. F08, F09 y los vectores con AEAD o handshake siguen sin vectores. Cada caso es una tarea de la lista conjunta. Reglas (`Doc/01` §7): el resultado esperado no se genera con el encoder bajo prueba; se produce a mano o con una herramienta independiente y lo revisan ambos lados; Swift→Kotlin y Kotlin→Swift; fragmentar en cada frontera relevante. El significado de F01–F12 proviene de la tabla del plan original §15.2 y puede reformularse para esta propuesta de framing.

| ID | Qué comprueba | Entrada (esquema) | Resultado esperado | Estado |
|---|---|---|---|---|
| F01 | Intercambio cruzado | Mensaje serializado en Swift y decodificado en Kotlin, y al revés, con campos idénticos y varios tipos de payload | Campos idénticos en ambos sentidos con los mismos bytes de referencia | NOT_RUN |
| F02 | Capacidades por dirección | `maxAttValueBytes` de 20, 64, 185, 512 y valores extremos (14, 13, máximo u16) | Ningún valor ATT excede la capacidad; <14 → `E_CAPACITY_TOO_SMALL` | NOT_RUN |
| F03 | Fronteras de fragmentación | Mensajes vacíos y tamaños exactos en cada frontera (N−1, N, N+1 frames) | Sin frame imposible ni padding mal interpretado; carga exacta por posición (K1/A: primero `max−13`, resto `max−9`) | NOT_RUN |
| F04 | Orden de frames | Duplicar, omitir y reordenar frames de un mensaje | Rechazo definido (`E_SEQUENCE`); nunca mezcla de mensajes | NOT_RUN |
| F05 | Identificador conflictivo | Mismo `messageId` con `fragCount` o `totalLength` distintos | Parcial/sesión rechazado según la política (3.6, 7.1) | NOT_RUN |
| F06 | Límite de parciales | Segundo mensaje mientras hay uno parcial (límite 1 en v1; cinco con límite cuatro si se activa el intercalado) | `E_TOO_MANY_PARTIALS` explícito con contador; memoria acotada | NOT_RUN |
| F07 | Tamaño total excesivo | `totalLength` > límite con número de frames válido | `E_OVERSIZE` antes de reservar el exceso | NOT_RUN |
| F08 | Contrapresión | Pausa a mitad de envío y posterior «listo» | Reanuda exactamente el siguiente frame pendiente, sin duplicar ni perder | NOT_RUN |
| F09 | Desconexión en el último frame | Desconexión tras el último frame, reconexión y reenvío por `requestId` | El mensaje lógico no produce dos efectos | NOT_RUN |
| F10 | Versionado | Major distinto; campos minor desconocidos; bit crítico desconocido | Major incompatible visible; extensiones compatibles ignoradas; nunca éxito | NOT_RUN |
| F11 | Parcial caducado | Stream parcial expira y luego llega el final viejo | No se combina con otro mensaje; limpieza | NOT_RUN |
| F12 | Cancelación y prioridad | Cancelar una transferencia larga mientras llega una acción de control | Cancelación efectiva; el control espera como máximo a un mensaje de ≤ 8 KiB, no a todo el lote | NOT_RUN |

Casos de seguridad relacionados (plan §15, Q01–Q12), a cubrir en el mismo esfuerzo cuando se apruebe la sección 8: Q01 dispositivo ajeno, Q02 mutación de cifrado/cabecera/tag, Q03 repetición, Q04 mensaje de sesión anterior, Q05 cambio de identidad, Q10 flood. Q07–Q09 (persistencia transaccional) pertenecen a P2-06.

Coautoría propuesta: Windows redacta el manifiesto con el formato (caso, esquema, bytes, resultado, origen de la expectativa) y Apple añade sus vectores propuestos en una rama de revisión; el editor integra el conjunto oficial (`Doc/01` §7).

## 10. Preguntas abiertas para Apple

| ID | Pregunta |
|---|---|
| Q-A1 | Método de emparejamiento observado entre iPhone y reloj (Just Works / comparación numérica): ¿se puede afirmar protección MITM del enlace, o se declara solo cifrado? |
| Q-A2 | ¿Aceptan el registro binario de 16 bytes de Capabilities o prefieren JSON pequeño? |
| Q-A3 | ¿Intercalado de mensajes con contador explícito, o sin intercalado y lotes divididos? **Cerrada (Windows, Revisión 2; coincide con Apple):** sin intercalado en v1, control ≤ 8 KiB, lotes divididos en mensajes, un mensaje abierto por dirección. Se reevalúa tras medir latencia. |
| Q-A4 | ¿Es 4 parciales × 64 KiB = 256 KiB la lectura correcta de «≤256 KiB acumulados por sesión»? **Confirmada por Apple** (techo; en v1 hay un solo parcial). |
| Q-A5 | ¿Capacidad efectiva de `didUpdateValueFor` en notificaciones y de `maximumWriteValueLength` que observan en el iPhone real (por conexión, con y sin MTU grande)? Se necesitan valores medidos, no teóricos. |
| Q-A6 | Disponibilidad y revisión de bibliotecas Noise/X25519/ChaCha20-Poly1305 en Swift (CryptoKit) y Kotlin; ¿aceptan un componente propio probado solo con los vectores de Noise? |
| Q-A7 | Ciclo de vida del Keychain al desinstalar/reinstalar la app iPhone y política de «olvidar dispositivo». |
| Q-A8 | ¿El reloj debe mantener la identidad ante un cambio de vínculo LE, o cada nuevo vínculo obliga a registro nuevo? |
| Q-A9 | ¿Eliminan el modo DATA sin AEAD de laboratorio (3.4)? **Cerrada (Windows, Revisión 2; coincide con Apple):** DATA sin AEAD prohibido en producción; solo tras un indicador de compilación de laboratorio y con clave de prueba rotulada «solo pruebas». |
| Q-A10 | Confirmación de nombre anunciado `WristBridge` y su coexistencia con el filtro ASK (ver `docs/windows/handoff-to-apple.md`). |

## 11. Qué falta para pasar de borrador a contrato

0. Hecho en Revisión 2 (solo framing): K1 (carga por frame), Q-A3, Q-A9 y K2 (vectores a mano verificados). Pendiente de Apple: confirmar la interpretación de límites por tipo (3.5) y revisar los vectores nuevos de Windows.
1. Revisión Apple de cada fila de este documento; decisiones por escrito.
2. Medición física de `maxAttValueBytes`, comportamiento de CCCD con vínculo existente y método de emparejamiento.
3. Revisión independiente de la sección 8 y elección de biblioteca.
4. Manifiesto de vectores F01–F12 aprobado por ambos lados.
5. Solo entonces: materializar `contracts/control.schema.json`, `contracts/framing.md`, `contracts/capabilities.json`, `contracts/compatibility.md`, subir `profileRevision` y exponer las características reservadas. Este borrador no cambia `contracts/ble-profile.json`.
