# Instalación y uso diario desde Windows

Guía para compilar, instalar la app WristBridge en el Galaxy Watch7 y ver su diagnóstico. No requiere resetear el reloj ni desvincularlo de su teléfono actual.

## 1. Qué es ADB

ADB (Android Debug Bridge) conecta la computadora con un dispositivo Android; el Watch7 usa Wear OS, que es Android. Con ADB se instala el APK, se lee el registro (`logcat`) y se consultan versiones. Funciona por Wi-Fi.

## 2. Preparar el reloj (una sola vez)

1. **Opciones de desarrollador:** Ajustes → Información del reloj → Información de software → toca **Versión de software** 5 veces.
2. **Wi-Fi:** conecta el reloj a la misma red Wi-Fi que la PC (Ajustes → Conexiones → Wi-Fi).
3. **Depuración:** Ajustes → Opciones de desarrollador → activa **Depuración ADB** y **Depuración inalámbrica**.
4. **Vincular la PC:** en **Depuración inalámbrica** → **Vincular nuevo dispositivo**. Anota la IP:puerto y el código de 6 dígitos.
5. En una terminal nueva de la PC:

   ```powershell
   adb pair <IP>:<PUERTO_DE_VINCULACION>
   adb connect <IP>:<PUERTO_DE_DEPURACION>
   adb devices
   ```

   El puerto de depuración es el que aparece en la pantalla principal de **Depuración inalámbrica**, distinto del de vinculación. `adb devices` debe mostrar el reloj como `device`. La vinculación se recuerda; los días siguientes basta `adb connect`, porque el puerto cambia al reactivar la depuración.

Para pruebas de batería: desactiva la depuración inalámbrica y desconecta ADB (Doc/00, protocolo físico).

## 3. Abrir el proyecto

- **VS Code:** `code-insiders "<ruta del repositorio>"`. Extensiones recomendadas: **Kotlin** (JetBrains) y **Gradle for Java**. Sirve para editar, compilar e instalar desde su terminal integrada.
- **Android Studio:** File → Open → carpeta raíz del repositorio. Pon Gradle JDK = `JAVA_HOME` (Temurin 21). Úsalo para depurar paso a paso, ver la vista previa de Compose y el inspector de Bluetooth.

Las dos herramientas pueden tener abierta la misma carpeta.

## 4. Compilar, probar e instalar

```powershell
.\gradlew.bat :core:protocol-kotlin:test :core:notification-domain:test
.\gradlew.bat :apps:watch:assembleDebug
.\gradlew.bat :apps:watch:installDebug
```

`installDebug` instala en el dispositivo conectado por ADB. El APK queda en `apps\watch\build\outputs\apk\debug\watch-debug.apk`. Para instalar un APK entregado y comprobar su checksum:

```powershell
Get-FileHash .\watch-debug.apk -Algorithm SHA256
adb install -r .\watch-debug.apk
```

## 5. Activar el puente en el reloj

1. Abre **WristBridge** en el reloj. La primera línea muestra versión y commit (`0.1.0-lab · <commit>`), que deben coincidir con la entrega (H01).
2. **Conceder permisos:** Bluetooth (dispositivos cercanos) y notificaciones.
3. **Iniciar puente:** arranca el servicio en primer plano. Aparece un aviso discreto «WristBridge activo».
4. **Canal propio** debe decir «Anunciando, esperando iPhone». Si debajo aparece que el nombre no contiene WristBridge, toca **Nombre WristBridge**. Eso cambia el nombre Bluetooth del reloj a `WristBridge` para que el selector del iPhone lo encuentre; **Restaurar nombre** lo devuelve al original.
5. En el iPhone, abre WristBridge y usa el selector. Acepta el emparejamiento en ambos dispositivos y, si iOS lo pide, permite compartir notificaciones.
6. **Avisos (ANCS)** pasa por Conectando → Buscando servicio → Suscribiendo → Data Source confirmado → **Listo**. Solo «Listo» significa que ANCS está suscrito y confirmado.

Puedes cerrar la app del reloj: el servicio sigue activo. **Detener puente** lo apaga.

## 6. Ver el diagnóstico en vivo

```powershell
adb logcat -s WristBridge
```

Los registros muestran estados, generación, UID, tamaños y, para avisos sintéticos del iPhone, `fixture=<runId>/F001`. Nunca muestran títulos ni cuerpos ni direcciones Bluetooth (los pares aparecen como `peer-xxxxxxxx`). La misma lista está en la sección **Diagnóstico** de la app.

Inventario del reloj para P1-01 (copiar el resultado a la evidencia):

```powershell
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.display.id
```
