# Revisión Apple previa a P2-04 / P2-05

18 de octubre de 2026. Revisor Apple, perfil leído `contracts/ble-profile.json`, revisión `p1-lab-1`, base conjunta `0abcd51`. Estado: **BLOCKED para implementar codec o activar datos sensibles**. Esta revisión enumera decisiones pendientes; no define otro protocolo.

PASS: las cuatro UUID reservadas coinciden con el original y Swift; anuncio de servicio y filtro de nombre ASK coinciden con la propuesta del perfil. Comprobación automatizada en `scripts/apple/validate_structure.py`. El perfil expone únicamente el servicio primario en P1, por lo que no corresponde intentar lecturas o escrituras sobre las características reservadas.

Windows es editor del contrato y de fixtures compartidos. Para revisión y congelación conjunta, Apple necesita:

| Área | Decisión y evidencia que debe contener la propuesta |
|---|---|
| GATT | Propiedades, permisos y dirección de cada característica; suscripciones/CCCD y comportamiento al revocar permiso o perder el enlace. |
| Compatibilidad | Versión de sesión y negociación de capacidades; resultado ante cliente/servidor incompatible sin interpretar datos desconocidos como éxito. |
| Mensajes | Representación exacta, longitudes, límites, fragmentación/reensamblado, orden y finalización; vectores binarios de referencia. |
| Flujo | Modo de escritura, confirmaciones, colas máximas, presión de escritura y descarte/cancelación. Adaptar a capacidades efectivas de CoreBluetooth; no copiar el cálculo de MTU Android como límite Swift. |
| Recuperación | Identificación de solicitud/sesión, política de repetición y expiración si corresponde; qué sobrevive a una reconexión y qué se invalida. |
| Errores | Mensajes truncados, tamaños excesivos, entradas desconocidas y desconexión a mitad de mensaje; comportamiento acotado y reproducible. |
| Seguridad | Modelo de amenazas y autenticación del par, biblioteca/protocolo revisados, ciclo de vida de claves, cambio de identidad, sesión y replay. No asumir que observar el servicio equivale a identidad autenticada. |
| Fixtures | Vectores F01–F12 y expectativas idénticas Kotlin/Swift, incluidos fragmentos parciales y condiciones de error. |

Con esa entrega, Apple puede implementar codec y control de flujo contra vectores compartidos, después revisar Keychain/identidad y persistencia de pares. Hasta entonces solo opera recuperación del enlace al accesorio autorizado por ASK y ajustes locales. No se crean claves, mensajes, campos ni estados del reloj por anticipado; Swift tampoco interpreta ANCS.

El éxito físico de notificaciones reportado por el usuario respalda la ruta de avisos existente. No verifica estas características futuras ni cierra la aceptación de seguridad del canal propio.
