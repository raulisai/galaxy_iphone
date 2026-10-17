# Guía para Codex en el Mac: aplicación del iPhone y extensiones Apple

Entrega esta guía junto con los documentos de coordinación, contrato, matriz y plan original. El desarrollo se realiza en **macOS con Xcode**; la aplicación inicial se instala en el **iPhone 15**. El Galaxy Watch7 ejecutará la app Kotlin del otro Codex.

## Alcance y límites de edición

Eres responsable de `apps/ios/**`, `packages/WristBridgeProtocol/**`, configuración Xcode, pruebas Swift y scripts Apple. Desde P6 también `apps/macos/**`. No edites Gradle, Kotlin ni el motor ANCS del watch. Revisa los cambios del contrato común; el editor coordinador es Windows, sin autoridad para aprobar por ti el funcionamiento Swift.

La app iPhone sirve para configurar el accesorio, permisos, diagnóstico y datos propios. **No puede leer libremente las notificaciones de todas las demás apps** mediante UserNotifications. Los avisos generales vendrán del servicio ANCS del sistema al cliente Kotlin. No construyas un «NotificationListener» iOS ficticio ni un servidor que retransmita esos avisos.

## Primera sesión: proyecto y preparación del enlace

1. Inspeccionar el repositorio y sus instrucciones. Si Windows ya creó la base, incorporarla antes de introducir rutas nuevas. Si no está disponible, crear el proyecto Apple en su rama y probarlo con un adaptador ficticio; no inventar un servicio BLE alternativo.
2. Crear `docs/apple/environment.md`: versión macOS, Xcode, Swift, SDK, versión/build real del iPhone, deployment target y método de instalación. Verificar herramientas actuales con fuentes oficiales y fijar la combinación que compila; no actualizar dependencias indiscriminadamente.
3. Crear `apps/ios/WristBridge.xcodeproj`, scheme compartido `WristBridge` y paquete `packages/WristBridgeProtocol`, con IDs de producto únicos. Añadir el paquete a la app. Mantener firma/equipo local sin secretos en Git.
4. Compilar y ejecutar la app vacía en iPhone. Una build de simulador verifica UI/compilación, no demuestra BLE con watch real ni firma física.
5. Implementar adaptadores separados para AccessorySetupKit, CoreBluetooth, permisos y diagnóstico. Seguir la documentación del SDK elegido respecto a capacidades y autorización; no pedir permisos adicionales solo porque los requiera otro framework en un ejemplo antiguo.
6. Usar el UUID de servicio del contrato para el filtro. Camino A: iPhone central y watch anuncia el servicio. El acceso ANCS inverso lo implementa Kotlin y tiene que probarse; la app Swift no lo garantiza por crear un `CBCentralManager`.
7. Entregar al Codex Windows el commit Apple, pasos de instalación, estados de autorización y un guion de aviso sintético. Ejecutar el spike conjunto y registrar ADR-001.

Puedes desarrollar UI, permisos, fixtures y pruebas con un periférico ficticio mientras falta el APK. No sustituir el experimento físico por mocks.

## P1 — MVP: tareas Swift

| ID | Implementación concreta | Evidencia local o conjunta |
|---|---|---|
| P1-01.S | Inventario iPhone, OS/build, cuenta/firma disponible y restricciones de instalación. | Documento sin seriales/datos sensibles. |
| P1-02.S | Proyecto iOS, paquete Swift y build propia mínima. | Build simulador y ejecución física separadas. |
| P1-04.S | Picker, autorización y transporte iOS del experimento A/B. | ADR-001 con rol real, permisos y condiciones de segundo plano. |
| P1-05.S | Onboarding, cancelación/reintento y explicación de compartir notificaciones cuando corresponda. | H02–H04; nunca estado «listo» basado solo en conexión. |
| P1-09.S | Emisor de avisos de nuestra app: lote determinista con `runId/fixtureId`. | Registro de programación; entrega observada se distingue de solicitud aceptada. |
| P1-10.S | Restauración y ciclo de vida del lado iOS; guiones de radio/reinicio/revocación. | H06–H09 y R01–R04 en conjunto según alcance. |
| P1-11.S | Ejecutar condiciones iPhone de la campaña MVP y reunir evidencia. | iPhone bloqueado, app cerrada normalmente y permisos negativos. |
| P1-12.S | Build firmada/instalada por un canal autorizado y guía reproducible. | El binario corresponde al commit de la campaña. |

P1-03/06/07/08 son Kotlin. No dupliques el parser ANCS ni su cola en Swift. Sí debes aportar señales honestas sobre permisos y el canal propio. Si el watch no informa ANCS listo aún, mostrar «no comprobado» o el estado recibido; no asumirlo.

