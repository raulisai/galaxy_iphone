# Handoff Apple · G0 y preparación P1

Fecha local: 16 de octubre de 2026, America/Mexico_City. Rama local `feat/ios/g0-p1`; repositorio recién inicializado, sin commit ni remoto. El paquete documental original permanece intacto. La revisión exacta de entrega se registra mediante `evidence/apple-initial/source-manifest.json`; no se afirma que exista un commit base compartido.

## Implementado

- Proyecto `apps/ios/WristBridge.xcodeproj`, scheme compartido WristBridge, target iPhone y target UI tests, paquete Swift local sin dependencias externas.
- Adaptador AccessorySetupKit: activación, recuperación de autorización única previa, selección, cancelación, reintento y retirada.
- Experimento A CoreBluetooth: conexión solo al accesorio autorizado, descubrimiento del servicio reservado, deadline de veinte segundos, desconexión, Service Changed y restauración básica. Cancelaciones se drenan antes de reutilizar el periférico. Reconexión voluntaria, sin bucle de escaneo/reintentos.
- SwiftUI con estados de autorización, canal propio, Bluetooth y ANCS siempre sin comprobar; diagnóstico exportable sin mensajes ni identificadores del par.
- Emisor propio con diez avisos por lote, IDs deterministas, Unicode/cuerpo largo, permiso pedido explícitamente, registro acotado persistente y exportación. Separación entre programación aceptada, callback y observación del centro.
- Canal sensible desactivado. Sin codec P2, transferencias propias, lectura ANCS Swift, IA, Mac ni HealthKit.
- Guiones HIL, instalación y CI manual preparado. No ejecutado en remoto.

## Estado por tarea

| ID | Entrega local | Pendiente/estado de aceptación |
|---|---|---|
| P1-01.S | Inventario Mac, Swift y SDK realizado; usuario confirma hardware disponible | Versión/build iPhone y firma: NOT_RUN. Xcode: BLOCKED. |
| P1-02.S | Proyecto, paquete y scheme creados; paquete y estructura verificados | Build iOS y ejecución: BLOCKED. G0 conjunto NO aprobado. |
| P1-04.S | Adaptadores ASK/central para A preparados | Nombre de anuncio a confirmar con Windows; radio/ADR-001: NOT_RUN. B no implementado por anticipación. |
| P1-05.S | Pantalla y flujo de autorización/cancelación/diagnóstico preparados | Provisional hasta decidir A/B; H02–H04 y UI en iOS: NOT_RUN. |
| P1-09.S | Generador/registro sintético implementado, plan puro probado | Programación/entrega iOS y recepción watch: NOT_RUN. |
| P1-10.S | Restauración básica y estímulos documentados | H06–H09/R01–R04: NOT_RUN. No aceptación de recuperación automática. |
| P1-11.S | Campaña preparada | BLOCKED por builds no integradas; G1 pendiente. |
| P1-12.S | Guía y configuración de firma local preparadas | Firma/instalación/binario: BLOCKED. No existe IPA instalable. |

## Pruebas

- Paquete: ocho pruebas, con dieciséis variantes parametrizadas distribuidas en tres de ellas, PASS con la adaptación CLT documentada. Cubren estado honesto, revocación, cancelar/reintentar/detener, límites de fixtures, IDs/tiempos, Unicode y metadata.
- Estructura: hashes originales, UUIDs, Info.plist, permisos, referencias/archivos del proyecto, scheme, regeneración y sintaxis Swift PASS. **No es comprobación de tipos del SDK iOS.**
- UI: una prueba XCTest preparada para conexión ficticia y ANCS sin comprobar; NOT_RUN.
- Builds iOS/simulador, firma, hardware, F01–F12 y todos los gates conjuntos: BLOCKED/NOT_RUN según `evidence/apple-initial/run.json`.
- Toolchain: macOS 26.6.2 (25G83), Swift 6.4, CLT activa, SDK macOS 27.0. Falta Xcode. El script completo devuelve 2 por ese bloqueo; no debe interpretarse como aprobación.

## Solicitud concreta al equipo Windows

1. Entregar commit base/contratos y APK de laboratorio, checksum, versiones/firmware reales y guía para activar la publicidad sin resetear el watch.
2. Revisar el requisito ASK adicional: anuncio de nombre que contenga `WristBridge` junto al UUID `6f06b8b1-21d9-4c65-9d7e-118880e36a01`. Es una propuesta Apple, no una modificación congelada del contrato. Alternativa: acordar datos de servicio y máscara.
3. Confirmar soporte de pairing LE y comportamiento mínimo de servicio para la campaña A; propiedades/permisos del perfil se documentan conjuntamente. Swift no inventa características ni escribe a las UUID reservadas.
4. Preparar diagnóstico watch que distinga bond, ANCS disponible/autorizado, Data Source y Notification Source confirmados, generación, eventos y tarjetas. Swift no puede observar todo eso por sí solo.
5. Ejecutar `test/hil/apple/mvp.md` con ambos equipos en la misma revisión; cerrar ADR-001 con evidencia. No coordinar cambiando simultáneamente las UUID.

No se envió este documento por servicios externos. Está listo para intercambiarse junto con el código mediante el repositorio común o un paquete de archivos; todavía no hay remoto para sincronizar.

## Siguiente acción Apple

Disponer de Xcode completo, compilar contra el SDK iOS correcto y corregir cualquier error que ese primer build revele; después firmar e instalar en iPhone, confirmar filtros con Windows y ejecutar el experimento A físico. No avanzar a P2 sensible ni fases opcionales hasta resolver sus dependencias.
