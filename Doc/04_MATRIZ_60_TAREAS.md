# Matriz completa de propiedad: 60 tareas y pruebas

Conserva IDs del plan original. K = Codex Windows/Kotlin; S = Codex Mac/Swift; J = integración conjunta sobre hardware. Los sufijos `.K`, `.S` y `.J` pueden convertirse en subtareas del ID original. «Sin código» significa que esa tarea no justifica añadir un componente en la otra app; no impide revisar o ejecutar estímulos.

Las dependencias detalladas y las definiciones de cada prueba están en el plan original incluido. Esta tabla asigna código y evidencia; no vuelve a estimar esfuerzo ni convierte una prueba conjunta en dos pruebas aisladas.

## P1 — MVP, 12 tareas

| ID | Windows/Kotlin | Mac/Swift | Integración y criterio |
|---|---|---|---|
| P1-01 | Inventario watch/Windows y ADB. | Inventario iPhone/Mac y firma Xcode. | J consolida matriz real sin resetear hardware. |
| P1-02 | Gradle raíz, módulos Kotlin, app Wear y CI Android. | Proyecto iOS, paquete Swift y CI Apple. | G0: ambas builds desde un commit base integrado. |
| P1-03 | Parser ANCS puro y fixtures oficiales. | Sin código ANCS; revisión del alcance. | U01–U12 Kotlin. No depende de que Swift termine UI. |
| P1-04 | Publicidad/servidor watch y cliente ANCS; evaluar Nordic. | Picker/onboarding y transporte iOS A/B. | J documenta ADR-001 y elige un camino real. |
| P1-05 | Consumir resultado del enlace en diagnóstico watch. | Implementar onboarding/permisos/cancelación/reintento. | J verifica accesorio y permiso ANCS, no solo conexión. |
| P1-06 | Servicio connectedDevice, permisos Android y lifecycle. | Preparar condiciones iPhone bloqueado/UI cerrada. | H05; Swift no sustituye el servicio Android. |
| P1-07 | Cola GATT y transacción ANCS completa/deadline. | Sin código; aportar estímulos controlados. | U13–U20 Kotlin y observación real posterior. |
| P1-08 | Reductor y notificaciones nativas Wear OS. | Emitir fixtures cortos/largos/concurrentes. | N01–N08 con oráculo de emisión válido. |
| P1-09 | Instrumentación receptora, contadores y correlación. | Generador de nuestra app iOS y registro de emisión. | J distingue programado/emitido/recibido/presentado. |
| P1-10 | Reconexión watch, generaciones y Service Changed. | Ciclo iOS/restauración propia y estímulos de radio. | R01–R04 en veinte ciclos controlados. |
| P1-11 | Ejecutar lado watch y recoger evidencia. | Ejecutar lado iPhone y permisos negativos. | J firma informe G1; BLOCKED no equivale a PASS. |
| P1-12 | APK, checksum e instalación Windows/watch. | Build iPhone con firma/canal disponible y guía Mac. | J entrega dos apps compatibles, restricciones y revisión exacta. |

## P2 — Fiabilidad y canal propio, 8 tareas

| ID | Windows/Kotlin | Mac/Swift | Integración y criterio |
|---|---|---|---|
| P2-01 | Propietario serial del estado y filtro por generación. | Sin duplicar estado ANCS; serializar su transporte propio donde aplique. | U19 y callbacks tardíos; responsabilidades distintas. |
| P2-02 | Recuperación GATT/ANCS, revocación y Service Changed. | Restauración del transporte iOS y permisos. | R01–R12; lifecycle de ambas plataformas. |
| P2-03 | Backlog silencioso, coalescing y avisos nuevos. | Emitir avisos antes/durante/después de reconectar. | N09–N14; sin gracia fija de pérdida. |
| P2-04 | Editor del contrato y codec/framer Kotlin. | Revisión de formato y codec/framer Swift. | F01–F12 en ambos sentidos y mismo conjunto de fixtures. |
| P2-05 | Identidad/Keystore, sesión/replay Kotlin. | Identidad/Keychain, sesión/replay Swift. | ADR de seguridad y vectores cruzados; sin datos sensibles antes. |
| P2-06 | DB/outbox/resultados y migraciones watch. | Persistencia de pares/ajustes/resultados iOS. | Q07–Q09: crash/reenvío/migración en cada almacenamiento. |
| P2-07 | Acciones ANCS, flags y etiquetas reales. | Estímulos y observación del efecto iPhone; sin «reply API» ficticia. | A01–A07 según capacidad; estado incierto explícito. |
| P2-08 | Métricas watch y baseline de batería. | Métricas iPhone y condiciones del ensayo. | J campaña 24/72 h y gate G2. |

