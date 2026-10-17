# WristBridge · iPhone

Base Apple para conectar un iPhone con Galaxy Watch7. El reloj consume ANCS directamente; la app iPhone configura el accesorio y genera pruebas propias. La implementación Kotlin pertenece al equipo Windows y todavía no está en este checkout.

El código inicial cubre G0 y preparación Apple de P1. **No hay aún un puente validado en hardware ni una build iOS compilada.** Esta máquina tiene Command Line Tools, pero no Xcode completo. Los dispositivos están disponibles según el usuario.

- [Revisión del plan y decisiones](docs/apple/review.md).
- [Entorno y restricciones reales](docs/apple/environment.md).
- [Instalación y prueba iPhone](docs/apple/installation.md).
- [Entrega al equipo del reloj](docs/apple/handoff-G0.md).
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

Abrir `apps/ios/WristBridge.xcodeproj`, scheme compartido `WristBridge`. El proyecto usa un paquete local sin dependencias externas. Para regenerarlo después de añadir archivos Swift: `python3 scripts/apple/generate_project.py`.
