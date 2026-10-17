# WristBridge: reparto entre Windows y Mac

Versión 1.0 · 17 de octubre de 2026 · Documento de ejecución, todavía sin apps implementadas.

El resultado serán dos aplicaciones: una app Kotlin instalada en el Galaxy Watch7 y una app Swift instalada en el iPhone 15. El código Swift se desarrolla y compila en el **Mac**, utilizando Xcode. El código Kotlin se desarrolla en **Windows**, utilizando Android Studio/Gradle. La app de Mac que recibe alertas de la laptop será un tercer producto posterior, en P6; no hace falta para las notificaciones iPhone→reloj.

## Archivos y orden de lectura

1. Ambos Codex leen este archivo y `01_CONTRATO_COMPARTIDO.md`.
2. El Codex de Windows recibe `02_WINDOWS_KOTLIN.md`.
3. El Codex del Mac recibe `03_MAC_SWIFT.md`.
4. `04_MATRIZ_60_TAREAS.md` asigna todas las tareas del plan original, conservando sus identificadores.
5. `referencias/plan_ejecucion_galaxy_apple.md` contiene el plan completo, las fuentes, los repositorios y el significado exacto de los casos de prueba. Este reparto lo complementa. Ante conflicto de responsabilidad, prevalece este reparto; ante una limitación de plataforma, prevalecen la documentación pública comprobada y el resultado físico.

Las rutas descritas a continuación son rutas **futuras dentro del mismo repositorio**. Este paquete no contiene todavía ese repositorio ni crea una conexión entre tus computadoras.

## Responsabilidad por carpeta

| Carpeta o archivo | Responsable de escritura | Entrega |
|---|---|---|
| `apps/watch/**` | Windows/Kotlin | App Wear OS: BLE, servicio, UI, notificaciones, multimedia, IA y voz. |
| `core/protocol-kotlin/**` | Windows/Kotlin | Parser ANCS y codec del canal propio, con pruebas JVM. |
| `core/notification-domain/**` | Windows/Kotlin | Estado, coalescing, retiro y deduplicación de avisos. |
| `core/assistant-domain/**` | Windows/Kotlin | Conversación, streaming, cancelación y herramientas permitidas. |
| Gradle raíz, wrapper, catálogo de versiones, configuración Android | Windows/Kotlin | Build reproducible desde Windows. |
| `apps/ios/**` | Mac/Swift | App iPhone: onboarding, permisos, configuración, diagnóstico y canal propio. |
| `packages/WristBridgeProtocol/**` | Mac/Swift | Modelo/codec Swift del protocolo propio. No necesita duplicar el parser ANCS. |
| `apps/macos/**` | Mac/Swift, desde P6 | Agente de barra de menú, integraciones y servidor local. |
| Proyectos, schemes, entitlements, firma y configuración Xcode | Mac/Swift | Builds y ejecución en dispositivos Apple. |
| `services/ai-gateway/**` | Windows, como tarea de servicio separada en P5 | Componente opcional de tokens; lenguaje según ADR. No es Kotlin del reloj ni Swift del iPhone. |
| `contracts/**`, `test/fixtures/shared/**` | Windows como editor del contrato; revisión obligatoria del Mac antes de congelar | Esquema, UUID, vectores y compatibilidad comunes. Ningún cambio unilateral del formato. |
| `test/fixtures/ancs/**` | Windows/Kotlin | Vectores oficiales ANCS y casos de regresión. |
| `test/fixtures/ios/**` | Mac/Swift | Guiones de avisos sintéticos y configuración de emisor. |
| `test/hil/windows/**`, `docs/windows/**`, `evidence/<run>/windows/**` | Windows/Kotlin | ADB, resultados Android y documentación propia. |
| `test/hil/apple/**`, `docs/apple/**`, `evidence/<run>/apple/**` | Mac/Swift | Xcode, resultados iOS/macOS y documentación propia. |
| `docs/adr/**`, manifiestos conjuntos y resúmenes de gates | Un editor por cambio; Windows coordina | Decisión revisada por ambos, sin dos versiones contradictorias. |
| CI Android, CI Apple | Cada lado su workflow separado | Ambos se comprueban antes de integrar cambios de interfaz. |

Cada equipo registra sus dependencias y atribuciones en `docs/provenance/windows.md` o `docs/provenance/apple.md`. Windows compone el índice/SBOM de entrega sin sobrescribir el archivo del otro equipo.

## Qué cruza realmente entre las apps

La ruta de avisos generales es **sistema iOS → servicio ANCS → consumidor Kotlin del reloj → notificación local Wear OS**. La app Swift no tiene acceso general a las notificaciones de otras apps. Crear dos apps y conectarlas por Bluetooth no garantiza por sí solo esa ruta: hay que demostrar autorización, acceso a ANCS y funcionamiento en segundo plano en tu hardware.

La app Swift permite configurar el accesorio y, más adelante, intercambiar ajustes y datos propios. Música se implementa con AMS en Kotlin, consumiendo el servicio del iPhone. IA se ejecuta en el reloj con un proveedor soportado y conectividad propia; iPhone no será un intermediario obligatorio ni un proxy permanente.

Los dos equipos deben distinguir:

- Autorización del accesorio, vínculo Bluetooth y permiso de compartir notificaciones.
- Canal propio disponible y ANCS realmente listo.
- App cerrada normalmente, proceso eliminado por el sistema y cierre forzado por el usuario.
- Prueba en simulador y prueba real de Bluetooth entre Watch7 e iPhone.