## P3 — UX y multimedia, 7 tareas

| ID | Windows/Kotlin | Mac/Swift | Integración y criterio |
|---|---|---|---|
| P3-01 | Material 3/Compose, accesibilidad y pantalla redonda. | Sin UI watch. | UI01–UI04 en reloj real. |
| P3-02 | Estilos, agrupación, silencio y aplicación local de privacidad. | Ajustes iPhone que se acuerde sincronizar. | N09–N14 y política consistente por origen. |
| P3-03 | Tile/complication y navegación/restauración. | Sin código. | UI05–UI07 watch. |
| P3-04 | Adaptador AMS, capacidades y comandos. | Reproductores/estímulos iPhone, sin servidor multimedia Swift. | M01–M07 físicos. |
| P3-05 | Atributos truncados, recuperación AMS y límites. | Cambios de pista/app/estado para campaña. | M08–M10; sin polling permanente. |
| P3-06 | Startup/jank release y perfiles cuando ayuden. | Sin código watch. | Treinta arranques y trazas comparables. |
| P3-07 | Publicar estado propio que el canal permita. | Ajustes/diagnóstico SwiftUI y permisos por función. | UI08; estado no comprobado cuando falta evidencia ANCS. |

## P4 — IA texto/dictado, 8 tareas

| ID | Windows/Kotlin | Mac/Swift | Integración y criterio |
|---|---|---|---|
| P4-01 | ADR red/proveedor y viabilidad auth en watch. | Evaluar configuración/login iPhone solo si se requiere. | Ruta soportada, elegibilidad y coste explícitos. |
| P4-02 | Proveedor ficticio y dominio de conversación. | Sin código salvo mock de configuración acordado. | I01–I05 sin red real. |
| P4-03 | Parser SSE incremental y eventos terminales. | Sin duplicar el cliente IA watch. | I06–I09 Kotlin. |
| P4-04 | Proveedor oficial y credenciales protegidas watch. | Configuración/autorización/revocación si el ADR la asigna al iPhone. | Smoke controlado; sin clave maestra incrustada. |
| P4-05 | Dictado/teclado/TTS/cancelación watch. | Sin audio relay iPhone. | I10–I12, español y permisos reales. |
| P4-06 | Historial propio, retención/borrado y cambio de cuenta. | Borrar/configurar datos iOS propios si existen; sin historial ChatGPT supuesto. | I13 y separación de cuenta. |
| P4-07 | Selección/resumen/traducción de aviso autorizado. | Ajuste/consentimiento si se comparte por contrato. | I14 y Q11; texto del aviso no autoriza herramientas. |
| P4-08 | Errores de red, presupuesto y estado offline watch. | Errores/revocación de auth iOS si participa. | 401/403/429/5xx y parciales sin falso éxito. |

## P5 — Voz en vivo, 6 tareas

| ID | Windows/Kotlin | Mac/Swift | Integración y criterio |
|---|---|---|---|
| P5-01 | Spike WebRTC/tamaño/energía en Watch7. | Sin cliente de audio iPhone obligatorio. | Sesión física y build reproducible antes de ampliarlo. |
| P5-02 | Servicio opcional separado: auth, tokens efímeros, cuotas y tests; lenguaje por ADR. | Consumir configuración si corresponde; no alojarlo dentro de iPhone por suposición. | El servicio es tercer componente, no parte del par BLE; sin despliegue/coste supuesto. |
| P5-03 | Audio/foco/rutas/micrófono watch. | Estímulos externos de interrupción cuando apliquen. | V01–V04 y limpieza de recursos. |
| P5-04 | Buffers acotados, interrupción y cancelación. | Sin código de buffers watch. | V05–V07, sin audio anterior en sesión nueva. |
| P5-05 | Métricas, pérdida de Internet y cierre de sesión. | Sin proxy de conectividad obligatorio. | V08–V10 y ANCS sigue activo. |
| P5-06 | Controlador/guardas/esquemas de herramientas y presupuesto. | Ejecutores Apple tipados solo de capacidades aprobadas y disponibles. | Q11–Q12, confirmación/idempotencia; sin shell arbitrario. |

