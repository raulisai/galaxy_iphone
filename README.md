# WristBridge · iPhone

Base Apple para conectar un iPhone con Galaxy Watch7. El reloj consume ANCS directamente; la app iPhone configura el accesorio y genera pruebas propias. La implementación Kotlin pertenece al equipo Windows y todavía no está en este checkout.

El código inicial cubre G0 y preparación Apple de P1. **La app está firmada, instalada y abre correctamente en el iPhone 15**, con iOS 26.6.2 (23G90). Se verificó el proceso activo y la pantalla real de laboratorio. La confianza del perfil quedó resuelta; las pruebas de enlace Bluetooth y avisos con el Watch7 siguen pendientes.

Ramas: `main` conserva la base integrada y `development_codex` es la única rama de trabajo Apple. Los avances de `feat/ios/xcode-validation` ya están incorporados.

- [Revisión del plan y decisiones](docs/apple/review.md).
- [Entorno y restricciones reales](docs/apple/environment.md).
- [Instalación y prueba iPhone](docs/apple/installation.md).
- [Entrega al equipo del reloj](docs/apple/handoff-G0.md).
- [Validación con Xcode y resultados](docs/apple/validation-xcode.md).
- [Instalación física y confianza del perfil](docs/apple/physical-installation.md).
- [Campaña física MVP](test/hil/apple/mvp.md).
- [Documentación original](Doc/00_LEEME_Y_COORDINACION.md).

Pruebas locales del paquete, sin Xcode completo:

```sh
bash scripts/apple/test-package.sh
python3 scripts/apple/validate_structure.py
```

Validación Apple, incluyendo compilación cuando Xcode esté disponible:

```sh
bash scripts/apple/check.sh
```

El script devuelve `2` si falta Xcode, después de verificar estructura y paquete. Las pruebas de UI requieren `WRISTBRIDGE_TEST_DESTINATION` con un simulador instalado. No ejecutan la radio ni sustituyen HIL.

## Windows · Galaxy Watch7

App Kotlin/Wear OS en `apps/watch` y módulos JVM en `core/*`, compilados con Gradle desde Windows. Estado: laboratorio P1 compilado y con pruebas JVM; sin prueba física todavía.

- [Entorno Windows](docs/windows/environment.md).
- [ADB, VS Code/Android Studio e instalación en el reloj](docs/windows/installation.md).
- [Entrega Windows G0](docs/windows/handoff-G0.md).
- [Campaña física lado reloj](test/hil/windows/p1-campaign.md).

```powershell
.\gradlew.bat :core:protocol-kotlin:test :core:notification-domain:test :core:assistant-domain:test
.\gradlew.bat :apps:watch:lintDebug :apps:watch:assembleDebug
```

## Apple

Abrir `apps/ios/WristBridge.xcodeproj`, scheme compartido `WristBridge`. El proyecto usa un paquete local sin dependencias externas. Para regenerarlo después de añadir archivos Swift: `python3 scripts/apple/generate_project.py`.
