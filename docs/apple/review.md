# Revisión de arquitectura y plan

Fecha de presentación: 16 de octubre de 2026, America/Mexico_City. Los documentos originales usan 17 de octubre en UTC. Revisión del paquete completo de documentación, sus 60 tareas y el catálogo de 148 casos más cinco E2E. Los seis archivos coinciden en tamaño y SHA-256 con `Doc/manifest.json`.

## Entendimiento del producto

El primer producto son dos apps nativas. El sistema iOS entrega ANCS directamente al consumidor Kotlin del Watch7; SwiftUI facilita autorización, configuración y diagnóstico. El canal BLE propio es una ruta adicional, no el transporte de los avisos de terceros. Música usa AMS del sistema; IA necesita Internet del reloj y autorización específica; Mac, voz y salud/contactos corresponden a fases posteriores.

El checkout recibido contenía únicamente documentación, sin repositorio Git, contratos materializados, app Kotlin, paquete Swift ni proyectos Xcode. Se inició una rama local `feat/ios/g0-p1`, sin remoto ni commit integrado. No se presupone que el equipo Windows ya entregó un APK.

## Dictamen

La arquitectura es una base razonable para el objetivo inicial en México: evita depender de un Android intermediario, del Mac o de un backend para avisos. Mantener interfaces nativas y adaptadores pequeños reduce las dependencias del experimento. La decisión que más valor aporta ahora es comprobar el enlace ANCS físico antes de invertir en funciones adicionales.

No existe evidencia para afirmar que el camino A sea el mejor en energía o compatibilidad. Es una **hipótesis preferida**: watch anuncia el servicio propio e iPhone central; el watch consume ANCS como cliente GATT. A y B se resuelven con ADR-001 y hardware. Una revisión documental no puede certificar firmware, suspensión ni autonomía.

## Decisiones comprobadas y ajustes

| Tema | Evaluación | Consecuencia práctica |
|---|---|---|
| ANCS directo | La especificación pública define autorización, servicios cambiantes y estado limitado a la sesión. | Mantener el motor en Kotlin. No crear un lector de avisos ajenos en Swift. |
| Selector AccessorySetupKit | **Faltaba precisión operativa:** UUID de servicio solo no satisface todos los criterios de descubrimiento. Apple exige también un identificador compatible, como nombre anunciado o datos enmascarados. | Se propone nombre anunciado `WristBridge`, declarado también en Info.plist. Windows debe confirmarlo antes de H02; no se modifica el contrato común unilateralmente. |
| Permisos Bluetooth | Apple documenta autorización específica del accesorio sin el prompt general de Bluetooth al conectarlo por ASK. | CoreBluetooth solo se crea después de autorización ASK; sin escaneo general ni permiso Bluetooth adicional. Se declara únicamente el nombre/servicio utilizados; ningún Company ID ajeno. |
| Bond y ANCS | `.bluetoothPairingLE` solicita el soporte LE en el descriptor; no certifica permiso ANCS, cifrado observado ni suscripciones del watch. | UI separa autorización, enlace, servicio y avisos sin comprobar. No se usa bridging a Bluetooth clásico. |
| Recuperación | TN3115 condiciona el relanzamiento a operaciones pendientes y eventos concretos, con reglas específicas para ASK desde iOS 26. | Restoration ID y callbacks preparados; cierre forzado, suspensión y terminación del sistema tienen pruebas distintas. No se promete ejecución permanente. |
| Reenvío moderno de Apple | Accessory Notifications permite desarrollo/prueba en cualquier región, pero restringe instalaciones de clientes a UE con cuenta de región UE. | No sustituye la arquitectura base destinada a México. La regla no debe describirse como prohibición universal de desarrollar fuera de UE. |
| Emisor iOS | UserNotifications programa exclusivamente avisos propios. Una solicitud aceptada no prueba entrega visible ni evento ANCS. | Contadores y exportación distinguen programación, callback foreground y snapshot entregado. IDs sintéticos estables, Unicode y cuerpo largo. |
| Formato P2 | El documento compartido reserva UUIDs, pero **no congela** propiedades GATT, tipos exactos ni framing. | Paquete Swift inicial solo contiene identificadores, estado local y fixtures. No se anuncia un codec interoperable ni se leen/escriben características propias. |
| Seguridad | Bond no sustituye identidad/autenticación/idempotencia de efectos propios. | Canal sensible desactivado. No se incorpora criptografía de un repositorio como si estuviera auditada. |
| Versiones | La referencia de iOS del plan no acredita la versión del teléfono. La CLT instalada reporta SDK macOS 27.0 y Swift 6.4; no hay SDK iOS seleccionado. | Deployment propuesto iOS 26.0; versión/build del teléfono, Xcode y SDK iOS quedan pendientes. No llamamos estable ni compatible a una combinación sin compilar. |
| Catálogo QA | La matriz asigna correctamente tareas y separa pruebas unitarias, UI y hardware. | Se registran estados por caso; ni el paquete Swift ni el simulador aprueban H05 o G1. |
| Reutilización | Los hallazgos de forks son referencias estáticas del informe. | Implementación Apple original desde APIs públicas; no se copió código. No se reauditaron los ocho repositorios ni sus dependencias transitivas, porque no se incorporaron. |
| Fases futuras | Separar IA/Mac/salud limita el riesgo y permisos del MVP. | Ninguna función posterior se habilita por anticipación. Proveedores, elegibilidad, licencias y costes se revalidarán al abrir sus tareas. |

