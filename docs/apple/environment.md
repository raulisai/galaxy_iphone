# Entorno Apple observado

Inventario realizado el 16 de octubre de 2026, hora de México. No se registran seriales, identificadores de dispositivos ni cuentas.

| Elemento | Observación | Estado |
|---|---|---|
| Mac | macOS 26.6.2, build 25G83, arm64 | PASS: inventariado |
| Directorio de desarrollo | `/Library/Developer/CommandLineTools` | PASS: observado |
| Swift | Apple Swift 6.4, swiftlang-6.4.0.34.1, clang-2100.3.34.1 | PASS: observado; no se afirma canal estable |
| SDK disponible por defecto | macOS 27.0; no es SDK iOS | PASS: observado |
| Xcode completo | No localizado en `/Applications`; `xcodebuild -version` falla por selección CLT | BLOCKED: build/firma iOS |
| Simulador | `simctl` no disponible en el entorno seleccionado | BLOCKED: UI ejecutable |
| Dispositivos Apple | `devicectl` no disponible en el entorno seleccionado | BLOCKED: inventario/instalación automática |
| iPhone 15 y Watch7 | Usuario confirma disponibles para pruebas | Versión/build real NOT_RUN |
| Cuenta/equipo de firma | No inspeccionado; no se buscaron credenciales | NOT_RUN |
| Destino iOS | Mínimo propuesto 26.0, solo iPhone | Configurado; compatibilidad NOT_RUN |
| Dependencias Swift externas | Ninguna | PASS: inventario |

El primer `swift test` consiguió ejecutar ocho pruebas. Tras cambiar el mínimo macOS del paquete a 14 para alinearlo con Swift Testing, el backend `swiftbuild` de esta CLT dejó de cargar `TestingMacros`. El backend nativo sin parámetros adicionales tampoco encontraba `Testing`. Se verificaron de nuevo las ocho pruebas usando el backend nativo y rutas explícitas al framework/plugin incluidos en la propia CLT. `scripts/apple/test-package.sh` aplica esa adaptación solo a CLT; con Xcode completo usa SwiftPM normal. El backend nativo está deprecado, así que esta es una adaptación local, no una selección permanente del producto. Esta limitación no se resuelve eliminando pruebas ni se considera una build iOS aprobada.

Herramientas de generación/verificación: Python 3 estándar, `plutil`, Swift Package Manager. No se instaló Ruby, XcodeGen ni una dependencia de proyecto adicional. La generación Xcode es determinista desde `scripts/apple/generate_project.py`.

La combinación Xcode/SDK iOS debe elegirse después de conocer el OS/build físico del iPhone y los requisitos vigentes de Apple. Registrar aquí la salida exacta de `xcodebuild -version`, `xcodebuild -showsdks` y el resultado de build antes de congelar herramientas. El proyecto y CI no presentan como probada una versión elegida por nombre o por ser la más reciente.
