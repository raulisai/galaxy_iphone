# Contrato de coordinación Swift ↔ Kotlin

Versión del documento 1.0 · Base del plan WristBridge · Estado: especificación para iniciar; perfil BLE y protocolo sensible pendientes de sus gates físicos y de seguridad.

Este archivo separa decisiones comunes de implementaciones nativas. El editor de `contracts/**` será el Codex de Windows; el Codex del Mac revisará cada cambio que afecte a Swift. Ambos pueden proponer cambios. El editor no tiene autorización para declarar probada una capacidad del otro sistema.

## 1. Decisiones que se conservan

| Tema | Decisión |
|---|---|
| Producto | Dos apps nativas; Kotlin/Compose en Wear OS y Swift/SwiftUI en iPhone. |
| Avisos de otras apps | ANCS del sistema iOS consumido directamente por Kotlin. No hay API Swift de lectura general de avisos. |
| Transporte propio | BLE para configuración/datos propios, separado de ANCS. No usar Wearable Data Layer como enlace iPhone. |
| Roles | A preferido: watch peripheral/GATT server propio, iPhone central; watch también actúa como cliente GATT de ANCS. B alternativo: iPhone peripheral del servicio propio y watch central. No confundir rol del enlace con rol GATT. |
| Viabilidad | Elegir A/B mediante ADR-001 con Watch7+iPhone. La publicidad inversa y ANCS autorizado no se dan por demostrados. |
| Versiones | Registrar versiones/builds reales. Watch7 es el modelo, no la versión de Wear OS. Pin de herramientas por plataforma en G0; revisar fuentes oficiales al ejecutar. |
| UI de conexión | Estados separados para vínculo, ANCS, canal propio e Internet/IA. «Listo» solo tras operaciones confirmadas. |
| Canal sensible | Desactivado hasta P2-05 y validación cruzada. El MVP no requiere inventar criptografía. |

## 2. Registro único de UUID

Se reservan los siguientes UUID de 128 bits para el proyecto. Son identificadores de nuestro servicio, **no UUID oficiales de Apple ni una certificación**. Windows los materializa en `contracts/ble-profile.json` y ambos los consumen como constantes derivadas de ese registro. Cambiarlos exige un cambio común de contrato.

| Identificador | UUID reservado |
|---|---|
| Servicio propio | `6f06b8b1-21d9-4c65-9d7e-118880e36a01` |
| Capacidades/handshake | `6f06b8b1-21d9-4c65-9d7e-118880e36a02` |
| Solicitudes | `6f06b8b1-21d9-4c65-9d7e-118880e36a03` |
| Eventos | `6f06b8b1-21d9-4c65-9d7e-118880e36a04` |

En el spike P1 basta anunciar el servicio y disponer del comportamiento mínimo necesario para configuración; reservar características no obliga a implementar P2. El perfil debe definir, antes de probar datos propios, propiedades, permisos cifrados, CCCD, tamaño de lectura y cómo se envía en cada dirección para el rol elegido. No improvisar el mapeo entre `request` lógico y característica desde cada app.

El anuncio no incluye credenciales, conversaciones ni el nombre personal del usuario. Usar el mismo identificador de servicio en el filtro del picker iOS y en la publicidad Kotlin. No utilizar identificadores de fabricante de otra compañía.

## 3. ANCS no usa nuestro envelope

Kotlin implementa los servicios/características y formatos oficiales de ANCS. Swift no serializa ni traduce mensajes ANCS. En P1 no se envían todos los avisos por una característica propia como sustituto de ANCS.

Invariantes bajo responsabilidad Windows:

- Confirmar Data Source antes de Notification Source; distinguir habilitación local y confirmación del descriptor remoto.
- Serializar operaciones GATT. Una escritura confirmada no equivale a una respuesta ANCS completa.
- Una transacción de atributos activa; terminar por respuesta completa, error o deadline.
- `NotificationKey = (sourceDeviceId, sessionGeneration, uint32UID)`; UID sin pérdida por signo.
- Coalescer Modified; Removed invalida la tarjeta e impide resurrección por respuesta tardía.
- Callbacks de generaciones anteriores no mutan la sesión actual.
- Límites y deadlines monotónicos; no retardos fijos de 500 ms ni gracia que pierda avisos nuevos al reconectar.

Swift aporta la autorización y las condiciones de emisión, no un ACK por cada aviso de terceros. El generador de la app iOS conoce sus propias notificaciones, con registro de programación y comprobación de entrega donde sea observable. No tratar «programado» como «emitido por iOS».

## 4. Canal propio de P2

Formato lógico inicial: JSON UTF-8, con esquema versionado y payloads tipados. El editor crea `contracts/control.schema.json`, `contracts/framing.md`, `contracts/capabilities.json` y `contracts/compatibility.md`. Swift y Kotlin implementan codecs separados contra los mismos vectores. No usar el orden de claves JSON como significado; si se autentican bytes, autenticar los bytes exactos con la representación definida en el ADR.

