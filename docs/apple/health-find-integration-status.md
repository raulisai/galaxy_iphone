# Salud y «Buscar» · qué está hecho y qué falta para que funcionen de extremo a extremo

18 de octubre de 2026 · Apple (`development_codex`, 0.3.2 (9)). Respuesta a «¿qué falta para que salud y buscar teléfono funcionen al 100 %?». Estado vivo: [`SEED.md`](../../SEED.md).

**Respuesta corta:** el lado iPhone ya está listo en lo que no depende del canal; **ninguna de las dos funciones puede funcionar de extremo a extremo hasta que exista el canal propio iPhone ↔ reloj**, y ese canal hoy no existe en ninguno de los dos lados (el reloj no expone las características reservadas; el formato y la seguridad no están congelados). Eso no es un hueco de la app iPhone: es la puerta G2 del contrato.

## Hecho en el iPhone (PASS en simulador y pruebas del paquete; NOT_RUN en dispositivo)

| Pieza | Estado |
|---|---|
| **Libro de salud** `HealthLedger` + `HealthLedgerRepository` (paquete): idempotencia por `syncId`/`syncVersion`, rechazo de valores negativos/inválidos (nunca se fabrica actividad negativa), huecos sin interpolar, caducidad que impide resucitar datos viejos, cursor por métrica, retención 7/30/90, archivo protegido y fuera de copias, archivos ilegibles o futuros se conservan | PASS: 12 pruebas nuevas (S02–S06 del plan), 38 en total |
| **Regla de confirmación**: `ingest` devuelve solo tras guardar; un lote con rechazos no es «totalmente aceptado»; reenviar tras un fallo entre guardar y confirmar no duplica | PASS en pruebas (S03) |
| **Pantalla Salud** lee el libro: pasos, ritmo cardiaco (con rango), distancia, energía, gráficas del día, huecos y estado de sincronización; «Datos de muestra» solo en demostración, rotulado | PASS en pruebas de interfaz; revisión visual en simulador |
| **Buscar iPhone y Buscar reloj por avisos (ruta experimental, sin canal propio)**: «Buscar reloj» envía 8 avisos sonoros al reloj por ANCS; «Buscar iPhone» deja un aviso armado con botones «Hacer sonar/Detener» que el reloj recibe y, al pulsar, iOS despierta la app y suena la alarma (`FindViaNotifications`) | Implementado; **NOT_RUN en el par físico**. Depende de que el reloj muestre los botones y de que iOS permita iniciar audio desde segundo plano |
| **Buscar iPhone**: alarma real (audio de reproducción que ignora el interruptor de silencio, 60 s, se corta con Detener o una interrupción), prueba local desde la app | PASS en simulador (inicia/detiene); **audio audible no verificado** (sin dispositivo) |
| Permiso de segundo plano `audio` en `Info.plist` para que la alarma pueda sonar con la app en segundo plano cuando el reloj la pida | Añadido; `validate_structure.py` actualizado. **Decisión a confirmar**: Apple revisa el uso de este modo si se publica en la tienda. |
| Medición `att-capacity` en el diagnóstico (solo lectura) | Añadida; sin medir en un iPhone real |

## Lo que falta (por quién y en qué orden)

| # | Qué falta | Responsable | Bloquea |
|---|---|---|---|
| 1 | Elegir (A) o (B) para la carga por frame y cerrar las preguntas de [`p2-draft-response.md`](p2-draft-response.md) | Windows | Todo el canal |
| 2 | Medir `maxAttValueBytes`/capacidad en iPhone real (Q-A5); comportamiento del CCCD y del emparejamiento | Apple + usuario con el iPhone | Tamaño de lote |
| 3 | Revisión independiente de la seguridad (§8 del borrador) y elección de biblioteca Noise; sesión autenticada con identidad fijada | Ambos | **Cualquier dato de salud** y comandos sensibles |
| 4 | Contrato congelado: `contracts/` con esquemas, framing, capacidades; vectores F01–F12 aprobados por ambos | Windows edita, Apple revisa | Implementar el codec |
| 5 | **Codec/framer Swift** y control de flujo contra esos vectores, más Keychain para la identidad | Apple | Recibir cualquier mensaje |
| 6 | Reloj: exponer las características reservadas, sensores (Health Services, permisos por métrica), outbox con huecos, comando «buscar iPhone» y botón en su app | Windows | Datos y orden reales |
| 7 | Cableado Apple: cursor + lotes → `HealthStore.ingest` → confirmar solo si devuelve `true`; orden «buscar iPhone» → `FindPhoneAlarm.shared.start(reason: .watchRequest)` | Apple | Integración final |
| 8 | Copia opcional a Apple Salud con `HKMetadataKeySyncIdentifier`/`SyncVersion`, permiso por métrica | Apple (opcional) | — |
| 9 | Pruebas físicas: tres importaciones repetidas sin duplicados, crash en cada frontera, alarma con iPhone bloqueado y app en segundo plano | Ambos + usuario | Aprobar G7 / la función |

«Buscar» ya tiene una ruta experimental por avisos (arriba) que no necesita el canal propio; el comando definitivo por canal seguro (pasos 1–7) sigue pendiente y sustituiría esa ruta si se prueba mejor.

## Qué NO se ha hecho a propósito

No se creó un codec ni un formato de mensaje inventados (el contrato lo prohíbe hasta G2), ni claves, ni permisos de Apple Salud, ni datos simulados fuera de la demostración. Cualquier dato que aparezca en una build real vendrá del reloj a través del libro.

## Estado de evidencia

Todo lo marcado «físico» es NOT_RUN. Esta página no aprueba G2 ni G7.
