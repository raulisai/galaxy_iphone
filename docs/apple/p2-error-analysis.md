# Canal seguro iPhone ↔ reloj · ¿se puede implementar ya? y análisis de errores

18 de octubre de 2026 · Apple (`development_codex`). Estado vivo: [`SEED.md`](../../SEED.md). Base: [`p2-channel-draft.md`](../../contracts/proposals/p2-channel-draft.md) revisión 2 y [`p2-decisions.md`](../windows/p2-decisions.md).

## ¿Se puede implementar ya el canal seguro?

| Parte | ¿Ahora? | Estado |
|---|---|---|
| **Framing y reensamblado** (bytes ↔ frames), **modelo de errores** | **Sí** | **Hecho en Swift** (`P2Framing.swift`), probado contra los 68 vectores de Windows: PASS. Sin cifrado, sin GATT, sin llamarlo desde la app |
| Cifrado de sesión (Noise, AEAD), identidad, Keychain, registro con código de seguridad | **No** | Bloqueado por K3: falta la **revisión independiente de seguridad** y elegir biblioteca. No se escribe criptografía propia sin eso |
| Exponer las características GATT en el reloj y usarlas desde el iPhone | **No** | Depende de lo anterior; el reloj aún no las expone |
| Mensajes de salud / buscar por el canal | **No** | Depende de lo anterior (salud es dato sensible) |

Conclusión: el único paso técnico que se podía adelantar sin riesgo —**definir y probar los fallos**— ya está. Lo que impide ver salud en el iPhone sigue siendo K3 (una persona o un tercero que revise la seguridad) y después el trabajo Kotlin. Esa decisión no se puede tomar programando.

## Qué puede fallar y cómo se maneja

Leyenda: **Hecho** = implementado y probado; **Diseñado** = definido en el borrador, sin código; **Bloqueado** = necesita seguridad/canal.

### 1. Mensajes dañados o incompletos (framing)

| Fallo | Detección | Respuesta | Estado |
|---|---|---|---|
| Frame vacío, cabecera < 9 B, flags reservados, `fragCount = 0`, `fragIndex ≥ fragCount`, forma incorrecta de ACK/CANCEL/ERROR | `P2Codec.parse` en orden fijo | `E_MALFORMED_FRAME`; se descarta. 3 seguidos u 8 en 60 s → cerrar sesión (un frame válido reinicia la racha) | Hecho |
| `frameVersion` desconocido | primer byte | `E_INCOMPATIBLE_FRAME`, cierra sesión; vale con 1 byte | Hecho |
| Tipo desconocido | bit 7 | no crítico: se ignora y se cuenta; crítico: `E_UNSUPPORTED_TYPE`, cierra sesión | Hecho |
| Mensaje demasiado grande | `totalLength` vs límite por tipo | `E_OVERSIZE` **antes de reservar memoria** | Hecho |
| Suma de cargas ≠ `totalLength`, `fragCount` incoherente | primer frame y último frame | `E_LENGTH_MISMATCH`; se descarta | Hecho |
| Frame salteado, duplicado, retrocedido, huérfano | `fragIndex` esperado | `E_SEQUENCE`; se descarta el parcial | Hecho |
| Mismo `messageId` con otro `fragCount`/tamaño/tipo (conflicto) | comparación con el abierto | `E_SEQUENCE` **y cierre de sesión** | Hecho |
| Segundo mensaje mientras hay uno abierto | `messageId` distinto | `E_TOO_MANY_PARTIALS`; el abierto se conserva | Hecho |
| Mensaje que no termina (15 s sin progreso) | reloj monótono | se descarta y se cuenta; la cola tardía es huérfana | Hecho |
| CANCEL / ACK fuera de lugar | tabla en vuelo | CANCEL idempotente; ACK desconocido se ignora y se cuenta | Hecho |
| Capacidad ATT < 14 B | al codificar | `E_CAPACITY_TOO_SMALL`, el canal no se abre | Hecho |

### 2. Enlace Bluetooth

