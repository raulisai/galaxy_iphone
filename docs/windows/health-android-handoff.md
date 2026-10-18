# Salud Android → Apple · entrega local 0.5.0-lab

18 de octubre de 2026. El usuario pidió avanzar salud y conservar Swift para la otra computadora. Se implementa la captura **local y optativa**; no se modifica el contrato ni se abre una transferencia sensible. G2 y la revisión de seguridad siguen pendientes.

## Entregado en el reloj

- Health Services 1.1.0, capacidades consultadas en ejecución, permisos independientes por métrica.
- Pasos: `STEPS_DAILY`, una instantánea acumulada diaria del proveedor, **no un delta por intervalo**. No sumar dos instantáneas ni importarlas directamente como muestras incrementales en HealthKit.
- Pulso: `HEART_RATE_BPM`, muestras pasivas con la hora del sensor; la entrega puede llegar en lotes.
- SQLite privado en `noBackupFilesDir`, identidades basadas en métrica, arranque, tiempo monotónico y valor, deduplicación, límite 4 000 filas / 7 días. Descartes visibles por contador; todavía no hay marcador de hueco intercambiable.
- Borrado local con desactivación de ambas métricas; permisos retirados impiden guardar nuevos puntos sin afectar ANCS.
- Registro de servicios de salud mediante JobScheduler tras arranque/actualización, separado del servicio BLE.
- Pantalla Salud con valores reales o «Sin datos», fecha, permisos, retención y estado explícito de envío pendiente.

No se activa por instalar. El usuario decide en Salud qué permisos conceder. No se leen Samsung Health, ECG, BIA, contactos ni valores de otras apps. No hay nuevo servicio en nube ni nueva app de teléfono.

## Qué falta para compartir y completar P7

1. Apple revisa el borrador P2 y se acuerdan formato, propiedades GATT, sesión autenticada y revisión de seguridad. No hay características P2 en el reloj actual.
2. Acordar semántica de pasos: convertir las instantáneas a deltas con límites de día/reinicio/huecos, o aceptar instantáneas explícitas. La representación local no congela el formato de intercambio.
3. Implementar agregación de cinco minutos y outbox de intercambio: `syncId/syncVersion`, lotes/cursor, huecos de retención, confirmación tras guardado, reenvío e idempotencia. La tabla local actual es preparación y NO equivale al outbox P7 completo.
4. La otra computadora implementa el libro Swift, reconciliación de ACK y presentación; HealthKit es opcional por métrica.
5. Ejecutar S01–S10 entre ambos dispositivos. Permisos reales, llegada de muestras y transferencia están pendientes hasta observarlos; una prueba SQLite sintética no los aprueba.

Fuentes de API: [datos pasivos](https://developer.android.com/health-and-fitness/health-services/monitor-background), [permisos por API](https://developer.android.com/health-and-fitness/health-services/permissions), [capacidades](https://developer.android.com/health-and-fitness/health-services/compatibility).

## Límite conocido

La recepción se guarda en una transacción antes de devolver el callback del servicio; el registro de sensores usa un ejecutor separado. No se promete captura sin pérdida ante cierre forzado/muerte del proceso durante una entrega. El coste de lotes grandes y el consumo siguen pendientes de campaña física. Las instantáneas de pasos permiten recuperar el siguiente total disponible, pero no reconstruir un intervalo de pulso perdido. No debe declararse sincronización clínica ni campaña completa.
