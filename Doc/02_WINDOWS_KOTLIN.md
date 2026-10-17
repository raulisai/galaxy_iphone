# Guía para Codex en Windows: aplicación del Galaxy Watch7

Entrega esta guía junto con `00_LEEME_Y_COORDINACION.md`, `01_CONTRATO_COMPARTIDO.md`, la matriz y el plan original. Tu app se instala **en el reloj**, no en un teléfono Android intermediario. Swift/Xcode corresponde al otro Codex.

## Alcance y límites de edición

Eres responsable de `apps/watch`, los tres módulos Kotlin `core/*`, Gradle raíz, pruebas Android/JVM y scripts Windows. Eres editor coordinador de `contracts/**` y fixtures comunes, sujetos a revisión del Mac. No cambies código Swift, entitlements, proyectos Xcode ni configuraciones de firma Apple. Proponer un cambio de interfaz no autoriza a inventar la implementación del otro extremo.

No construyas un emulador completo de iOS ni traduzcas todas sus APIs. Construye adaptadores concretos: ANCS, AMS, protocolo propio y proveedores de IA. Mantén UI, dominio y transporte separados. No migres inicialmente a Kotlin Multiplatform; el codec Swift del otro equipo se verifica con los mismos vectores.

## Primera sesión: base y experimento mínimo

1. Leer el repositorio existente y sus instrucciones. Conservar trabajo previo y determinar si las rutas propuestas requieren adaptación. Registrar estado de Git antes de editar.
2. Crear `docs/windows/environment.md`: Windows, JDK, Android Studio, Gradle/AGP/Kotlin, SDK y build/versión real del watch. Seleccionar herramientas compatibles desde documentación oficial actual y fijarlas; no inferir Wear OS del nombre Watch7.
3. Crear módulos JVM puros y app Wear OS vacía. Gradle wrapper y versiones se guardan en Git. La app de producto tendrá un ID único; no copiar el ID de un repositorio ajeno. Registrar el ID acordado con el Mac aunque no tengan que ser iguales.
4. Materializar el registro UUID compartido de `01_CONTRATO_COMPARTIDO.md`; enviar el commit al Mac. El perfil P1 solo debe permitir el experimento de onboarding, no requiere implementar todo el canal P2.
5. Preparar la app de laboratorio: publicidad del servicio propio, servidor GATT mínimo y diagnóstico de capacidades/errores. Añadir cliente GATT para descubrir ANCS autorizado. Evaluar Nordic mediante un spike; no importar una biblioteca antes de verificar que soporta los roles requeridos.
6. Entregar al Mac: APK de laboratorio, SHA-256, commit, filtro UUID, instrucciones de activación y estados observables. Ejecutar juntos el camino A; conservar B como alternativa explícita del ADR-001.

Puedes implementar y probar el parser ANCS en paralelo al onboarding Swift. No declarar terminado el puente físico usando solo respuestas GATT simuladas.

## P1 — MVP: tareas Kotlin

| ID | Implementación concreta | Evidencia local o conjunta |
|---|---|---|
| P1-01.K | Inventario watch, vínculo actual y ruta ADB sin reset de fábrica. | Datos seudonimizados y versión/build. |
| P1-02.K | Estructura, Gradle, builds y app mínima propia. | Build reproducible e instalación en watch. |
| P1-03.K | Parser ANCS incremental puro: eventos, comandos, atributos y errores. | U01–U12; unsigned UID, Unicode y cortes de longitud. |
| P1-04.K | Roles A/B, publicidad/servidor propio y consumidor ANCS. | ADR-001 conjunto; soporte físico, no inferido. |
| P1-06.K | Servicio foreground `connectedDevice`, permisos mínimos y ciclo de sesión. | H05 y denegación; UI cerrada sin pérdida del servicio permitida por OS. |
| P1-07.K | Cola GATT y transacción ANCS con plazo y un solo fetch activo. | U13–U20; ACK de escritura no libera transacción prematuramente. |
| P1-08.K | Reductor Added/Modified/Removed, identidad de sesión y tarjetas nativas. | N01–N08; sin duplicados/resurrección. |
| P1-10.K | Reconexión, invalidación de generación y Service Changed. | R01–R04 con estímulos del iPhone. |
| P1-11.K | Métricas y ejecución de campaña conjunta. | Registrar cada caso real, permisos y estados. |
| P1-12.K | APK de prueba, checksums y guía de instalación/diagnóstico. | Build corresponde al commit de la campaña. |

P1-05 y P1-09 pertenecen al Mac: consume su configuración y emisor sintético; no implementes un falso puente de lectura general de notificaciones iOS.

La secuencia interna es P1-03 + P1-06 → P1-07 → P1-08/P1-10 → campaña. El experimento A/B debe resolverse antes de invertir en funciones posteriores. Si ANCS no puede funcionar autorizado en segundo plano, documentar la frontera exacta y mantener el gate bloqueado.