## P6 — Agente Mac, 7 tareas

| ID | Windows/Kotlin | Mac/Swift | Integración y criterio |
|---|---|---|---|
| P6-01 | Requisitos del consumidor y compatibilidad de esquema. | Investigar captura pública e integraciones; ADR de alcance. | Sin prometer lectura universal de notificaciones Mac. |
| P6-02 | Sin agente Windows ni app Mac Kotlin. | App macOS de barra de menú e identidad. | Inicio/parada y build Mac propios. |
| P6-03 | Cliente TLS/LAN, descubrimiento y confianza en watch. | Servidor TLS/LAN/enrolamiento Mac. | L01–L04 con identidad fijada; Bonjour no autentica. |
| P6-04 | Mostrar eventos válidos recibidos por cliente. | CLI/IPC local protegido y emisores sintéticos. | Origen/ID/caducidad definidos en contrato. |
| P6-05 | Reductor de procedencia/dedupe en reloj. | IDs estables y rutas de integración Mac. | L05–L07; no comparar solo texto. |
| P6-06 | UI de acción tipada y estado/consulta de resultado. | Ejecutor local allowlist e historial de resultados. | L08–L10, sin efecto doble al perder ACK. |
| P6-07 | Reconexión y estado offline watch. | Sleep/wake, cambio LAN y cola acotada Mac. | J verifica independencia iPhone→watch. |

## P7 — Salud y contactos, 4 tareas

| ID | Windows/Kotlin | Mac/Swift | Integración y criterio |
|---|---|---|---|
| P7-01 | Health Services/sensores y permisos por métrica watch. | Permisos/capacidad de lectura/escritura Apple por tipo. | S01, sin afectar ANCS ante denegación. |
| P7-02 | Normalización/outbox duradera/reintento con sync IDs. | Ledger/HealthKit/syncVersion y ACK tras efecto guardado. | S02–S06, crash después de guardar antes de ACK. |
| P7-03 | Receptor/almacén snapshot y commit transaccional watch. | Contacts, conjunto autorizado, lotes y tombstones. | S07–S10, snapshot incompleto no reemplaza anterior. |
| P7-04 | Retención/borrado/UI y procedencia watch. | Retención/borrado/UI y procedencia iPhone. | Q08–Q12, alcance de borrado documentado. |

## P8 — Prototipo final, 8 tareas

| ID | Windows/Kotlin | Mac/Swift | Integración y criterio |
|---|---|---|---|
| P8-01 | Congelar soporte watch y versiones Kotlin/protocolo. | Congelar soporte iPhone/Mac y versiones Swift/protocolo. | J matriz real de capacidades probadas. |
| P8-02 | Regresión JVM/Android y parte watch HIL. | Regresión Swift/iOS/macOS y parte Apple HIL. | J catálogo completo con estados y exclusiones. |
| P8-03 | Perfil release/energía watch y trazas. | Perfil release/energía iPhone/Mac y condiciones. | J AB/BA y siete días de uso; sin depuradores en batería. |
| P8-04 | Migración watch y contraparte Swift anterior. | Migración Apple y contraparte Kotlin anterior. | J actualización parcial, incompatibilidad y degradación explícita. |
| P8-05 | Dependencias/avisos/secretos Android y servicio; índice conjunto. | Dependencias/avisos/secretos y privacidad Apple. | SBOM/provenance por plataforma y revisión integrada. |
| P8-06 | Firma APK, checksums, instalación/backup propio. | Firma/canal de prueba iPhone y agente Mac cuando aplique. | J instalación sin herramientas BLE de laboratorio. |
| P8-07 | Observar/corregir fallos del uso watch. | Observar/corregir fallos del uso iPhone/Mac. | J ensayo del usuario sin depuradores. |
| P8-08 | Evidencia Windows y edición del índice final. | Evidencia Apple y revisión del informe. | J G8, demo reproducible, limitaciones y backlog posterior. |