El generador de notificaciones usa APIs públicas de UserNotifications para **nuestra app** y contenido sintético. Probar avisos de otras apps requiere estímulos específicos y observación real; no afirmar cobertura universal desde la app de fixtures. Documentar el efecto de Enfoque, previsualizaciones y privacidad del iPhone sobre lo que ANCS puede entregar.

## P2 — Protocolo, restauración y seguridad

- P2-02.S: restauración CoreBluetooth y recuperación permitida por el sistema. Distinguir suspensión, terminación por sistema y cierre forzado del usuario. No prometer ejecución permanente de la app iPhone.
- P2-04.S: revisar el wire format antes de congelar; implementar modelos/framing Swift y backpressure contra F01–F12. CoreBluetooth expone capacidades efectivas: no trasladar sin adaptar el cálculo de MTU de Android.
- P2-05.S: identidad del par, Keychain, validación de sesión/replay y cambios de identidad. Revisar biblioteca/protocolo con Windows antes de habilitar datos sensibles.
- P2-06.S: persistencia/migraciones iOS de ajustes, pares y resultados propios. No añadir un historial de notificaciones ajenas que no posees.
- P2-08.S: campaña 24/72 horas, cambios de permisos/radios y consumo de iPhone con condiciones controladas.

La UI no debe depender de que el proceso iOS esté siempre despierto para asegurar la entrega ANCS. El perfil BLE propio y la supervivencia ANCS se miden por separado. Si el camino B solo funciona con iOS en primer plano, documentarlo como fallo del objetivo de segundo plano.

## P3 — Ajustes y diagnóstico iPhone

P3-07.S: SwiftUI para accesorio autorizado, canal propio, estado ANCS reportado y momento de última comprobación, permisos por capacidad y recuperación guiada. Presentar fallos comprensibles y acciones posibles; no exponer jerga o registrar cuerpos de avisos.

P3-02.S: ajustes de privacidad/alertas que el usuario solicite y el contrato permita sincronizar. El watch aplica políticas locales. No diseñar botones de respuesta que ANCS no anuncie. P3-04/05 AMS y P3-01/03/06 UI/rendimiento del reloj corresponden a Windows; aporta reproductores/estímulos iPhone para sus pruebas.

## P4–P5 — Papel del iPhone en IA

El cliente de IA, streaming, dictado, TTS y audio del reloj son Kotlin. Tu trabajo empieza por P4-01.S: evaluar con Windows si hace falta configuración/autenticación iPhone y cuál es la ruta públicamente soportada para ese cliente. Un callback de login disponible en una biblioteca no demuestra elegibilidad de la app móvil.

Solo si el ADR lo exige, implementar el flujo Swift de autorización/configuración, almacenamiento Keychain, revocación y traspaso autorizado de configuración por el canal P2. Preferir tokens acotados; no trasladar una clave maestra de servicio al watch ni usar cookies/endpoints privados de ChatGPT. No prometer acceso al historial existente de ChatGPT o a voz por tener una suscripción.

Si el usuario elige configuración independiente en el watch, la app iPhone puede no necesitar módulo de IA. No crear un proxy HTTP iPhone permanente como dependencia oculta. Tampoco construir `services/ai-gateway` en Swift por suposición: P5-02 es una tarea separada coordinada por Windows.

P5-06.S solo aplica si una herramienta permitida debe ejecutarse en Apple: implementar un ejecutor tipado, idempotente y con confirmación donde corresponda. En el MVP no existe ese ejecutor. El Mac de desarrollo no debe mantenerse encendido para usar el chat del reloj.

## P6 — Aplicación de Mac, después del MVP

Esta fase crea **otra app**, `apps/macos`, no una pestaña que transforme iOS en lector de avisos Mac.

1. P6-01.S: ADR de captura. Empezar por integraciones explícitas, APIs/webhooks locales o CLI. Investigar APIs públicas actuales antes de prometer acceso general a notificaciones del Mac. Accesibilidad, si se explora, es una función experimental con permiso explícito; no leer bases privadas del sistema.
2. P6-02.S: agente de barra de menú, identidad propia, iniciar/detener y estado de conexión.
3. P6-03.S: servidor TLS local y enrolamiento; Windows implementa cliente y valida identidad. Bonjour descubre la instancia, no la autentica. El perfil LAN se congela con esquema y fixtures comunes antes del cliente real.
4. P6-04.S: entrada CLI/IPC local protegida para timers, CI y monitorización sintética. Proporcionar ejemplos y límites de entrada.
5. P6-05.S: IDs estables/procedencia y caducidad para que Kotlin deduplique donde exista identidad común real. No deduplicar solo por texto.
6. P6-06.S: ejecutores permitidos como abrir un proyecto autorizado, consultar estado o timer. Resolver IDs contra configuración local, sin shell ni rutas arbitrarias propuestas por el modelo.
7. P6-07.S: sleep/wake, red distinta y desconexión; cola acotada y limpieza. El enlace iPhone→watch sigue independiente.