## Alternativas y prioridad

1. Conservar A como experimento de primera elección: autorización ASK y conexión central del iPhone encajan con el flujo documentado de Apple.
2. Probar B solo si existe una falla concreta de A. Anunciar un periférico desde iPhone cambia las restricciones de descubrimiento y segundo plano; no es una alternativa garantizada.
3. Evitar por ahora frameworks BLE externos en Swift, un backend y un relay iPhone de IA: no resuelven el riesgo ANCS y añaden piezas al experimento.
4. Mantener iOS 26 mínimo para esta primera pareja es una reducción de matriz propuesta por el plan, no una necesidad de ASK, disponible desde iOS 18. Ampliar soporte solo con necesidad y pruebas.
5. Las jornadas del plan siguen siendo estimaciones orientativas. No se pueden ratificar como calendario sin enlace probado, firma y revisión integrada del watch.

## Limitaciones actuales que impiden la validación completa del sistema

- Falta Xcode completo: no se ejecutó el compilador contra SDK iOS, no se renderizó UI ni se comprobó la firma.
- El usuario confirma iPhone y Watch7 disponibles. Esta sesión no ha obtenido su versión/build, autorizado instalación ni observado su radio; disponibilidad no equivale a prueba ejecutada.
- Falta contraparte Kotlin y commit base compartido. Confirmar nombre anunciado y comportamiento mínimo de servicio antes del picker real.
- No hay datos de energía, latencia, reconexión, permisos ANCS ni continuidad con teléfono bloqueado. ADR-001 y G1 están pendientes.
- El spike central implementa reconexión voluntaria y restauración básica; no equivale a la recuperación completa P2 ni a 19/20 recuperaciones en 30 segundos.
- La app no automatiza Modified/Removed por APIs de avisos propios ni prueba otras apps. La campaña describe estímulos y observación conjunta; no se infiere cobertura universal desde diez avisos.

## Fuentes primarias verificadas para esta implementación

- [ANCS: especificación](https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html).
- [AccessorySetupKit: descubrimiento y configuración](https://developer.apple.com/documentation/accessorysetupkit/discovering-and-configuring-accessories).
- [Muestra Apple de autorización Bluetooth](https://developer.apple.com/documentation/accessorysetupkit/setting-up-and-authorizing-a-bluetooth-accessory).
- [Opciones ASK](https://developer.apple.com/documentation/accessorysetupkit/asaccessory/supportoptions).
- [Nombres Bluetooth declarados](https://developer.apple.com/documentation/bundleresources/information-property-list/nsaccessorysetupbluetoothnames).
- [Servicios Bluetooth declarados](https://developer.apple.com/documentation/bundleresources/information-property-list/nsaccessorysetupbluetoothservices).
- [TN3115: reglas de restauración](https://developer.apple.com/documentation/technotes/tn3115-bluetooth-state-restoration-app-relaunch-rules).
- [Accessory Notifications: alcance regional](https://developer.apple.com/documentation/accessorynotifications).
- [Xcode 26: SDKs y requisitos](https://developer.apple.com/documentation/xcode-release-notes/xcode-26-release-notes). Es una referencia mínima, no una selección del Xcode instalado.
- [Motivos de uso de APIs de privacidad](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype).