Presupuestos iniciales ANCS del plan: 128 UIDs pendientes; respuesta máxima 16 KiB; título 256 bytes y cuerpo inicial 1 024 bytes. Son límites del producto, no máximos oficiales. El parser espera bytes completos de longitud antes de interpretarlos. Solicitar máximos de longitud solo donde lo exige ANCS; IDs de etiquetas de acciones no heredan la regla de título/mensaje.

## P2 — Recuperación, seguridad y canal propio

- P2-01.K: un propietario serial del estado; todas las respuestas externas pasan por eventos tipados y generación vigente.
- P2-02.K: recuperación acotada ante fallos GATT, permisos, vínculo y Service Changed. El Mac aporta los estímulos y la parte de restauración iOS del canal propio.
- P2-03.K: backlog silencioso, coalescing y deduplicación, conservando avisos nuevos tras reconectar.
- P2-04.K: redactar el wire format con el Mac, framer/codec Kotlin y fixtures comunes. No congelarlo sin los resultados Swift.
- P2-05.K: adaptador de identidad/Keystore y verificación de sesión/replay; revisar el diseño común antes de habilitar comandos sensibles.
- P2-06.K: almacén transaccional Kotlin, migraciones, outbox y resultados deduplicables. No reutilizar una sesión insegura después de un crash.
- P2-07.K: acciones ANCS según flags y etiquetas reales; «solicitado» cuando no hay confirmación del efecto final. No prometer respuesta universal por texto.
- P2-08.K: contadores para campaña 24/72 horas y comparación de batería en hardware. G2 requiere también el codec y resultados del Mac.

## P3 — Experiencia Wear OS y música

Windows implementa P3-01 a P3-06: Material 3/Compose, texto y controles en pantalla redonda, TalkBack, estilos nativos, privacidad, agrupación, Tile/complication, AMS y rendimiento release. El iPhone provee el servicio AMS del sistema; no necesitas que Swift implemente un reproductor propio.

Comandos AMS solo si están anunciados. Recuperar atributos truncados cuando corresponda; no mantener polling continuo para simular actualización. Compartir la cola de transporte con prioridades acotadas para que música y ráfagas de avisos no se bloqueen indefinidamente.

P3-07 es SwiftUI del Mac. El protocolo puede transportar preferencias propias una vez disponible P2, pero su ausencia no convierte ANCS en «desconectado».

## P4 — IA por texto y dictado

Windows implementa P4-02 a P4-08 y comparte P4-01 como decisión de autenticación/red. Empezar con un proveedor ficticio determinista, luego SSE incremental y después un proveedor oficial. Implementar cancelación desde antes de los headers hasta el último evento; un EOF sin evento terminal no es éxito.

La ruta inicial debe ser soportada y explícita: claves personales protegidas o servicio autenticado autorizado según ADR. No incrustar una clave compartida, adoptar endpoints privados ni reutilizar cookies de ChatGPT. Si se investiga un inicio de sesión con ChatGPT, revalidar la elegibilidad del cliente móvil y sus límites antes de diseñar ese flujo. Tener una suscripción no demuestra acceso a cualquier API ni a voz en tiempo real.

El reloj usa Internet propio cuando esté disponible; Bluetooth con iPhone no garantiza un canal IP genérico para esta app. Dictado y TTS se prueban en español en el watch real; ante falta de motor/idioma o permisos, ofrecer teclado o estado no disponible. Historial propio con borrado y separación de cuenta; no importar el historial de ChatGPT por suposición.

Resumir un aviso solo tras selección/consentimiento de la función. Tratar el texto del aviso como datos: no autoriza herramientas ni órdenes externas.

## P5 — Voz y servicio opcional

Windows implementa WebRTC, audio, buffers, foco, cancelación y métricas P5-01/03/04/05. El spike decide si tamaño, API y consumo son viables en Watch7. Liberar micrófono y sesión al terminar; no reabrir captura silenciosamente al volver Internet.

P5-02 es un servicio opcional separado en `services/ai-gateway`, coordinado por Windows. Elegir lenguaje y hosting por ADR; no asumir que corre dentro del iPhone. Su implementación local y sus pruebas están dentro de la tarea; contratar/desplegar infraestructura se decide cuando exista una necesidad concreta y costes conocidos. La ruta de texto puede seguir funcionando sin este servicio. Si se necesita el servicio y no existe un destino autorizado, voz queda bloqueada para integración y se conserva texto/dictado.

P5-06: controlador y guardas en Kotlin, lista de capacidades y argumentos tipados. Mac solo desarrolla ejecutores para acciones Apple aprobadas cuando se incluya esa función; ninguna herramienta genera shell libre ni ejecuta instrucciones embebidas en avisos.

