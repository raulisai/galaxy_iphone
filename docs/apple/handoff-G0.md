# Handoff Apple · G0 y preparación P1

Actualizado el 16 de octubre de 2026, America/Mexico_City, después de instalar Xcode. Revisión base `02136d3` (`init`), rama `feat/ios/xcode-validation`, con cambios locales pendientes de commit. Ya existe repositorio con `origin/main`; no se hizo push ni se modificó el trabajo Windows. La ejecución vigente está en `evidence/apple-xcode-validation/apple`; `evidence/apple-initial` conserva la primera sesión.

Actualización física posterior: iPhone 15 detectado por cable, emparejado, iOS 26.6.2 (23G90). Se resolvió la falta de detección. El preflight de `evidence/apple-physical-setup/apple` aún bloquea instalación por Modo de desarrollador desactivado y firma/cuenta Apple pendientes. El inventario y las limitaciones de la campaña con Xcode de abajo son históricos donde mencionan falta de detección; no se ejecutó todavía la campaña ANCS.

Segunda actualización física: Modo de desarrollador verificado activo y preparación DDI completada (código 0). La ventana de acceso de Apple en Xcode sigue vacía; la firma/cuenta es el único bloqueo local para instalar. Registro `device-ready-preflight.json`. No se ha instalado ni ejecutado la app en el iPhone.

Actualización del 17 de octubre: cuenta/Personal Team y firma validados; build firmada e instalación de WristBridge 0.1.0 (1) PASS. La primera apertura fue bloqueada por la comprobación de confianza de iOS; se pidió al usuario completar confianza en el teléfono. Aún no se declara ejecución física ni ANCS aprobado. Detalles vigentes en `physical-installation.md` y `evidence/apple-physical-install/apple`.

Estado físico vigente: confianza resuelta, apertura PASS (código 0), proceso activo y pantalla propia inspeccionada en iPhone 15. La app corre en modo real; permiso de avisos propios aún Sin solicitar. Instalación Apple y smoke de arranque/pantalla aprobados; enlace BLE/ANCS, campaña de fixtures y gates conjuntos siguen pendientes de la contraparte Watch7.

Dependencia confirmada por el usuario: la app aún no está instalada en Watch7. Windows debe generar/entregar e instalar el APK de laboratorio con checksum y revisión, anuncio de nombre/servicio y diagnóstico ANCS descritos abajo. La parte Apple no requiere más ajustes de firma para iniciar la campaña; la campaña de enlace queda BLOCKED por la contraparte.

## Entregado

- Proyecto iPhone, scheme compartido, paquete Swift local y adaptadores ASK/CoreBluetooth del camino A.
- Onboarding, cancelación, retirada, diagnóstico y ANCS explícitamente sin comprobar.
- Generador de avisos propios con IDs sintéticos y registros acotados; canal sensible desactivado.
- Builds Debug de simulador y Release arm64 iPhone sin firma aprobadas.
- Ocho pruebas Swift y dos pruebas de interfaz aprobadas con Xcode completo.
- Corregidos protección de archivo iOS, conformidades de delegates y arquitectura Debug. Generador de proyecto consistente con el proyecto generado.
- Guías físicas, configuración de firma local, CI manual y reporte `validation-xcode.md`.

No hay codec P2, parsing ANCS Swift, IA, Mac, salud ni transferencias propias sensibles.

## Estado por tarea

| ID | Resultado local | Pendiente |
|---|---|---|
| P1-01.S | Inventario Mac/Xcode/SDK y del iPhone 15: iOS 26.6.2 (23G90), conectado, emparejado, Modo de desarrollador activo y DDI preparado | Cuenta Apple y configuración de firma pendientes. |
| P1-02.S | Proyecto y paquete compilados; ocho tests Swift, dos UI simuladas y arranque/pantalla en iPhone físico PASS | G0 integrado con Windows y prueba de enlace real. |
| P1-04.S | ASK y transporte A compilados | Publicidad y pairing LE reales, ADR-001, decisión A/B. |
| P1-05.S | UI ejecutada en simulador; estado ANCS honesto | Picker y permisos físicos H02–H04. |
| P1-09.S | Plan de fixtures probado; UI bloquea programación sin consentimiento | Programar lote autorizado, verificar persistencia y entrega iOS/watch. |
| P1-10.S | Restauración básica compilada | H06–H09/R01–R04, sin promesa de recuperación automática aprobada. |
| P1-11.S | Campaña preparada | Ambas apps instaladas y campaña física. |
| P1-12.S | Build Debug firmada, instalación 0.1.0 (1), apertura y pantalla en iPhone físico PASS | Empaquetado/aceptación conjunta después de G1. |

G0 conjunto y G1 siguen pendientes. Ningún caso físico se aprobó por los resultados de simulador.

## Herramientas y evidencia

macOS 26.6.2 (25G83), Xcode 27.1 (27A9275), Swift 6.4 y SDK iOS/iOS Simulator 27.1. UI ejecutada en iPhone 17 simulado, iOS 26.2 (23C54). El paquete pasa con SwiftPM normal; el fallo histórico de macros CLT no está activo bajo Xcode completo.

`validation-xcode.md` detalla los checks, capturas y fallos iniciales corregidos. `run.json` distingue build sin firma, UI, tests y hardware. `source-manifest.json` identifica los archivos de esta entrega sin depender de un commit nuevo. No se guardan cuentas, claves de firma ni identificadores de teléfono físico.

## Interfaz y solicitud Windows

El contrato documental permanece en versión 1.0 y conserva las cuatro UUID reservadas. El formato P2 y las propiedades GATT no están congelados. Swift descubre el servicio sin leer/escribir características reservadas ni inventar un reporte ANCS.

Windows debe entregar commit base/contratos y APK de laboratorio con checksum, guía de activación y firmware real. Confirmar anuncio de nombre que contenga `WristBridge` más UUID `6f06b8b1-21d9-4c65-9d7e-118880e36a01`; ese nombre sigue siendo una propuesta Apple requerida para el filtro ASK. Alternativa: acordar datos de servicio/máscara.

Confirmar pairing LE, perfil mínimo y diagnóstico watch separado para bond, permiso ANCS, confirmación Data Source/Notification Source, generación, eventos y tarjetas. Ejecutar una sola ruta A/B y la misma revisión integrada durante la campaña. No se envió este handoff por servicios externos.

## Siguiente acción Apple

Conectar/desbloquear/confiar en iPhone, configurar el equipo propio en Xcode, firmar e instalar. Registrar versión/build y ejecutar el spike A con Windows. La disponibilidad de hardware confirmada por el usuario no sustituye su detección ni la ejecución física. Continuar con `test/hil/apple/mvp.md`; no avanzar a funciones sensibles o fases posteriores antes de sus gates.
