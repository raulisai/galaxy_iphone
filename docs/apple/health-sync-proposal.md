# Propuesta P7 · datos de salud del reloj en el iPhone

18 de octubre de 2026 · Apple (`development_codex`) · **propuesta para revisión conjunta, no implementada ni validada.** Cambia el contrato solo cuando Windows la acepte. Estado vivo: [`SEED.md`](../../SEED.md).

## Respuesta corta

- **No se puede leer la salud del Galaxy Watch desde el iPhone por las apps de Samsung.** Samsung Health, su Data SDK y Health Connect son del teléfono Android; los datos del reloj llegan allí y no hay acceso desde iOS ([Samsung](https://developer.samsung.com/health/blog/accessing-samsung-health-data-through-health-connect), [Terra](https://docs.tryterra.co/faq/help-topics/connecting/apple-health-health-connect-and-mobile-sdk/samsung-health-android-only-routes)).
- **La única vía viable es la app del reloj (Kotlin) leyendo sus propios sensores y enviándolos al iPhone por el servicio BLE propio.** Wear OS exige Health Services con pasos y frecuencia cardiaca pasivos, medidos solo por sensores del reloj ([Android](https://developer.android.com/health-and-fitness/health-services/compatibility)). Qué métricas expone el Watch7 a terceros se comprueba en el reloj con `Capabilities` en tiempo de ejecución; no está verificado.
- **El diseño más eficiente: el iPhone pide por cursor, el reloj responde por lotes agregados, el iPhone guarda y solo entonces confirma.** Primero se guarda en un libro local de la app (que alimenta la pantalla Salud sin permisos de Apple); escribir en Apple Salud es un paso opcional posterior, por métrica.
- **Hoy no se puede validar de extremo a extremo:** no existe el lado del reloj, el formato de mensaje ni la sesión segura (G2 pendiente). La pantalla Salud mostrará «—» y bloqueado hasta entonces; no se simulan datos.

## Opciones consideradas

| Opción | Veredicto |
|---|---|
| Samsung Health / Health Connect / Data SDK en el iPhone | Descartada: solo Android. |
| El reloj empuja cada muestra al iPhone | Descartada: gasta batería, pierde datos si el iPhone no está, duplica al reconectar. |
| **Cursor + lotes agregados (el iPhone pide)** | **Recomendada**: eficiente, reanudable, idempotente. |
| Volcado completo diario | Descartada: reenvía lo ya guardado y rompe totales acumulados. |
| iCloud/servidor intermedio | Descartada: sube datos sensibles a terceros sin necesidad. |

## Flujo recomendado

1. **Reloj (Kotlin):** registra pasos y frecuencia cardiaca con Health Services pasivo y los agrupa en intervalos (p. ej. 5 min). Guarda en un *outbox* local con `syncId` estable. No envía por su cuenta salvo que el iPhone lo pida.
2. **iPhone al conectar o restaurar el enlace:** envía `{tipo, cursor}` con el último `syncId` confirmado. La restauración de Core Bluetooth ya reactiva la app si iOS la relanza.
3. **Reloj:** responde con lotes ordenados, de tamaño acotado por `maxAttValueBytes`, con índice y fin de lote.
4. **iPhone:** valida, guarda en el libro persistente y **solo entonces** confirma el cursor (recibido ≠ guardado, contrato §5). Reenviar es idempotente: mismo `syncId` y `syncVersion` no crea otra muestra.
5. **Opcional, por métrica:** copia a Apple Salud con `HKMetadataKeySyncIdentifier` y `SyncVersion` ([Apple](https://developer.apple.com/documentation/healthkit/hkmetadatakeysyncidentifier)), tras pedir permiso solo de esa métrica.

## Modelo de datos (propuesto)

`deviceId`, `tipo` (pasos, distancia, energía, ritmo cardiaco), `inicio`/`fin`, `valor` y `unidad`, `fuente` (sensor del reloj), `syncId` = `deviceId + tipo + inicio + ancho`, `syncVersion`.
- Pasos y energía: **deltas por intervalo**. El total diario nunca se importa como delta; el reinicio del contador, el cambio de zona y la medianoche no producen valores negativos (casos S05).
- Frecuencia cardiaca: mínimo/promedio/máximo por intervalo, más muestras sueltas solo si hacen falta.
- Estimación propia (no medida): 24 h × 3 métricas en intervalos de 5 min son ~860 registros; en JSON rondan decenas de KB y en binario compacto unos pocos KB, es decir segundos de enlace. Por eso el contrato sugiere JSON para control y evaluar protobuf para salud.

## Lo que mostrará la app

Pantalla Salud: tarjeta por métrica con estado **disponible / no soportada / no autorizada** según evidencia, último valor y hora de su última sincronización, procedencia («reloj») y gráfica diaria (Swift Charts) desde el libro local. Controles de retención y «borrar todo». Nada se muestra sin dato real.

## Requisitos de seguridad y privacidad

- Ningún byte de salud viaja hasta aprobar la sesión autenticada de P2 y su revisión independiente.
- Permisos por métrica y bajo demanda; denegación parcial o revocación no afecta avisos (S01).
- El libro es persistente (no 500 IDs en `UserDefaults`), excluido de copias de seguridad y borrable (S04, P7-04).

## Qué necesito de Windows (preguntas abiertas)

1. ¿El Watch7 expone pasos y ritmo cardiaco pasivos vía Health Services a una app de terceros? ¿Hace falta el Samsung Health Sensor SDK o una aprobación de Samsung?
2. Esquema de mensaje, cursor, tamaño de lote y confirmación en `contracts/**` (editor: Windows, revisión Apple) y vectores cruzados.
3. Frecuencia de agregación y tope del outbox del reloj; qué pasa al llenarse.
4. ADR-009 (ledger y retención) antes de P7-02.

## Orden de trabajo cuando se abra el gate

Apple: P7-01 permisos por capacidad → P7-02 libro + ACK tras guardado + pruebas S01–S06 → pantalla Salud con datos reales → P7-04 retención/borrado → copia opcional a Apple Salud. Windows: sensores, outbox, cursor/lotes. Cada paso se registra PASS/FAIL/BLOCKED/NOT_RUN en `evidence/`.

## Estado

NOT_RUN en todo lo anterior. Esta propuesta no activa HealthKit, Contactos ni mensajes BLE nuevos.
