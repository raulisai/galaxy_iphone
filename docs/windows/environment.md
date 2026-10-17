# Entorno Windows · Galaxy Watch7

Registrado el 17 de octubre de 2026 (America/Mexico_City). Este documento recoge las herramientas fijadas para compilar la app Kotlin del reloj, con la versión real de cada pieza y su fuente oficial.

## Máquina de desarrollo

| Pieza | Versión real | Notas |
|---|---|---|
| Sistema | Windows 11 Pro 10.0.26300 | Shell PowerShell 5.1 y Git Bash. |
| JDK para Gradle | Eclipse Temurin 21.0.12.1+1 LTS | Instalado con `winget install EclipseAdoptium.Temurin.21.JDK`. `JAVA_HOME` de usuario apunta a `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`. |
| Android Studio | Build AI-261.26222.65.2614.16204760 | Trae JBR 25. **No** se usa para Gradle: en Settings → Build Tools → Gradle → Gradle JDK hay que elegir `JAVA_HOME` (Temurin 21), para que el IDE y la consola compilen igual. |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` | `ANDROID_HOME` y `ANDROID_SDK_ROOT` de usuario. Plataformas android-36 y android-37, build-tools 36.0.0. |
| ADB | 1.0.41, platform-tools 37.0.1 | `platform-tools` está en el PATH de usuario. |

Las terminales abiertas antes de configurar las variables no las ven; hay que abrir una nueva.

## Versiones fijadas del build

| Componente | Versión | Fuente |
|---|---|---|
| Gradle (wrapper) | 9.8.1, con `distributionSha256Sum` | services.gradle.org (versión actual y checksum publicado). |
| Android Gradle Plugin | 9.4.1 | Último estable en maven.google.com. Notas oficiales de AGP 9.4: Gradle mínimo 9.6.0, JDK mínimo 17, build-tools 36.0.0, API máxima 37. |
| Kotlin | 2.4.21 | Último estable en Maven Central. AGP 9 trae Kotlin integrado: el módulo app no aplica `kotlin-android`; `kotlin.plugin.compose` usa la misma versión. |
| compileSdk / targetSdk / minSdk | 37 / 36 / 33 | AndroidX 2026.09 exige compilar contra API 37. `targetSdk` 36 mantiene el comportamiento de ejecución. `minSdk` 33 equivale a Wear OS 4 o posterior. |
| Bytecode | JVM 17 | Los módulos `core/*` son JVM puro, consumibles por Android. |

Las librerías (Compose BOM 2026.09.00, Wear Compose Material3 1.7.1, coroutines 1.11.0, JUnit 6.1.3…) están en [`gradle/libs.versions.toml`](../../gradle/libs.versions.toml). Ninguna versión es dinámica.

## Reloj (pendiente de inventario físico, P1-01.K)

| Campo | Estado |
|---|---|
| Modelo | Galaxy Watch7 (dato del plan; el modelo no define la versión de Wear OS). |
| Versión de Wear OS / One UI Watch / build | **NOT_RUN**: se obtiene con ADB, `adb shell getprop ro.build.version.release`, `ro.build.version.sdk` y `ro.build.display.id`. |
| Vínculo actual con otro teléfono | **NOT_RUN**: anotarlo sin desvincular ni resetear. |
| Ruta de instalación | ADB inalámbrico (ver [installation.md](installation.md)). |

## Identificadores

- `applicationId` del reloj: `dev.wristbridge.watch`. Es propio y no se copió de un repositorio de referencia. El ID iOS es configurable (`WRISTBRIDGE_APP_BUNDLE_ID`) y no tiene que coincidir.
- Perfil BLE: [`contracts/ble-profile.json`](../../contracts/ble-profile.json), revisión `p1-lab-1`, contrato 1.0.

## Comandos de verificación

```powershell
.\gradlew.bat :core:protocol-kotlin:test :core:notification-domain:test :core:assistant-domain:test
.\gradlew.bat :apps:watch:lintDebug :apps:watch:assembleDebug
```

`connectedDebugAndroidTest` existe como tarea, pero no hay pruebas instrumentadas todavía y nunca se sustituye la prueba física por un emulador.
