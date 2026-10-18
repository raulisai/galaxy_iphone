# Instalación física Apple

Actualización del 18 de octubre: el usuario reporta enlace con Watch7 y avisos recibidos con el teléfono suspendido y la app cerrada deslizando. Ver [handoff P2](handoff-P2.md); este documento conserva la instalación de P1, no certifica la nueva recuperación P2.

17 de octubre de 2026, America/Mexico_City. iPhone 15, iOS 26.6.2 (23G90), conectado por cable, emparejado, Modo de desarrollador activo y DDI preparado.

## Estado observado

- Cuenta Apple agregada a Xcode; Personal Team disponible.
- Firma de desarrollo configurada en `apps/ios/Signing.local.xcconfig`, excluido de Git. No se registran el equipo, la cuenta ni sus credenciales en archivos compartidos.
- Build Debug firmada: PASS. Verificación local `codesign --verify --deep --strict`: PASS.
- Perfil: bundle y equipo coinciden; contiene el UDID del iPhone comprobado en memoria; no está vencido; permite depuración. Las cinco comprobaciones pasan.
- Instalación: PASS, mediante `devicectl`; se consultó después la app instalada y se verificó WristBridge 0.1.0 (1).
- Primera apertura: bloqueada por iOS con `FBSOpenApplicationErrorDomain / Security`. La firma y el perfil superaron las comprobaciones; se pidió completar confianza en el teléfono. Se conserva esa denegación como primer intento.
- Apertura posterior a la confirmación del usuario: PASS, comando de apertura con código 0, proceso WristBridge activo y pantalla real inspeccionada. El bloqueo de confianza está resuelto.

La comprobación física cubre apertura y UI visible en modo oscuro: título WristBridge, laboratorio, permiso de avisos propios Sin solicitar y controles de fixtures. No es una campaña completa de accesibilidad, lifecycle o Bluetooth. No se solicitó permiso de avisos ni se programó un lote en esta comprobación; ANCS permanece sin probar.

## Identidad y duración de esta instalación

Bundle de laboratorio: `dev.wristbridge.iphone.lab.a6b689d82`. El sufijo es aleatorio, sin identidad del usuario. La app conserva las UUID del contrato; el cambio de bundle propio no modifica el protocolo con el reloj.

El perfil emitido vence el 24 de octubre de 2026 a las 00:30, hora de México (06:30 UTC). Corresponde a esta instalación de desarrollo; renovar mediante una build firmada cuando corresponda. No es distribución pública ni una IPA universal.

## Evidencia

`evidence/apple-physical-install/apple/` contiene `signed-build.log` redaccionado, `installation-status.json` del primer intento, `profile-validation.json`, `launch-after-trust.json`, `running-process.json` y `iphone-app-running.png`. `run.json` resume el estado vigente. La primera denegación no se convirtió retrospectivamente en un éxito.

Se verificó que los archivos compartidos no contienen el equipo de firma, UDID o serial reales. Los certificados y perfiles emitidos quedan en los almacenes locales de Xcode/Keychain/DerivedData; no se copiaron al repositorio. Se conserva la evidencia de simulador como campaña separada.

Siguiente paso: confirmar que la app Kotlin de laboratorio esté instalada/abierta en Watch7 y ejecutar el experimento A. La instalación Apple está comprobada; G0 conjunto/ADR-001/G1 siguen pendientes.

El usuario confirmó que la app todavía no está instalada en Watch7. La campaña de enlace queda BLOCKED por esa dependencia concreta. Windows debe entregar e instalar su APK de laboratorio, confirmar anuncio `WristBridge` y servicio reservado, y aportar el diagnóstico ANCS antes del experimento. Este checkout Apple no contiene APK ni código Kotlin para instalar en el reloj.

Referencia: [Confiar manualmente en un desarrollador, ayuda de Xcode](https://help.apple.com/xcode/mac/current/en.lproj/dev96a12fb84.html).
