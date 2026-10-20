# Optimización de batería · reloj 0.6.5-lab (19-oct-2026)

## Medición base (reloj Galaxy Watch7, 3 h 4 min con el puente activo, `dumpsys batterystats`)
- CPU de la app: ~42 s en 3 h (≈0,4 %). Wake locks propios: ninguno sostenido (96 breves del sistema al publicar avisos, 2,4 s en total).
- `HealthDataService`: 76 arranques en 3 h (lotes pasivos de pulso que decide Health Services; la app no controla la frecuencia).
- Servicio en primer plano `connectedDevice` continuo (necesario para ANCS/AMS).
- BLE: el reloj seguía **anunciando (cada 1 s) aun conectado**; consumo de radio no medible con `batterystats` en este dispositivo (NOT_RUN: campaña de 24 h).

## Cambios (todos mantienen el comportamiento)
| # | Cambio | Efecto |
|---|---|---|
| 1 | Anuncio BLE `LOW_POWER` (antes `LOW_LATENCY`) | Menos radio al esperar al iPhone |
| 2 | **Se detiene el anuncio mientras hay un iPhone conectado** y se reanuda al desconectarse | Quita radio continuo en el caso normal |
| 3 | Prioridad de conexión `LOW_POWER` cuando ANCS está listo | Intervalos de conexión relajados |
| 4 | Temporizadores de pantalla (última recepción, progreso de música, salud, vinculación) solo con la pantalla encendida (`repeatOnLifecycle`) | Antes seguían despertando la CPU con la pantalla apagada |
| 5 | Pantalla Salud refresca cada 5 s (antes 3 s) y solo visible | Menos lecturas |
| 6 | Depuración de la base de salud como máximo cada 10 min (antes en cada lote y en cada refresco) | Menos escrituras a flash |
| 7 | Reintentos de notificación P2 acotados (20, con espera creciente) | Evita un bucle si el iPhone deja de estar suscrito |
| 8 | Sesión P2: no hay sondeos; el reloj solo responde cuando el iPhone pide | Sin consumo en reposo |

## Lo que no se puede reducir sin perder función
Servicio en primer plano, ANCS/AMS suscritos y el muestreo pasivo de pulso/pasos (lo hace Health Services). Apagar «Pulso» en Salud es el mayor ahorro opcional del usuario.

## Pendiente de medir (NOT_RUN)
Consumo real en 24 h con y sin salud, y con anuncio detenido; verificar que el iPhone reconecta igual de rápido tras desconexión con anuncio `LOW_POWER`. Reactivar la depuración inalámbrica para instalar la 0.6.5-lab (19) y medir: `adb shell dumpsys bluetooth_manager | grep -A8 "Ongoing advertising"` debe mostrar solo el anuncio del sistema mientras está conectado.

## Tareas de batería para Apple (iPhone)
| ID | Tarea |
|---|---|
| A-BAT1 | Sin escaneo continuo ni temporizadores con la sesión P2 inactiva; conexión pendiente con `CBConnectPeripheralOptionNotifyOnConnectionKey` y restauración de estado |
| A-BAT2 | Cerrar la sesión P2 tras ~2 min sin uso y reabrirla bajo demanda (la búsqueda sin sesión usa la ruta ANCS) |
| A-BAT3 | Agrupar la lectura de salud: pedir lotes al conectar y como máximo cada 15 min, no por muestra |
| A-BAT4 | `FindPhoneAlarm`: liberar `AVAudioSession` y el motor al terminar (ya lo hace); no mantener audio activo en reposo |
| A-BAT5 | Medir con Instrumentos (Energy Log) una hora con la app en segundo plano y anotar el resultado en `evidence/` |
