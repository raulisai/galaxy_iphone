# Compilar e instalar en iPhone

Estado actual: **BLOCKED por Xcode completo ausente**. No se ha generado IPA ni instalado esta app. Los pasos siguientes son el procedimiento preparado, no evidencia de instalación.

## Preparar herramientas

1. Instalar Xcode completo desde [Apple Developer](https://developer.apple.com/xcode/) o la Mac App Store, con SDK que admita el OS real del iPhone. Abrirlo y completar los componentes de plataforma/licencia que solicite Apple.
2. Seleccionar ese Xcode en Settings → Locations → Command Line Tools. Para un comando aislado también puede usarse `DEVELOPER_DIR` apuntando a su directorio `Contents/Developer`, sin cambiar la configuración global del Mac.
3. Registrar versión/build de iPhone en Ajustes → General → Información, sin guardar serial/UDID. Completar `environment.md` y el run de HIL.
4. Comprobar Xcode/SDK y ejecutar `bash scripts/apple/check.sh`. El primer build real puede revelar errores de tipos de SDK que el análisis sintáctico local no puede detectar.

## Firma e instalación de desarrollo

1. Abrir `apps/ios/WristBridge.xcodeproj`; seleccionar scheme `WristBridge`.
2. Añadir la cuenta Apple propia a Xcode. Copiar `Signing.local.xcconfig.example` como `Signing.local.xcconfig` y definir `DEVELOPMENT_TEAM` y, si hace falta, `WRISTBRIDGE_APP_BUNDLE_ID` único. El archivo local está excluido de Git. No pegar certificados o credenciales en el chat.
3. Conectar el iPhone al Mac, completar la confianza del dispositivo y activar Developer Mode si Xcode lo requiere. Elegir ese destino y ejecutar Run.
4. Verificar nombre de app, versión 0.1.0 (1), revisión de código y OS/build. Las restricciones de cuenta personal, renovación de perfil o instalaciones las determina Apple; registrar las que realmente aparezcan.

No se publica en App Store ni se crea una suscripción o servicio externo. No hace falta resetear el reloj.

## Selector y primera conexión

1. Instalar la app de laboratorio Kotlin del otro equipo en Watch7 y activar el anuncio del servicio reservado. Requisito propuesto: nombre anunciado que contenga `WristBridge`; pendiente confirmación Windows.
2. Abrir WristBridge en iPhone y tocar **Seleccionar reloj**. El picker debe mostrar el dispositivo correcto. Autorizarlo o cancelar para probar el caso negativo.
3. Verificar separadamente **Accesorio autorizado**, conexión y **Servicio de configuración encontrado**. Este último no confirma protocolo P2, ANCS ni cifrado medido del enlace.
4. Los **Avisos en el reloj** permanecen **Sin comprobar**. La app todavía no recibe un estado ANCS de la contraparte: comprobarlo con diagnóstico Kotlin y evidencia física. No hay formato de estado inventado.
5. Si iOS ofrece compartir notificaciones del sistema para el accesorio en Ajustes → Bluetooth, verificarlo. El botón Abrir Ajustes de la app usa únicamente el enlace público a los ajustes propios; no usa URLs privadas de Bluetooth.

## Simulador

La UI de demostración está disponible en Debug con argumento `--demo` del scheme. Muestra una banda explícita de simulación, no usa la radio y oculta el generador real. Release ignora ese argumento.

Prueba de UI preparada:

```sh
WRISTBRIDGE_TEST_DESTINATION='platform=iOS Simulator,name=NOMBRE_INSTALADO,OS=VERSION_INSTALADA' bash scripts/apple/check.sh
```

Sustituir nombre/versión por un destino real de `xcrun simctl list devices available`. No se ha ejecutado aquí. La prueba confirma que una conexión simulada no cambia ANCS a listo y que detener configuración cambia su estado visible; no prueba autorización del sistema ni Bluetooth.

## CI

El workflow Apple es manual hasta fijar una combinación real. Solicita ruta de Xcode del runner, versión exacta esperada y destino de simulador. Verifica esa selección antes de ejecutar paquete, build y UI. No se activó ni ejecutó remotamente; falta remoto Git. No hay firma o credenciales de Apple en CI.
