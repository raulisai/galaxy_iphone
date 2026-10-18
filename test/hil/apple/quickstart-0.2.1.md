# Pruebas que debe hacer el usuario · iPhone 0.2.1 (6)

Usar WristBridge en iPhone y Galaxy Watch7, sin restablecer el reloj ni quitar el vínculo que ya recibe avisos. Comprobar en iPhone → WristBridge → Ajustes que la versión sea **0.2.1 (6)**. Con una versión anterior, estos controles pueden no aparecer.

## 1. Comprobar configuración

Abrir WristBridge también en el reloj, mantenerlos cerca y mirar Conexión en iPhone. Pulsar Reconectar **una vez** si está desconectado. Esperar hasta 30 segundos.

Anotar el texto de Configuración y el mensaje que aparece debajo. «Servicio de configuración encontrado» comprueba solo el servicio propio; los avisos se evalúan en la prueba siguiente. Si no avanza, ir a Ajustes → Compartir diagnóstico sin mensajes. No seguir pulsando ni borrar el accesorio para ocultar el fallo.

## 2. Diez avisos con iPhone bloqueado

En Pruebas, autorizar los avisos propios si falta permiso. Elegir **10 avisos** y **30 segundos**, y pulsar Programar 10 avisos una sola vez. Confirmar el mensaje con 10/10 solicitudes aceptadas y conservar el **ID del último lote**. El campo de ID se prepara para un lote nuevo solo tras aceptación completa.

Volver a inicio y bloquear el iPhone antes de esos 30 segundos. Esperar unos 90 segundos y contar los avisos sintéticos distintos en el reloj. Comprobar texto corto, acentos/emoji y aviso largo; no confundir agrupación visual del reloj con pérdida.

En Pruebas → Lo que observaste, elegir «Avisos con el iPhone bloqueado» y guardar Funcionó/Falló/No se pudo probar según el resultado. Enviar el ID del lote y cuántos de 10 se observaron. No enviar contenido real de otras apps. «Solicitudes aceptadas» y «observados en el centro» no son contadores de recepción en el reloj.

## 3. App cerrada deslizando

Programar otro lote de 10 con espera de 30 segundos. Cerrar WristBridge deslizando fuera del selector, bloquear y repetir el conteo. Registrar la condición «Avisos después de cerrar la app deslizando». Este resultado no prueba que iOS relanzó la app ni recuperó su canal de configuración.

## 4. Detener y reconectar configuración

Con el servicio propio encontrado, pulsar Detener configuración. Debe decir Detenido y seguir así al volver a abrir la app. Después pulsar Reconectar una vez y anotar si recupera el servicio. La llegada ANCS se comprueba por separado; detener configuración no promete detener los avisos del reloj.

Guardar la condición «Recuperación de configuración» y su resultado real. Si falla, compartir diagnóstico desde Ajustes; contiene pasos y códigos, no mensajes ni identificadores del par.

## 5. Lote grande, solo después de lo anterior

Esperar a que termine el lote anterior o cancelarlo. Elegir 25 o 50 avisos; con 50, esperar aproximadamente cuatro minutos incluyendo el margen inicial. No iniciar varios lotes grandes simultáneos: la app conserva el límite de 60 solicitudes pendientes.

## Reporte mínimo

Versión iPhone y revisión/versión del APK si se conoce; texto de Configuración; ID y cantidad observada por lote; condición bloqueado/cierre deslizando; resultado detener/reconectar. Un reporte parcial se registra como parcial. Radios, distancia, revocación y duración 24/72 h siguen en `p2-recovery.md`; estas pruebas rápidas no cierran G1/G2.