| Campo | Acuerdo lógico |
|---|---|
| `protocolMajor`, `protocolMinor` | Enteros; major incompatible se rechaza visiblemente. Minor solo añade extensiones opcionales documentadas. |
| `sessionId` | Sesión autenticada actual; nunca restaurarla tras un crash sin garantía de estado consistente. |
| `sequence` | Secuencia por dirección; rango y codificación se fijan en el esquema para evitar diferencias de enteros Swift/Kotlin. |
| `messageId` | Identidad de mensaje y deduplicación de transporte. |
| `requestId` | Identidad del efecto/petición; conservar al consultar resultado incierto. |
| `kind` | `hello`, `capabilities`, `request`, `result`, `event`, `ack`, `error`. |
| `capability` | Función versionada y autorizada; ausencia no equivale a permiso. |
| `payload` | Esquema por capacidad; tamaño acotado. |
| `ttl` | Caducidad; documentar unidad y política frente a relojes distintos antes de implementar. |

Los tipos exactos, opcionalidad, códigos de error, cabecera binaria, orden de bytes y significado de ACK **se congelan en P2-04**, no quedan a elección independiente de cada desarrollador. No se entrega aún un codec ejecutable ni un wire format final en este paquete.

Presupuestos iniciales del plan: control ≤8 KiB; lote ≤64 KiB; cuatro mensajes parciales; ≤256 KiB acumulados por sesión; 15 s sin progreso para expirar un parcial. Configurarlos en un solo documento y probar fronteras. Audio continuo usa una ruta específica posterior, no JSON/base64 por defecto.

La capacidad de entrada al framer se llama `maxAttValueBytes`. Android la obtiene de la negociación real y el overhead de la operación; Swift utiliza la capacidad CoreBluetooth correspondiente. No asumir que MTU solicitado fue aceptado ni restar tres bytes dos veces a valores ya calculados por iOS.

## 5. Tres confirmaciones diferentes

1. Confirmación GATT: terminó una operación de enlace.
2. Confirmación de mensaje: se recibió/reensambló/validó un mensaje lógico, según el contrato.
3. Resultado de dominio: el destinatario ejecutó o persistió el efecto, con resultado comprobable.

No mostrar «guardado en Salud» tras el primer ACK, ni «acción completada» tras una escritura ANCS. Usar «solicitado» o estado incierto cuando no exista confirmación final. Un efecto irreversible incierto no se reintenta ciegamente.

## 6. Identidad y privacidad

P1 conserva el vínculo autorizado y seguridad del sistema, sin acciones sensibles propias. P2 evalúa una sesión autenticada de biblioteca mantenida compatible con ambas plataformas. Si se usa el candidato P-256/HKDF/AES-GCM del repositorio MIT, requiere identidad fijada, transcript autenticado, claves por dirección/sesión, nonces únicos y revisión independiente; no copiar ECDH anónimo como seguridad terminada.

Windows implementa su adaptador Keystore y Mac el suyo Keychain. El contrato define semántica de revocación, cambio de identidad y repetición. Si no supera la revisión, queda desactivada la transferencia sensible y se conserva el alcance ANCS comprobado. Las claves de firma de apps no entran en Git.

## 7. Fixtures y compatibilidad cruzada

Windows crea un manifiesto de vectores con caso, esquema, bytes codificados, resultado esperado y origen de la expectativa. Mac añade sus vectores propuestos a una rama de revisión; el editor integra el conjunto oficial. El resultado esperado no se genera usando el mismo encoder que se está probando.

- Swift codifica → Kotlin decodifica y Kotlin codifica → Swift decodifica.
- Fragmentar en cada frontera relevante; Unicode por byte; longitudes vacías, máximas y superiores al máximo.
- Duplicar, omitir y reordenar frames; desconectar antes del último; caducar parcialmente; reiniciar sesión.
- Enfrentar versión actual con anterior y major incompatible.
- Verificar contrapresión real en ambas APIs y efectos idempotentes tras perder un resultado.

Las pruebas F01–F12 se ejecutan en ambos lados. ANCS U01–U20 pertenece a Kotlin; no añadir una implementación ANCS Swift por simetría artificial.

## 8. Documentos de traspaso obligatorios

Cada equipo entrega por hito `docs/<plataforma>/handoff-<gate>.md` con:

| Campo | Contenido |
|---|---|
| Revisión | Commit integrado, branch y estado limpio/sucio. |
| Herramientas | Versiones fijadas, sistema operativo de desarrollo y destino físico. |
| Interfaz | Hash/versión del contrato y camino A/B. |
| Capacidad | Implementado, probado, no probado y desactivado. |
| Pruebas | IDs, estado, comandos, evidencia y fallos reproducibles. |
| Artefactos | Build, checksum, método de instalación y firma requerida sin secretos. |
| Dependencia | Qué espera exactamente del otro equipo; datos sintéticos para reproducir. |

Un gate conjunto incluye `run.json`, las revisiones de ambas apps, versiones del hardware y el resultado por caso. No medir latencia restando relojes de dos equipos sin alineación o sin expresar su incertidumbre.
