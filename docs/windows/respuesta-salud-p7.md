# Respuesta Windows a la propuesta de salud P7

18 de octubre de 2026. Responde a `docs/apple/health-sync-proposal.md`, sección «Qué necesito de Windows». Las comprobaciones salen de inspeccionar un Galaxy Watch7 real (SM-L300, Wear OS 6, API 36) por ADB, sin instalar ni ejecutar código de salud. Estado: **respuesta de diseño; nada implementado ni probado en una app de terceros** (P7 sigue bloqueado por G2, contrato y sesión segura).

## 1. ¿Expone el Watch7 pasos y ritmo cardiaco a una app de terceros?

**Sí, por las APIs estándar de Android; con los datos vistos es viable sin el SDK de Samsung.** Pendiente confirmar con una app real que el permiso se concede y que el muestreo pasivo en segundo plano funciona.

| Dato | Qué hay en el reloj | Permiso | Veredicto |
|---|---|---|---|
| Pasos | Sensores estándar `android.sensor.step_counter` y `step_detector` | `ACTIVITY_RECOGNITION` | Viable |
| Ritmo cardiaco | Sensores estándar `android.sensor.heart_rate` (lotes y no-wakeup) | `android.permission.health.READ_HEART_RATE` | Viable |
| Health Services de Google | Paquete `com.google.android.wearable.healthservices` instalado | permisos de Health Services | Camino recomendado para lo pasivo |
| Health Connect en el reloj | Instalado (`com.android.healthconnect.controller`) | grupo `HEALTH` | Posible, no necesario |
| ECG, impedancia (BIA), acelerómetro 32G | Sensores propios de Samsung | `com.samsung.permission.SSENSOR` (permiso privilegiado de Samsung) | **No disponible** para terceros sin acuerdo con Samsung; fuera de alcance |

Conclusiones:
- No hace falta el Samsung Health Sensor SDK ni aprobación de Samsung para pasos ni ritmo cardiaco. Sí haría falta para ECG, BIA o composición corporal: no se proponen.
- La app Samsung Health del reloj (`com.samsung.android.wear.shealth`) usa los mismos sensores; la app WristBridge no leería de ella.
- Distancia y energía activa no aparecen como sensores: saldrían de Health Services (estimados) y no se prometen hasta comprobarlo en ejecución.
- Coste que Apple debe saber: añadir `androidx.health:health-services-client` y permisos nuevos en el manifiesto del reloj. Esa biblioteca no está en la caché de dependencias offline de esta PC; se descargará cuando se abra el gate.
- La app pedirá los permisos por métrica y bajo demanda en el reloj (no se piden hoy).

## 2. Esquema de mensaje, cursor, lote y confirmación

De acuerdo con el flujo de Apple (cursor + lotes agregados; el iPhone confirma solo tras guardar). No se define aquí un formato aparte: se integra en el borrador `contracts/proposals/p2-channel-draft.md` como un tipo de solicitud/evento más, con `syncId`/`syncVersion` idempotentes. Se concretará, junto con los vectores cruzados, cuando Apple conteste las preguntas abiertas de ese borrador. Tamaño de lote acotado por la capacidad efectiva de ATT medida en el iPhone real, que sigue sin medirse.

## 3. Agregación y tope del outbox

Propuesta (no medida en hardware):
- Intervalos de 5 minutos, igual que Apple; pasos y energía como deltas, ritmo cardiaco como mínimo/promedio/máximo.
- Outbox persistente en el reloj (base de datos local, fuera de copias de seguridad) con tope de **7 días ≈ 2 000 intervalos por métrica**.
- Al llenarse: se descartan los intervalos más antiguos y se deja un **marcador de hueco** (inicio, fin, cantidad) que viaja con el siguiente lote, para que el iPhone muestre «sin datos» en vez de interpolar. Nunca se descarta en silencio y nunca se recorta lo reciente.
- Un registro solo se borra del outbox tras el ACK de guardado del iPhone (recibido no equivale a guardado); un reinicio o una pérdida del ACK reenvía el mismo `syncId` sin duplicar.
- El muestreo pasivo respeta el ahorro de energía: sin sondeo continuo del sensor.

## 4. ADR-009 (libro y retención)

De acuerdo en que debe existir antes de P7-02. Propuesta de reparto: el libro y la retención en el iPhone los redacta Apple; Windows revisa la parte del outbox del reloj (tope, huecos, ACK). Windows no empieza código de sensores hasta que se abran G2 y el contrato.

## Lo que no cambia

Sin permisos de salud, sin datos de salud en el canal y sin código de sensores hasta los gates. Esta respuesta no activa nada.
