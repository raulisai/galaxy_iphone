# Validación Apple con Xcode

16 de octubre de 2026, America/Mexico_City. Xcode 27.1 (27A9275), SDK iOS/iOS Simulator 27.1 y Swift 6.4. Runtime de prueba: iPhone 17 simulado, iOS 26.2 (23C54). Ver `toolchain-lock.json`.

## Resultado y alcance

La app compila con el SDK de iOS y se ejecutó en simulador. Pasaron las ocho pruebas del paquete Swift usando SwiftPM normal y las dos pruebas de interfaz. Se inspeccionaron las capturas: estados legibles, demostración identificada, ANCS sin comprobar y aviso de autorización previa al programar.

No se ha instalado en el iPhone físico: `devicectl` solo detectó dispositivos simulados, no hay identidades válidas de firma y no existe configuración local de equipo. La versión/build real del iPhone sigue pendiente. El producto Kotlin y su contrato materializado tampoco están en este checkout. G0 conjunto, ADR-001 y G1 siguen sin aprobarse.

## Correcciones encontradas al ejecutar

1. **Protección del registro:** la opción se había escrito como `completeUntilFirstUserAuthentication`, que el SDK iOS no reconoce. Se corrigió a `completeFileProtectionUntilFirstUserAuthentication`, conservando escritura atómica y protección después del primer desbloqueo. La compilación previa de un paquete Foundation no verificaba este adaptador iOS.
2. **Callbacks Bluetooth:** las conformidades de los delegates ahora indican `@preconcurrency`. El gestor propio siempre usa la cola `.main`, correspondiente a la clase `@MainActor`; se comprueba esa precondición al entrar desde Objective-C. No se desactivó la comprobación global de concurrencia ni se añadió `unchecked Sendable`.
3. **Arquitecturas Debug:** la app intentaba enlazar x86_64 mientras el paquete local para el simulador seleccionado era arm64. El generador fija `ONLY_ACTIVE_ARCH=YES` en Debug y `NO` en Release, alineando app y paquete para el destino concreto. No se excluye arm64 ni se elimina la dependencia.
4. **Automatización UI:** se espera la actualización asíncrona del texto al detener configuración. La comprobación de consentimiento desplaza la lista hasta el mensaje debajo del botón. Los fallos iniciales se conservaron en logs; las expectativas funcionales siguen siendo las mismas.

## Pruebas verificadas

| Comprobación | Resultado | Límite |
|---|---|---|
| Documentación, UUIDs, permisos, referencias y regeneración | PASS | Consistencia estática; no radio. |
| Ocho tests Swift, con 16 variantes parametrizadas en tres de ellos | PASS | Estado y fixtures puros. |
| Build Debug de simulador | PASS | Código iOS compilado; sin hardware. |
| Conexión ficticia → detener; ANCS permanece sin comprobar | PASS | UI con `--demo`; no accesorio real. |
| Inicio normal → programar sin consentimiento | PASS | Muestra autorización requerida; no solicita permiso implícitamente ni programa el lote. |
| Programación autorizada, persistencia y entrega real | NOT_RUN | No se concedió permiso ni se emitió un lote en esta campaña. |
| Firma, instalación y picker físico | BLOCKED | Falta iPhone detectado y equipo de firma. |
| ANCS, pantallas dormidas, reconexión y métricas | NOT_RUN | Necesita ambas apps integradas y hardware. |

La compilación Release para iPhone sin firma se registra por separado en `evidence/apple-xcode-validation/apple/run.json`; aunque compile, no produce por sí sola un artefacto instalable.

## Evidencia y reproducción

Directorio: `evidence/apple-xcode-validation/apple/`. Contiene logs de paquete, estructura, build de simulador y Release iPhone; `ui-results.json` y `ui-tests.log`; capturas `demo-stopped.png` y `fixture-consent.png`; fallos iniciales con nombre `initial-*`; metadata `run.json` y hashes `source-manifest.json`.

```sh
bash scripts/apple/test-package.sh
python3 scripts/apple/validate_structure.py
xcodebuild -project apps/ios/WristBridge.xcodeproj -scheme WristBridge \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
xcodebuild -project apps/ios/WristBridge.xcodeproj -scheme WristBridge \
  -destination 'platform=iOS Simulator,name=iPhone 17,OS=26.2' \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO test
xcodebuild -project apps/ios/WristBridge.xcodeproj -scheme WristBridge \
  -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

Xcode emitió diagnósticos propios sobre el formato del build de su SDK (`invalidDigitCount(94232)`) y la consulta de versión del debugger. La campaña UI final devolvió código 0, dos tests aprobados y cero fallos; se conservan esos mensajes para no confundirlos con errores del producto. No se declara certificado el canal estable del Xcode instalado.

## Siguiente paso físico

Conectar y desbloquear iPhone, completar confianza con el Mac y seleccionar la cuenta/equipo propios en Xcode. Configurar `Signing.local.xcconfig`, instalar en ese destino y registrar OS/build. Después confirmar con Windows nombre anunciado, UUID, APK y revisión integrada y ejecutar `test/hil/apple/mvp.md`. No es necesario restablecer el reloj.

Fuentes: [protección de archivos de Apple](https://developer.apple.com/documentation/foundation/nsdata/writingoptions/completefileprotectionuntilfirstuserauthentication) y [aislamiento de conformidades Swift](https://github.com/swiftlang/swift/blob/main/userdocs/diagnostics/conformance-isolation.md).