| Fallo | Respuesta | Estado |
|---|---|---|
| Desconexión a mitad de mensaje | `reset()` descarta el parcial; nunca se entrega nada incompleto; tras reconectar, sesión nueva y reenvío por `requestId` | Hecho (reset) / Diseñado (reenvío) |
| Desconexión tras el último frame y antes del ACK (F09) | El emisor reenvía con el mismo `requestId`; el receptor deduplica | Diseñado; el libro de salud ya es idempotente (S03) |
| Service Changed, cambio de MTU, Bluetooth apagado/encendido, pérdida de vínculo | Cierra la sesión de aplicación (no necesariamente el enlace); releer capacidades, rehabilitar CCCD, nuevo handshake. **Nunca** toca ANCS | Diseñado |
| CCCD persistente en un par vinculado | No cuenta como señal de sesión; se espera `didUpdateNotificationStateFor` antes de HELLO | Diseñado |
| Capacidad cambia a mitad de envío | CANCEL + reenvío con `messageId` nuevo y el mismo `requestId` | Diseñado |

### 3. Sesión y seguridad (todo **bloqueado** por K3)

| Fallo | Respuesta prevista | Estado |
|---|---|---|
| Etiqueta AEAD inválida o contador repetido | `E_BAD_TAG`, se descarta; 3 seguidos cierran sesión; **nunca** se ejecuta un efecto | Bloqueado (código de error ya existe) |
| Versión mayor distinta | `E_INCOMPATIBLE_MAJOR`, cierra sesión, mensaje visible | Código listo; negociación bloqueada |
| Clave del reloj distinta de la fijada | `E_IDENTITY_CHANGED`; no hay degradación a «sin identidad»; hay que registrar de nuevo en ambos | Bloqueado |
| Capacidad sin sesión autenticada | `E_NOT_AUTHORIZED` | Bloqueado |
| Desinstalar y reinstalar la app (Keychain persiste) | Detectar instalación nueva y pedir conservar u olvidar la identidad | Diseñado; comprobar en dispositivo (Q-A7) |

### 4. Datos de salud en el iPhone (libro)

| Fallo | Respuesta | Estado |
|---|---|---|
| Valor negativo, NaN, fuera de rango, unidad incorrecta, intervalo inválido, fecha futura o fuera de retención, identificador no válido | Se rechaza con motivo y el lote **no** se confirma al reloj | Hecho (S01–S06) |
| Mismo `syncId` repetido / versión menor | Duplicado o ignorado; sin doble conteo | Hecho |
| Reinicio del contador del reloj | Nunca se resta ni se inventa actividad negativa | Hecho |
| El archivo del libro es ilegible o de una versión futura | Se conserva, se avisa y se detiene (no se sobrescribe) | Hecho |
| Disco lleno o error al guardar | `ingest` lanza error → **no se confirma** → el reloj conserva el lote | Hecho |
| Interruptor «Recibir datos de salud» apagado | `ingest` devuelve `false`; no se confirma | Hecho |
| Se cae entre guardar y confirmar | El reenvío es idempotente | Hecho (S03) |
| Permiso de Apple Salud denegado o revocado | Afecta solo la copia opcional a Apple Salud, nunca al libro ni a los avisos | Diseñado |

### 5. Qué ve el usuario

`P2Error.userFacingSummary` da un texto fijo por error, **sin contenido ni identificadores**: versiones incompatibles, identidad cambiada (volver a registrar), mensaje no verificable descartado, reloj ocupado, conexión demasiado limitada, mensaje que no llegó completo. Los detalles técnicos van solo al diagnóstico (códigos y tamaños, sin claves ni datos).

## Qué falta para cerrar los errores del canal

1. K3 (revisión de seguridad + biblioteca) → implementar `E_BAD_TAG`, `E_IDENTITY_CHANGED` y el registro con código de seguridad.
2. Cablear `P2Reassembler` a las características GATT cuando el reloj las exponga (Apple), usando `reset()` en cada evento de enlace de la tabla 2.
3. Registrar contadores (ignorados, ACK desconocidos, caducados) en el diagnóstico exportable, sin contenido.
4. Pruebas físicas: cortar a mitad de mensaje, apagar Bluetooth, Service Changed; todas **NOT_RUN**.
