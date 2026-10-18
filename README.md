# WristBridge · iPhone

Base Apple para conectar un iPhone con Galaxy Watch7. El reloj consume ANCS directamente; la app iPhone configura el accesorio y genera pruebas propias. La implementación Kotlin del equipo Windows ya está integrada en este checkout.

Candidato actual **0.2.1 (6)**: Conexión, Pruebas y Ajustes, recuperación/restauración, resultados propios persistentes y controles de privacidad. El Release firmado 0.2.1 (6) está instalado y abrió en el iPhone. La traza física confirmó el servicio de configuración tras esperar disponibilidad Bluetooth; la campaña completa de avisos/recuperación sigue pendiente.

El usuario reportó recepción de avisos en Watch7 incluso con iPhone suspendido/app cerrada deslizando; el fallo anterior del canal de configuración se conserva en la evidencia. **No están cerradas todas las fases**: canal seguro/contratos, extensiones de salud/contactos y campañas conjuntas siguen pendientes. Ver [estado de las ocho fases iPhone](docs/apple/iphone-phases.md).

Ramas: `developer` es la integración solicitada por el usuario; `development_codex` es la rama de trabajo Apple. `main` conserva su historia anterior. Los avances de `feat/ios/xcode-validation` ya están incorporados.

- [**SEED.md**: estado vivo del proyecto y protocolo multiagente](SEED.md).
- [Ensayo de reconexión 0.3.0 (sin instalar aún)](docs/apple/handoff-0.3.0.md).
- [Pruebas rápidas de la versión 0.2.1](test/hil/apple/quickstart-0.2.1.md).
- [Integración y entrega 0.2.1](docs/apple/handoff-0.2.1.md).
- [Estado actual y entrega P2 Apple](docs/apple/handoff-P2.md).
- [Revisión del plan y decisiones](docs/apple/review.md).
- [Entorno y restricciones reales](docs/apple/environment.md).
- [Instalación y prueba iPhone](docs/apple/installation.md).
- [Propuesta de salud P7 (en revisión)](docs/apple/health-sync-proposal.md).
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

App Kotlin/Wear OS en `apps/watch` y módulos JVM en `core/*`, compilados con Gradle desde Windows. Estado: laboratorio P1 compilado y con pruebas JVM; recepción física reportada por el usuario el 18 de octubre; ver el handoff Apple para alcance y evidencia pendiente.

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
