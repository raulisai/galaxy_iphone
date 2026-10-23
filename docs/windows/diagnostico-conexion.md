# Diagnóstico de conexión y registros · reloj 0.6.6-lab (23-oct-2026)

## Hallazgo principal: un fallo fatal que mataba el puente
En el buffer de fallos del reloj hay **4 cierres fatales el 21-oct (14:57, 14:58, 16:14 y 20:04)**, todos iguales:

`IllegalArgumentException: notification should not be longer than max length of an attribute value` en `BluetoothGattServer.notifyCharacteristicChanged` ← `P2Channel.pump` (hilo `wristbridge-ble`).

**Causa:** el iPhone negocia MTU 517; el reloj fijaba el tamaño de frame en MTU−3 = 514 bytes, y Android rechaza notificaciones de más de 512 bytes. Al enviar un lote de salud (varios frames llenos) la excepción mataba **todo el proceso**: caían ANCS, AMS y el canal propio a la vez, y por eso, estando en la calle, "se desincroniza y no vuelve a sincronizar". Al reiniciar, el reloj reabría el puente, pero el iPhone ya tenía un cursor de salud y una sesión que el reloj había perdido.

## Correcciones
| # | Cambio |
|---|---|
| 1 | Frame máximo 512 bytes (`min(MTU−3, 512)`); un frame mayor cierra solo la sesión P2, no el proceso |
| 2 | El hilo BLE ya no puede morir por una excepción: se registra y continúa (`dispatchMessage` seguro) |
| 3 | **Registro persistente**: todo evento, advertencia, error, cierre fatal y motivo por el que el sistema terminó el proceso (`ApplicationExitInfo`) se guarda en `wb.log` (4 × 256 KiB, sobrevive reinicios) |
| 4 | Se registran cambios de vínculo (BONDED/BONDING/NONE), MTU y desconexiones del enlace ANCS con su código |
| 5 | **Resincronización de salud**: si el iPhone pide un cursor mayor que el que tiene el reloj (reloj reiniciado, reinstalado o base borrada), el reloj responde `reset:true` y envía desde 0 en lugar de dejar ambos lados atascados. Hasta ahora esto obligaba a "borrar todo" |
| 6 | `diag.logs`: el iPhone puede pedir páginas del registro por el canal seguro |

## Cómo sacar los registros
- **PC:** `bash scripts/windows/pull-watch-logs.sh` (con la depuración inalámbrica activa) deja `wb*.log`, `system-crash.log` y `bluetooth.log` en `evidence/watch-logs/<fecha>/`.
- **Reloj:** Ajustes › Diagnóstico › «Registro guardado» muestra el tamaño.
- **iPhone:** capacidad `diag.logs` (abajo, tarea para Apple).

## Por qué no se vinculaba (causas posibles, con su registro)
Del lado del reloj hay tres rechazos legítimos y cada uno deja ahora una línea en `wb.log`:
1. **Ventana de registro cerrada** (`E_NOT_AUTHORIZED`): hay que pulsar «Vincular iPhone» en el reloj *antes* (dura 120 s).
2. **iPhone distinto** del vinculado (`E_IDENTITY_CHANGED`): el reloj conserva la clave del primer iPhone; hay que «Olvidar iPhone» en el reloj para vincular otro, y borrar el accesorio en el iPhone.
3. **Código no coincide / no se confirma a tiempo**: se descarta sin guardar nada.
Además, tras el cierre fatal (arriba) el reloj perdía la sesión sin avisar al iPhone, y éste seguía creyendo que estaba vinculado. Con el registro persistente la próxima vez se verá el motivo exacto.

## Tareas para Apple (K11)
| ID | Tarea |
|---|---|
| A-DIAG1 | Botón «Pedir registro del reloj»: enviar `{kind:"req", requestId, capability:"diag.logs", payload:{page:k, bytes:4000}}` para k = 0…pages−1 (0 es lo más reciente), unir el texto en orden cronológico y ofrecer «Compartir» (archivo `wristbridge-reloj-<fecha>.log`) |
| A-DIAG2 | Persistir un registro del iPhone equivalente (eventos P2, CB, restauración, errores con código) con rotación y «Compartir diagnóstico» que incluya ambos |
| A-DIAG3 | Cursor de salud: si el lote trae `reset:true`, reiniciar el cursor local a 0 y aceptar las muestras (idempotentes por `syncId`); no detenerse |
| A-DIAG4 | Mostrar el motivo cuando el reloj cierra la sesión (`E_NOT_AUTHORIZED`, `E_IDENTITY_CHANGED`, `E_BAD_TAG`) y ofrecer «Olvidar reloj» + «Volver a vincular» sin borrar datos de salud |
| A-DIAG5 | Detectar y registrar cuando iOS elimina el accesorio o cambia el UUID del periférico |

## No verificado (NOT_RUN)
El cierre fatal se reproduce por análisis del código y del stack; no hay prueba física con el 0.6.6. Las pruebas JVM cubren el reinicio de cursor y `diag.logs`. Falta la prueba en la calle con el registro nuevo.
