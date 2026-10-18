# Revisión Apple de ADR-001 · roles BLE

18 de octubre de 2026 · base integrada `5c79d64` · revisión documental de `docs/adr/ADR-001-roles-ble.md`. Editor de la ADR conjunta: Windows; esta respuesta no modifica su archivo ni aprueba G1.

**Posición Apple: de acuerdo con mantener A como camino de laboratorio y como recomendación condicionada.** La evidencia disponible no justifica cambiar a B. La decisión final sigue pendiente de la campaña conjunta identificada en la ADR.

## Evidencia del lado Apple

- Selector y avisos: reportes del usuario recogidos en `docs/apple/handoff-P2.md`, sin recuentos ni duración suficientes para cerrar H05/N/R.
- `evidence/apple-0.2.1/apple/run.json`: instalación y apertura PASS. La traza `physical-restoration-trace.json` muestra restauración, espera de radio y descubrimiento del servicio. Prueba el servicio propio en ese intento; no prueba ANCS ni un relanzamiento por el sistema en segundo plano.
- Se conserva el FAIL anterior del plazo de descubrimiento. Los cambios 0.3.1 de recuperación en vivo y 0.3.3 de diagnóstico requieren evidencia física propia, sin extrapolar el PASS de 0.2.1.
- Los logs/evidencias del reloj son aportación de Windows, no una prueba reejecutada en este Mac.

## Condiciones para la confirmación conjunta

1. Registrar versiones/commits de ambas apps y condiciones; no actualizar durante la campaña.
2. H05, R01–R04 y avisos N con cantidades, tiempos y observación del reloj. La campaña automática de 20 reconexiones iOS solo mide configuración en primer plano; no sustituye salir/volver al alcance ni verifica ANCS.
3. Distinguir app en segundo plano, eliminación por el sistema y cierre forzado. Una recuperación del servicio propio no puede activar «ANCS listo» en Swift.
4. Mantener `WristBridge` en el nombre para el filtro ASK actual. Aceptación Apple del nombre como condición del laboratorio, no solución definitiva de producto. Un filtro alternativo exige una prueba separada y cambio coordinado.
5. Ninguna característica P2 se activa con esta revisión. La contradicción de fragmentación y seguridad de la respuesta `p2-draft-response.md` siguen pendientes.

Estado de esta revisión documental: **PASS**. Confirmación física final de ADR-001 y G1: **BLOCKED por campaña pendiente**. No se requieren nuevas ramas ni implementación B mientras no aparezca evidencia que la justifique.
