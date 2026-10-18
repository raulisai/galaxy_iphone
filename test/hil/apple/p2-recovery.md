# P2 Apple · campaña de recuperación

Ejecutar sobre revisiones identificadas de ambas apps. Registrar versiones de SO, ajuste de recuperación, condiciones de permisos y solo metadatos sintéticos. No incluir nombres de cuentas/dispositivos, UUID personales o mensajes reales. Separar siempre canal de configuración iOS y avisos ANCS del reloj.

Estado de todos los casos de la nueva implementación: **NOT_RUN**. La observación del usuario del 18 de octubre corresponde a la versión ya instalada, no a esta entrega.

| Caso | Acción | Comprobación esperada |
|---|---|---|
| R-P2-01 | Autorizar y encontrar servicio; alejar reloj y acercarlo, app en primer plano | Solicitud pendiente y recuperación sin seleccionar otro par; medir latencia. |
| R-P2-02 | Repetir con iPhone bloqueado y app en segundo plano | Registrar por separado configuración y avisos; no asumir éxito de uno por el otro. |
| R-P2-03 | Detener configuración con conexión pendiente; acercar reloj y abrir app | Sigue detenido; no reaparece un enlace propio por callback antiguo. ANCS puede continuar. |
| R-P2-04 | Desactivar recuperación; desconectar enlace; reabrir app | Ajuste conservado y sin reintentos por fallo. Reconectar explícitamente funciona. |
| R-P2-05 | Apagar/encender Bluetooth desde Ajustes; repetir 20 ciclos | Recuperación autorizada no bloqueada por cancelaciones antiguas. Repetir detenido: no conectar. Distinguir Centro de control. |
| R-P2-06 | Servicio propio ausente o descubrimiento sin respuesta | Detener tras fallo/plazo de descubrimiento; no bucle inmediato. Hasta tres reintentos si iOS comunica fallos de conexión. |
| R-P2-07 | Quitar autorización con intento activo; recibir callback tardío | Sin reconexión al par retirado; volver a seleccionar exige ASK. |
| R-P2-08 | Terminación por sistema y relanzamiento por evento Bluetooth | Pendiente integración de arranque temprano; registrar evidencia, no simular cierre forzado como equivalente. |
| R-P2-09 | Cerrar app deslizando; enviar avisos sintéticos | Registrar ANCS y configuración por separado. Reporte previo PASS del usuario para recepción; restauración P2 no aprobada. |
| R-P2-10 | Reiniciar iPhone; antes y después del primer desbloqueo | Registrar disponibilidad, permisos y necesidad de abrir app; no prometer recuperación antes de desbloquear. |
| R-P2-11 | Cambiar ajuste automático y Detener; terminar/abrir app | Persisten; modo demo no modifica ajustes reales. |
| R-P2-12 | 24 h y después 72 h con estados/radios controlados | Medir pérdidas, duplicados, p50/p95 de latencia, desconexiones y batería con línea base; no inferir de un smoke test. |

Registrar PASS/FAIL/BLOCKED, hora de cada acción y revisión, repetir fallos sin restablecer el reloj. No declarar G2 hasta cumplir los criterios originales y revisar resultados con Windows.
