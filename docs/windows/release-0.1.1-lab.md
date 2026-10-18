# WristBridge Watch 0.1.1-lab (2)

18 de octubre de 2026 · America/Mexico_City. Rama development; cambios sin commit. Esta entrega modifica Android/Wear OS. Los cambios Apple anteriores se conservan para la otra computadora.

## Instalación y compatibilidad

- **PASS:** APK generado, firma v2 verificada, instalación mediante actualización -r y arranque del proceso de la app en SM-L300.
- **PASS:** versión instalada 0.1.1-lab, versionCode 2; checksum remoto idéntico al APK validado.
- APK: `build/releases/wristbridge-watch-0.1.1-lab-2.apk` (35 357 182 bytes).
- SHA-256: `f2ce5e172dbb5ef21512b75783eaa24c9f423f8dbddad30fd3b9ed5115c46f3d`.
- No se necesita otra app ni una nueva instalación iPhone para esta actualización. Se conserva el perfil P1/UUID y la ruta ANCS directa; no se incorpora un relay ni un protocolo P2.
- No se desinstaló la app ni se borraron sus datos o el vínculo. Android puede detener el servicio al reemplazar el paquete: abrir WristBridge e iniciar el puente si aparece detenido; reconectar desde la app iPhone existente si el enlace no vuelve.

## Implementación

La suscripción ANCS que expira durante el emparejamiento espera el vínculo hasta 60 s, sin extender el plazo ante errores repetidos. Al completar el vínculo se crea una generación GATT nueva; una confirmación tardía del descriptor previo no confirma la suscripción nueva. Rechazo, vencimiento o retirada del vínculo dejan un estado de error explícito. No se habilitan comandos sensibles.

## Verificación

| Comprobación | Resultado |
|---|---|
| JVM protocolo | PASS, 29 tests |
| JVM notificaciones | PASS, 22 tests (4 nuevos de BondWait) |
| JVM asistente desactivado | PASS, 1 test |
| Lint debug | PASS, 0 errores y 1 aviso |
| Compilación/assembleDebug | PASS |
| Firma e instalación/arranque watch | PASS |
| Avisos posteriores a esta actualización, H05 y R físicos | NOT_RUN |
| G1 | NOT_RUN como campaña formal |

Los resultados JVM del build final proceden de caché con los mismos inputs; los 52 tests se ejecutaron y pasaron en la validación anterior. Ver `evidence/windows-p1-update-2/windows/artifact.json`, `gradle-check.log` y `source-manifest.json`. El aviso de lint y sus reglas constan en artifact.json.

## Compilar en esta PC

AAPT2 enlaza el APK correctamente en una salida temporal local. La prueba mínima del mismo binario se bloqueó al escribir en F: y terminó en menos de un segundo usando disco temporal local. No se reinstaló el SDK ni se cambió AGP.

`scripts/windows/native-output.init.gradle` permite repetir esa distribución de salidas. Añadir `--no-configuration-cache --init-script scripts/windows/native-output.init.gradle` a Gradle; `-Pwristbridge.nativeBuildRoot=<directorio local absoluto>` permite elegir la salida. Sin override usa un temporal local por workspace. Las salidas de cada módulo quedan debajo de ese directorio, por ejemplo `apps/watch/outputs/apk/debug/watch-debug.apk`; copiar el artefacto validado a build/releases.

La caché global previa continúa retenida por procesos anteriores. La validación usó Gradle User Home `build/g3`, caché de proyecto `build/gradle-p1-project-2` y una copia de dependencias sin locks como seed de solo lectura. No borrar locks ni carpetas de compilación abiertas por procesos ajenos. Gradle usa Java 21; el observador de archivos está desactivado por el bloqueo nativo documentado.

Al terminar la instalación se confirmó arranque y proceso, pero System UI permanecía delante de la app. La visibilidad de la UI y activación del servicio quedan pendientes de desbloquear/abrir el reloj y pulsar Iniciar puente si aparece. No se registra ANCS listo ni entrega de avisos posteriores a la actualización sin observación.

Confirmación posterior del usuario: puente activo tras la actualización, PASS reportado. La recepción de nuevos avisos y los casos físicos formales siguen sin comprobarse en esta versión.

## Aceptación funcional posterior

El usuario declara terminada su validación de la fase y confirma conexión correcta y recepción continua de notificaciones en build 2: PASS reportado para el uso funcional descrito. Se conserva su aceptación. Duración y resultados individuales H/N/R no registrados; no se inventan métricas ni se sustituye la revisión conjunta del gate. El siguiente cambio local Android mejora la identificación visual del origen y de llamadas, sin introducir protocolo P2 ni controles remotos.
