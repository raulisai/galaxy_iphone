# ADR-001 · Roles BLE entre Watch7 e iPhone

Estado: **Propuesto; pendiente de revisión del Mac y de la campaña formal**. Editor: Windows. Revisión requerida: Mac. G1 **no** está aprobado.

## Contexto

El reloj debe consumir ANCS del iPhone (Kotlin) y disponer de un canal propio separado para configuración. Doc/01 §1 define A como preferido y B como alternativa, y exige elegir con Watch7 + iPhone reales.

## Opciones

- **A:** reloj periférico con servidor GATT del servicio `6f06b8b1-21d9-4c65-9d7e-118880e36a01`; iPhone central mediante AccessorySetupKit/CoreBluetooth. El reloj abre además un cliente GATT al servicio ANCS del iPhone sobre el mismo enlace y, tras vincular, se reconecta en segundo plano.
- **B:** iPhone periférico del servicio propio y reloj central.

## Implementación en curso

Ambos lados implementan A en la revisión de laboratorio (Windows `p1-lab-1`, Apple `development_codex`). Hechos de diseño ya conocidos:

- Android no permite un nombre de anuncio distinto del nombre Bluetooth del sistema. El reloj pone ese nombre en la respuesta de escaneo y ofrece renombrar el adaptador a `WristBridge` de forma explícita y reversible.
- El UUID de 128 bits va en el anuncio. Anuncio con UUID y nombre juntos excede los 31 bytes.

## Evidencia física del lado Windows

Fuente: `docs/windows/SEED.md`, `docs/windows/release-0.2.0-lab.md`, `docs/windows/android-p1-pairing.md`, `evidence/windows-p1-update-*/windows/` y `docs/apple/handoff-P2.md`. Hardware: Galaxy Watch7 (Wear OS 6, API 36) con iPhone. Las filas «observado» proceden de logs del reloj o de la observación del usuario; el agente no midió tiempos ni cantidades salvo donde se indica. Nada de esto sustituye la campaña formal.

### Camino A: lo que funcionó

| Punto | Resultado | Evidencia |
|---|---|---|
| Selector de AccessorySetupKit encuentra el reloj | PASS con el nombre Bluetooth del reloj cambiado a `WristBridge` | Observado por el usuario |
| Anuncio del UUID propio y servidor GATT mínimo | PASS en dispositivo | Log `WristBridge`: «Servicio iniciado», «Anunciando servicio … nombreValido=true» |
| Vínculo LE con el iPhone | PASS | Log Bluetooth del reloj: `BOND_BONDING => BOND_BONDED` |
| ANCS: Data Source y Notification Source confirmados | PASS | Log: «Data Source confirmado», «ANCS listo gen=1» |
| Tarjetas con avisos sintéticos (F001–F005) | PASS parcial | Log: «Tarjeta mostrada … fixture=run-…/F001» |
| Backlog (PreExisting) tras recuperar | PASS observado por logcat: 11 avisos previos reconstruidos en silencio, 3 reconocidos como llamadas perdidas | `release-0.2.0-lab.md` (sección 0.2.1) |
| Service Changed | Manejo implementado y observado en el reloj; ver problemas | `SEED.md`, evidencia 0.2.1 |
| AMS (Apple Media Service) | PASS observado: sesión AMS lista tras ANCS; con el reproductor inactivo el iPhone solo anunció volumen arriba/abajo | `release-0.2.0-lab.md` |
| Reanudación del puente tras actualizar la app o reiniciar | PASS observado en cuatro instalaciones seguidas con el reloj bloqueado | `release-0.2.0-lab.md` |

El reloj opera como periférico/servidor GATT del servicio propio **y** como cliente GATT de ANCS/AMS sobre el mismo enlace, que es lo que exige el camino A. El usuario además reportó, con procedencia «reporte del usuario» y sin cantidades ni duración, recepción de avisos con el iPhone suspendido y después de cerrar WristBridge (`docs/apple/handoff-P2.md`).

### Problemas observados y arreglos

| Problema | Causa observada | Arreglo | Estado |
|---|---|---|---|
| Suscripción ANCS expiraba mientras el usuario aceptaba el emparejamiento | Escritura del descriptor vencida durante el diálogo del sistema | Espera de vínculo acotada a 60 s (BondWait) y nueva generación GATT al completar el vínculo | Arreglo instalado (0.1.1-lab); caso con emparejamiento nuevo NOT_RUN |
| «Recuperar conexión» dejaba el reloj sin avisos | El enlace físico seguía abierto y el CCCD de Notification Source seguía activo; iOS no repetía los avisos existentes y se retiraban las tarjetas | Con el iPhone vinculado, se deshabilita y rehabilita el CCCD (también en AMS) para que iOS reenvíe | Arreglo instalado (0.2.1-lab); repetir «Recuperar» NOT_RUN |
| Service Changed durante la petición de MTU dejaba ANCS atascado | El redescubrimiento en el mismo cliente no recibía respuesta | Service Changed, o un descubrimiento sin respuesta, rehace el enlace cliente (máx. 3 intentos automáticos hasta «Listo») | Arreglo instalado (0.2.1-lab); R03 formal NOT_RUN |
| El selector respondía «No accessory found» | El nombre Bluetooth del reloj no contenía `WristBridge` | Acción explícita y reversible «Nombre WristBridge» | Condición de uso vigente |
| Cuerpo sintético largo llegó con 256 bytes | Sin determinar si lo recorta iOS o nuestro límite | Pendiente | N02 no se da por aprobado |

Lado Apple, para contexto (no es evidencia Windows): un intento físico de descubrir el servicio propio venció su plazo local de 20 s (**FAIL de ese intento**, causa sin establecer); un intento posterior con 0.2.1 restauró y verificó el servicio propio (`docs/apple/handoff-P2.md`, `docs/apple/handoff-0.2.1.md`). El perfil P1 no expone aún las características reservadas.

### Brechas pendientes

| Caso | Estado |
|---|---|
| H05: iPhone bloqueado, UI cerrada, pantallas dormidas ≥30 min | NOT_RUN |
| R01: salir/volver al alcance ×20 | NOT_RUN |
| R02: Bluetooth off/on en cada dispositivo | NOT_RUN |
| R03: Service Changed formal | NOT_RUN |
| R04: corte del enlace durante una consulta | NOT_RUN |
| Campaña 24/72 h | NOT_RUN |
| Casos físicos de las builds 0.2.0–0.3.1 (llamadas, acciones, música, UI) | NOT_RUN |
| Avisos reales de otras apps con duración y recuentos | NOT_RUN |
| Revisión Mac/Apple de esta ADR y de las evidencias de su lado | Pendiente |

## Decisión

**Recomendación (no decisión): se propone confirmar A.** Fundamento: en Watch7 + iPhone reales A obtuvo vínculo LE, ANCS (Data Source y Notification Source) y AMS confirmados, tarjetas visibles y reconstrucción del backlog, y no apareció un bloqueo que obligue a probar B. La decisión se confirma solo cuando existan la revisión del Mac y la campaña formal (H05, R01–R04, casos N físicos) con la misma revisión integrada en ambos lados. Si A pierde ANCS autorizado en segundo plano durante esa campaña, se documenta el punto exacto y se prueba B en una rama aislada; si ninguna funciona, se detiene la ampliación (plan §6.3).

Pendiente de confirmación conjunta: el nombre anunciado `WristBridge` (Android solo lo logra renombrando el adaptador del reloj; alternativa: datos de servicio y máscara en el filtro ASK).
