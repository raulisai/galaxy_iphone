# iPhone 0.2.1 (6) · integración en developer

18 de octubre de 2026. Trabajo en `development_codex`; el usuario pide enviar todos los cambios e integrarlos en **`developer`**, rama remota existente. Sus commits y los de `development`/`development_claude` eran ancestros del trabajo Apple al empezar; no se modifica Kotlin ni se reescribe su historial. `main` se conserva como historia anterior. El resultado remoto final se comprueba después del push.

## Cambio

Pruebas iPhone con lotes de 10/25/50 y espera inicial de 10/30/60 segundos (30 por defecto). Un lote completo prepara otro ID sintético; ante denegación, fallo o aceptación parcial se conserva el ID para investigar. El ID del último lote permanece visible y se recupera del registro propio al reabrir. No cambia el límite de 60 solicitudes pendientes, los datos sintéticos ni el significado de los contadores.

También se limpian selecciones antiguas de accesorios cuando ASK invalida la sesión. Se mantiene el código de recuperación/restauración 0.2.0 y las guardas de autorización/radio. No se crean características BLE ni mensajes propios sin contrato.

Instalación y apertura en iPhone: PASS. La traza propia capturada después de abrir muestra `restorationReceived → waitingForRadio → radioChanged(5) → discoverySubmitted(5) → discoveryCompleted → serviceVerified`: PASS del servicio propio restaurado en ese intento. No prueba recepción ANCS ni relanzamiento en segundo plano por el sistema.

## Qué probar

[Guía rápida para el usuario](../../test/hil/apple/quickstart-0.2.1.md): versión, servicio propio, diez avisos bloqueado, otro lote tras cerrar deslizando y detener/reconectar. Solo después, lote de 25/50. Registrar observaciones honestas y compartir el diagnóstico si no recupera el servicio.

## Validación y límites

Resultados, huellas y estado de instalación en `evidence/apple-0.2.1/apple/run.json`. Las pruebas locales no validan BLE físico, ANCS ni duración. El contrato compartido continúa en `p1-lab-1`; P2 seguro, salud/contactos, extensiones dependientes del reloj y gates conjuntos permanecen como indica `iphone-phases.md`.

El binario firmado y sus logs locales se guardan fuera de Git en `/tmp/WristBridge-iPhone-0.2.1`. Solo se publican informes sin identidad de firma/dispositivo. Usar `prepare-release.sh` e `install-candidate.py`; no publicar una IPA de desarrollo como distribución universal.