## Secuencia de arranque y dependencias

| Hito | Windows entrega | Mac entrega | Condición para continuar |
|---|---|---|---|
| G0: base compartida | Estructura Gradle, módulos vacíos, inventario watch, registro UUID y fixtures iniciales. | Proyecto iOS, paquete Swift, inventario iPhone y revisión del perfil BLE. | Mismo commit base, ambas apps compilan y UUID coinciden. |
| Experimento de roles | Watch anuncia servicio propio; cliente ANCS y diagnóstico mínimo. | Picker/onboarding y cliente CoreBluetooth para camino A. | Evidencia física; ADR-001 elige A o B. |
| G1: MVP | Cola GATT, parser, estado, tarjetas y reconexión básica. | Onboarding funcional, emisor sintético y diagnóstico de permisos. | Avisos reales con iPhone bloqueado y ambas UI cerradas normalmente. |
| G2: fiabilidad y canal propio | Codec Kotlin, persistencia, identidad y recuperación. | Codec Swift, restauración, identidad y ajustes. | Vectores cruzados y revisión de seguridad aprobados. |
| G3–G5: experiencia/IA | UI reloj, AMS, texto, dictado y voz por etapas. | Ajustes y autenticación/configuración solo donde el ADR lo requiera. | Cada función pasa su gate; ANCS sigue independiente. |
| G6–G7: extensiones | Cliente Mac y sensores/almacenes watch. | Agente Mac, HealthKit y contactos del iPhone. | Permisos y efectos verificables, por capacidad. |
| G8: prototipo | APK firmado de prueba y evidencia Android. | App iPhone instalada mediante firma/canal autorizado; agente Mac si entra en alcance. | Regresión conjunta, versiones compatibles y limitaciones documentadas. |

G0 es un punto de coordinación añadido a este reparto; P1–P8 mantienen el alcance del plan original. Las cifras de jornadas del plan son esfuerzo aproximado, no promesa de calendario: dos Codex no eliminan dependencias ni el tiempo de pruebas físicas.

P6 (Mac) y P7 (salud/contactos) pueden avanzar desde G2 con responsables distintos; no tienen que esperar a voz. P4 requiere G2/G3 y P5 requiere G4. El prototipo base del plan cierra con G1–G4 y G6; voz G5 y salud/contactos G7 son extensiones que se aprueban o se excluyen explícitamente. El MVP G1 sí consta únicamente de las dos apps iniciales y los avisos directos.

## Trabajo paralelo con Git

Usar un único repositorio y un clon en cada computadora. Los repositorios externos estudiados son referencias; no crear dos forks divergentes como productos separados.

1. Windows inicia la base común y deja un commit identificable. Mac incorpora su proyecto Apple sobre esa base mediante una rama propia. Si ya existe un repositorio, conservarlo y adaptar las rutas, sin reinicializar ni borrar trabajo.
2. Ramas por tarea: `feat/watch/P1-03-ancs-parser`, `feat/ios/P1-05-onboarding`, `contract/P2-04-framing`. Cada Codex modifica su ámbito.
3. Compartir cambios mediante commits remotos o un `git bundle` si aún no hay remoto. No hace falta que ambos editen la misma carpeta de una unidad compartida.
4. Antes de una integración física, ambos usan una revisión integrada idéntica. Desactivar cambios locales que alteren protocolo durante la campaña.
5. Un cambio de contrato se revisa con ambos codecs y fixtures; se integra como una unidad compatible. Una app a medio actualizar no se considera evidencia de funcionamiento.
6. Cada entrega incluye commit, versión del contrato, comandos/checks ejecutados, fallos conocidos y tarea siguiente. No pegar credenciales, archivos de firma o notificaciones personales en el chat de traspaso.

Los Codex de máquinas diferentes no ven automáticamente la sesión ni los archivos del otro. La interfaz de coordinación es el repositorio, las guías y la evidencia. Las pruebas del enlace real necesitan que el iPhone y el reloj estén físicamente al alcance; las pruebas unitarias pueden avanzar por separado.

## Protocolo de integración física

Windows instala el APK en el watch mediante ADB, normalmente por Wi-Fi. Mac firma e instala la app iPhone con Xcode. Usar avisos sintéticos identificados por `runId` y `fixtureId`, observar qué notificaciones fueron efectivamente emitidas y guardar estados redaccionados de ambos lados.

Solo una configuración A/B del puente estará activa por campaña. No dejar un explorador BLE ni otra build de laboratorio compitiendo por el enlace. Para pruebas de batería, desconectar depuradores y desactivar ADB inalámbrico; registrar condiciones y comparar con una referencia controlada.

Las computadoras ayudan a desarrollar e instalar; el uso cotidiano iPhone→watch no debe requerir Windows, Mac, un teléfono Android intermediario ni un servidor. La configuración inicial de fábrica del watch es un asunto separado: no resetearlo para empezar estas pruebas.

## Regla de cierre

Una tarea puede estar implementada localmente y seguir bloqueada para integración. Usar `PASS`, `FAIL`, `BLOCKED`, `NOT_RUN` o `NOT_APPLICABLE` con motivo. Ningún Codex marca G1/G2/G8 aprobado porque su app compila o porque un simulador funciona. Si no hay acceso físico, entrega código, builds y el guion pendiente, y declara el gate pendiente.