## Quién implementa y quién ejecuta las pruebas

Esta agrupación cubre los 148 casos numerados del catálogo más cinco E2E. No autoriza a marcar aprobado un grupo entero; cada caso conserva su estado. En Q/H/R/S hay casos que solo aplican a ciertas fases: se registra motivo y gate correspondiente.

| Casos | Dueño de automatización/código | Estímulo y verificación conjunta |
|---|---|---|
| U01–U20 (20) | K: parser ANCS, cola, concurrencia y reloj falso JVM. | Expectativas desde especificación; no requiere iPhone para unitarios. |
| F01–F12 (12) | K y S: cada codec ejecuta el conjunto común completo. | Intercambio cruzado de fixtures; backpressure físico adicional donde aplique. |
| H01–H09 (9) | K: instalador/lifecycle watch. S: onboarding, firma y lifecycle iOS. | J sobre ambos dispositivos; distinguir cierre normal/force-quit. |
| N01–N14 (14) | K: reductor/presentación/métricas. S: emisor sintético y guiones iPhone. | J corrobora emisión y estado final; Enfoque no equivale automáticamente a pérdida. |
| R01–R12 (12) | K: recuperación de sesión watch. S: recuperación propia y estímulos iOS. | J alcance, radios, reinicios, Service Changed y 72 h. |
| A01–A07 (7) | K: elegibilidad/comandos/estado ANCS y acciones propias. | S provoca avisos y observa efecto iPhone; A07 involucra receptor del comando propio. |
| M01–M10 (10) | K: AMS. | S controla apps reproductoras; J valida estado/energía. |
| UI01–UI08 (8) | K: UI01–UI07. S: UI08 en ajustes iPhone; K también verifica sus errores visibles. | Físico, accesibilidad y release; no sustituir por capturas de emulador. |
| I01–I14 (14) | K: conversación/SSE/dictado/TTS/proveedor/historial. S: auth/config si participa. | Smoke real acotado y autorizaciones, además de proveedor fake. |
| V01–V10 (10) | K: voz watch y servicio opcional. S: ejecutores Apple si entran en herramientas. | J hardware/audio/red; no requerir relay de audio iPhone. |
| L01–L10 (10) | S: agente/servidor/CLI/ejecutor. K: cliente/UI/confianza. | J identidad, redes, sleep/wake, resultados inciertos. |
| S01–S10 (10) | K: sensores/outbox/snapshot. S: HealthKit/ledger/Contacts. | J crash/reenvío/permisos parciales y revocación. |
| Q01–Q12 (12) | Cada lado en su superficie y almacén; K también servicio opcional. | J identidad/replay/efectos, revisión de diseño y privacidad. |
| E2E-01–E2E-05 (5) | K y S aportan sus scripts y evidencia. | J ejecuta guiones completos originales; no se emulan como aceptación física. |

## Cómo abrir tareas sin duplicar trabajo

Ejemplo P2-04:

1. `P2-04.K1`: Windows propone esquema, cabecera y fixtures en una rama de contrato.
2. `P2-04.S1`: Mac revisa capacidad CoreBluetooth, tipos Swift y errores; propone correcciones.
3. `P2-04.J1`: congelar versión e incorporar ambos comentarios al mismo commit de contrato.
4. `P2-04.K2` y `P2-04.S2`: codecs nativos en paralelo, sin tocar la definición congelada por separado.
5. `P2-04.J2`: ejecutar F01–F12 cruzados, corregir y registrar compatibilidad antes de habilitar la función.

Aplicar el mismo patrón a identidad, LAN, salud y contactos. La coordinación no implica que un Codex tenga que escribir el código del otro: cada interfaz se aprueba una vez y cada lado desarrolla su adaptador.
