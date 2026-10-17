# Entorno Apple observado

Inventario actualizado el 16 de octubre de 2026, hora de México, después de instalar Xcode. No se registran seriales, identificadores de dispositivos físicos ni cuentas. Resultados en `validation-xcode.md`; herramientas observadas en `toolchain-lock.json`.

| Elemento | Observación | Estado |
|---|---|---|
| Mac | macOS 26.6.2, build 25G83, arm64 | PASS: inventariado |
| Directorio de desarrollo | `/Applications/Xcode.app/Contents/Developer` | PASS: observado |
| Swift | Apple Swift 6.4, swiftlang-6.4.0.34.1, clang-2100.3.34.1 | PASS: observado; no se afirma canal estable |
| SDK iOS y simulador | iOS 27.1 / iOS Simulator 27.1 | PASS: compilación iOS disponible |
| Xcode completo | Xcode 27.1, build 27A9275 | PASS: seleccionado y ejecutado |
| Simulador | iPhone 17 con iOS 26.2, build 23C54 | PASS: arranque e instalación para pruebas |
| Dispositivos Apple | iPhone 15 físico detectado por cable y emparejado | PASS: detección/confianza; instalación pendiente |
| iPhone 15 | iOS 26.6.2, build 23G90; Modo de desarrollador activo y DDI preparado | PASS: inventario/preparación; firma pendiente |
| Watch7 | Usuario confirma disponible para pruebas | Versión/build real NOT_RUN |
| Firma | Cuenta y Personal Team disponibles; configuración local excluida de Git; build firmada, instalación y apertura PASS | Confianza verificada al abrir la app |
| Destino iOS | Mínimo 26.0, solo iPhone | SDK compila; compatibilidad física NOT_RUN |
| Dependencias Swift externas | Ninguna | PASS: inventario |

### Conexión física posterior

El iPhone 15 ya fue detectado por cable y emparejado con el Mac: iOS 26.6.2, build 23G90. El bloqueo anterior de detección queda resuelto. En el preflight físico se observó Modo de desarrollador desactivado, cero identidades de firma y ninguna cuenta Apple iniciada en Xcode. Se dejó abierta la ventana de acceso para el usuario; las credenciales no se solicitan por chat. Registro sin serial/UDID/cuenta en `evidence/apple-physical-setup/apple/device-preflight.json`. Instalación pendiente de esas acciones.

Preflight posterior: el usuario activó Modo de desarrollador y se verificó activo. La preparación DDI mediante la herramienta oficial de Xcode terminó con código 0. La ventana de acceso a Apple continuaba vacía; solo queda pendiente completar cuenta/firma para instalar. Nuevo registro: `device-ready-preflight.json` en el mismo directorio de evidencia.

Actualización del 17 de octubre: cuenta y Personal Team validados; firma local y build firmada aprobadas. WristBridge 0.1.0 (1) está instalada en el iPhone. La primera apertura fue denegada por la comprobación de confianza de iOS. Los bloqueos de cuenta/firma de los preflights anteriores están resueltos; falta completar confianza en el teléfono y repetir apertura. Ver `physical-installation.md`.

Validación posterior del 17 de octubre: apertura con código 0, proceso WristBridge observado activo y captura de la UI propia inspeccionada. El bloqueo de confianza queda resuelto. Solo se valida instalación/arranque/pantalla; no se ejecutó todavía el enlace ANCS con el reloj.

Con Xcode completo, las ocho pruebas del paquete pasan usando SwiftPM normal, sin la adaptación CLT. En la primera sesión, el backend `swiftbuild` de CLT dejó de cargar `TestingMacros`; el backend nativo necesitó rutas explícitas al framework/plugin incluidos. El script conserva esa adaptación únicamente para entornos CLT. Es un antecedente registrado en `evidence/apple-initial`, no un fallo vigente de la ejecución con Xcode.

Herramientas de generación/verificación: Python 3 estándar, `plutil`, Swift Package Manager. No se instaló Ruby, XcodeGen ni una dependencia de proyecto adicional. La generación Xcode es determinista desde `scripts/apple/generate_project.py`.

Xcode/SDK observados quedan registrados para reproducir la compilación local. Todavía debe conocerse el OS/build físico del iPhone antes de declarar esa combinación compatible con hardware. No se afirma que el canal de distribución del Xcode instalado haya sido certificado independientemente ni que una build sin firma pueda instalarse.