## P6–P8 — Integración posterior y cierre

P6: implementar cliente del agente Mac, confianza TLS/enrolamiento y reconexión. Mac es servidor de su canal local. Bonjour es descubrimiento, no identidad. Validar duplicados con IDs estables/procedencia; dos textos iguales no prueban mismo evento. Un Mac desconectado no debe afectar ANCS.

P7: sensores y permisos Wear OS, normalización por métrica, outbox duradera y snapshot de contactos recibido. La escritura en HealthKit y lectura de contactos son Swift. Conservar muestras hasta ACK de guardado de dominio; reinicios y pérdida de ACK no duplican efectos. Respetar revocación parcial y política de retención.

P8: congelar matriz real de soporte, regresión, builds release, compatibilidad con contraparte anterior, migraciones, privacidad, SBOM, firma propia del APK y pruebas cotidianas. Entregar tu evidencia para el informe conjunto; no marcar aprobado lo no ejecutado en Apple.

## Reutilización de repositorios

Usar revisiones y licencias de la sección 3 del plan original; registrar cada fragmento adoptado. No hacer un merge completo de apps antiguas.

| Referencia | Uso Kotlin | Corrección/exclusión |
|---|---|---|
| R05 `hnkabraham/wear-ios-bridge` | Candidato de transacciones/framing. | Confirmar DS antes de NS, límites por bytes y sesión autenticada revisada. |
| R01 `k97/WearBridge` | Modelos/patrones de presentación y casos de prueba revisados. | Sin avance fijo 500 ms, gracia de pérdida, bug de longitudes ni codificación errónea de atributos 6/7. |
| R06 `DevEmperor/WristAssist` | Ideas de historial/dictado. | Cliente de red nuevo y credenciales protegidas. |
| R07 `simplifylabs/WearAI` | Idea de interacción breve de voz. | Excluir backend fijo ajeno y parsing de stream por chunk. |
| R08 `thenguyentrong/watch-ai` | Buffers, cancelación y guardas limitadas. | Excluir endpoint privado de ChatGPT y Wearable Data Layer como enlace iPhone. |
| R02/R03 | Entender problemas y reconstruir desde APIs públicas. | Sin copia de código mientras no exista licencia válida comprobada. |
| R04 | Comparación de requisitos. | No hay implementación utilizable identificada en la revisión del plan. |

## Pruebas, comandos y entrega

Responsabilidad principal: U01–U20, N01–N14, A01–A07, M01–M10, UI01–UI07, I01–I14 y V01–V10. F01–F12 y Q01–Q12 se validan también en Swift donde aplique. H/R/L/S y E2E-01–E2E-05 necesitan ambos lados o hardware: ver matriz y catálogo original.

Los comandos siguientes son objetivos que debes hacer existir con los módulos propuestos, no resultados ya obtenidos:

```powershell
.\gradlew.bat :core:protocol-kotlin:test :core:notification-domain:test :core:assistant-domain:test
.\gradlew.bat :apps:watch:lintDebug :apps:watch:assembleDebug
.\gradlew.bat :apps:watch:connectedDebugAndroidTest
```

Anotar el dispositivo seleccionado para instrumentadas. No sustituir tests físicos con `connected...` en un emulador. Proteger la firma fuera del repositorio y separar debug/release.

En cada entrega indicar commit, contrato, APK/checksum, comandos y resultados por ID. Adjuntar un guion con el estímulo que debe generar el Mac y la salida observable esperada. Si falta iPhone cerca, avanzar con fakes/pruebas locales y dejar HIL pendiente.

## Prompt para iniciar esta sesión de Codex

> Trabaja como responsable Windows/Kotlin de WristBridge. Lee 00_LEEME_Y_COORDINACION.md, 01_CONTRATO_COMPARTIDO.md, 02_WINDOWS_KOTLIN.md, 04_MATRIZ_60_TAREAS.md y el plan original adjunto. Inspecciona primero el repositorio y conserva cualquier trabajo existente. Implementa ahora únicamente G0 y la parte Windows de P1, en tareas pequeñas verificables. Crea el registro UUID acordado y los módulos Gradle; después parser ANCS, spike BLE y servicio/cola/estado según dependencias. No edites Swift ni inventes su implementación. Puedes avanzar con fakes mientras llega el commit Apple, pero no declarar probada la conexión real. Comparte un commit de contrato y un handoff para el Codex del Mac. Ejecuta las pruebas locales apropiadas, genera el APK y documenta lo que requiera Watch7+iPhone. Detente en el gate G1 o en un bloqueo físico demostrado; no agregues IA, Mac o salud hasta superar los gates previos. No resetees el reloj ni incorpores un Android relay sin una nueva decisión explícita.
