# Candidato Android 0.5.0-lab (11)

18 de octubre de 2026. Continúa el trabajo pendiente de Claude desde `0573588` y conserva sus cambios locales de CallStyle y versión. Swift y `Doc/**` no se modifican.

## Fallo confirmado

El buffer de fallos del Watch7 instalado con 0.4.0-lab muestra repetidamente `IllegalArgumentException` en `CardPresenter.render`: el sistema rechaza CallStyle en una notificación que no cumple sus requisitos. La excepción llegaba al hilo BLE y terminaba el proceso. Una llamada existente se podía volver a recibir al reconectar y repetir el fallo.

Se integra la corrección pendiente: tarjeta normal con categoría de llamada, acceso a su pantalla y acciones publicadas por ANCS, sin CallStyle/fullScreenIntent. Se contiene un fallo de presentación para mantener el puente. Una llamada de Telegram real continúa pendiente de regresión; no se infiere su entrega de un aviso sintético.

## Prioridad de llamadas

Las llamadas entrantes nuevas se consultan antes de los avisos acumulados. Se respeta la transacción ANCS activa y las acciones del usuario; no se cancela una respuesta en curso. Una llamada PreExisting mantiene el orden normal y una llamada retirada deja la cola. La consulta activa aún puede introducir demora hasta su plazo.

## Recuperación

- Plazos de conexión directa (30 s) y espera automática (90 s).
- Reintentos con espera 2/5/15/30/60 s, tope de espera de 60 s y sin abandono definitivo tras tres fallos. La espera de conexión automática puede añadir hasta 90 s por intento.
- Stop/revocación cancelan intentos; READY reinicia el presupuesto.
- Al agotar una operación GATT se descarta el cliente/generación para que un callback tardío no confirme otra escritura con el mismo UUID.
- Se guarda solo el último par autorizado, en almacenamiento local excluido de backup, y se comprueba que siga vinculado al restaurar. La app no selecciona cualquier dispositivo vinculado.
- Servicio y radio se recuperan sin depender de abrir la pantalla. Abrir la UI también reanuda un puente habilitado si el servicio no está ejecutándose.

El estado del canal de configuración en la app iPhone sigue siendo responsabilidad Swift. No se declara ANCS listo por una conexión de ese canal.

## Salud

Captura local optativa de pasos diarios/pulso, almacenamiento y pantalla. Alcance y pendientes para compartir: [handoff de salud](health-android-handoff.md). **La transferencia al iPhone no está implementada ni habilitada.**

## Validación

Resultados y artefacto: `evidence/windows-update-11/windows/`. Actualizar con los resultados finales antes del cierre. G1/G2/S01–S10 no se aprueban por compilación o pruebas sintéticas.