La alternativa BLE del Mac requiere otro ADR/prueba de coexistencia con el iPhone; no habilitarla simultáneamente sin medir las conexiones disponibles en el reloj.

## P7 — Salud y contactos en iPhone

Swift implementa permisos HealthKit/Contacts por capacidad y las operaciones de Apple; Kotlin implementa sensores/outbox y almacenes watch.

- P7-01.S: capacidades y tipos de salud permitidos; denegación parcial/revocación sin afectar avisos.
- P7-02.S: ledger persistente, `syncId/syncVersion`, importación HealthKit e idempotencia. Guardar el efecto antes de ACK de dominio y reconciliar crash entre guardado y respuesta. No usar un Set de 500 IDs en UserDefaults como única defensa.
- P7-03.S: snapshot de contactos solo del conjunto autorizado, lotes/versiones/tombstones y commit transaccional en el receptor. Pérdida de permisos no se interpreta como orden de borrar todos los contactos.
- P7-04.S: controles de retención/borrado, procedencia y verificación de eliminación local. Documentar qué datos se escriben en HealthKit y qué política de borrado realmente aplica.

## P8 — Firma y entrega Apple

Congelar OS/builds probados, pasar regresión Swift y conjunta, migraciones y compatibilidad con contraparte anterior. Incluir privacidad, entitlements/capacidades realmente usados, procedencia y dependencias. Firmar e instalar mediante el canal disponible y autorizado; no asumir que una IPA sin firma adecuada es instalable.

Una cuenta personal de desarrollo puede imponer límites al canal de prueba: observarlos y documentar renovación/instalación sin convertirlo en una promesa de distribución pública. No publicar en App Store ni adquirir servicios por el hecho de preparar un prototipo.

## Reutilización de repositorios

R05 `hnkabraham/wear-ios-bridge` es candidato MIT para `BLEFramer.swift`, contrapresión/restauración y patrón de ACK de salud. Separar el gestor monolítico, ampliar límites por bytes y sustituir la deduplicación limitada por ledger. Mantener atribuciones y cambios documentados.

R01 aporta contexto del consumidor ANCS, pero sus componentes Android pertenecen al equipo Windows. R02/R03 carecen de licencia identificada en el estudio: observar conceptos e implementar desde Apple, sin copiar código. R04 es comparación de requisitos. R06/R07/R08 no se importan como cliente Swift ni como autenticación ChatGPT. Ver commits exactos, licencias y defectos en el plan original.

## Pruebas, comandos y entrega

Responsabilidad principal: flujo Apple H02/H03/H06–H09, UI08, ciclo iOS de R, F01–F12 del codec Swift y seguridad/persistencia Q aplicable. Emitir estímulos para N/A/M. P6 lidera L01–L10 con cliente Kotlin; P7 comparte S01–S10. La campaña H05 y los E2E siempre requiere el reloj.

Objetivos de comandos cuando existan los proyectos:

```bash
swift test --package-path packages/WristBridgeProtocol
xcodebuild -project apps/ios/WristBridge.xcodeproj -scheme WristBridge -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Crear además un esquema/destino de tests SwiftUI apropiado al proyecto. La prueba física usa el equipo de firma y destino real de esa máquina, sin hardcodear identificadores personales en Git. El otro Codex no puede validar tu firma desde Windows.

Entrega commit, versión de contrato, instrucciones de instalación, resultados por ID, logs redaccionados y un guion iPhone de la campaña. Clasifica lo pendiente como pendiente. Compartir solo el binario no permite reproducir una diferencia de permisos o de OS.

## Prompt para iniciar esta sesión de Codex

> Trabaja como responsable Mac/Swift de WristBridge. Lee 00_LEEME_Y_COORDINACION.md, 01_CONTRATO_COMPARTIDO.md, 03_MAC_SWIFT.md, 04_MATRIZ_60_TAREAS.md y el plan original adjunto. Inspecciona primero el repositorio y conserva el trabajo existente. Implementa ahora únicamente G0 y la parte Apple de P1: proyecto iOS/paquete Swift, onboarding del accesorio, permisos, diagnóstico y emisor sintético. Consume los UUID comunes; si falta el commit Windows, avanza con un adaptador ficticio sin inventar otro contrato. No implementes un lector general de notificaciones iOS ni edites Kotlin/Gradle. La ruta ANCS la consume directamente el watch. Compila, prueba lo local e instala en el iPhone si este entorno lo permite. Coordina el experimento A/B y entrega un handoff con commit y condiciones físicas necesarias. No declares G1 aprobado sin Watch7+iPhone con UI cerradas normalmente. No implementes aún agente Mac, IA o HealthKit antes de los gates correspondientes; el proyecto Swift queda en el Mac y la app se instala en el iPhone.
