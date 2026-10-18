# Traspaso Windows → Apple · P2

18 de octubre de 2026. Responde a [docs/apple/p2-contract-review.md](../apple/p2-contract-review.md). G1 no está aprobado; nada de aquí cambia `p1-lab-1`.

## Lo que Windows entrega y te afecta

| Entrega | Qué significa para Apple |
|---|---|
| Propuesta P2 en [contracts/proposals/p2-channel-draft.md](../../contracts/proposals/p2-channel-draft.md) | Borrador NO CONGELADO con una respuesta a cada fila de tu tabla (GATT, compatibilidad, mensajes, flujo, recuperación, errores, seguridad, fixtures F01–F12). No implementar codec ni claves hasta aprobar. |
| ADR-001 actualizado | Evidencia física del lado Windows; recomienda confirmar A, sin decidir (revisión Mac y campaña formal pendientes). |
| Acciones ANCS y controles AMS | Son del **reloj**. Swift no interpreta ANCS ni AMS, no recibe confirmaciones de ellos y no debe exponer nada equivalente. Las acciones se muestran como «solicitado»; el efecto final lo confirma el iPhone con `Removed`/`Modified`. |
| Diario de campaña | Ver [campaign-journal.md](campaign-journal.md) para el registro de pruebas y observaciones. |
| Límites de compatibilidad | [compatibilidad-iphone.md](compatibilidad-iphone.md): sin audio ni micrófono de llamada, sin responder mensajes de otras apps, «abrir en el iPhone» solo vía canal propio. |

## Lo que Windows necesita de Apple

1. Revisión del borrador P2 fila por fila, y respuesta a las preguntas Q-A1 a Q-A10 de su sección 10 (método de emparejamiento observado, intercalado de mensajes, interpretación de los 256 KiB, capacidades medidas en el iPhone real, bibliotecas, ciclo de vida del Keychain, modo sin AEAD).
2. Coautoría de los vectores F01–F12: Windows propone el formato del manifiesto; Apple añade sus vectores en una rama de revisión; ningún resultado esperado se genera con el encoder bajo prueba.
3. Confirmación de la lectura del filtro ASK con el nombre `WristBridge` (abajo) y de ADR-001.
4. Revisión de la sección de seguridad como diseño candidato **sin revisar**; sin su aprobación los comandos sensibles siguen desactivados.

## Hechos que Apple debe conocer

- **Nombre anunciado.** Android no permite un nombre de anuncio distinto del nombre Bluetooth del sistema. El selector solo encuentra el reloj si su nombre contiene `WristBridge`; el operador lo cambia con el botón «Nombre WristBridge» (reversible con «Restaurar nombre»). Si el nombre vuelve al original, el selector responde «No accessory found». Alternativa a evaluar: datos de servicio y máscara en el filtro.
- **Generación.** El reloj numera cada sesión ANCS con una generación (`gen=N`); los callbacks de generaciones anteriores se ignoran y desde 0.3.0-lab la generación persiste entre reinicios del servicio. Tras un `PreExisting` nuevo, las tarjetas anteriores se retiran y el backlog se reconstruye en silencio. Es estado interno del reloj; no se transmite por el canal propio.
- **Service Changed.** El reloj rehace su enlace cliente GATT de ANCS/AMS (máx. 3 intentos automáticos hasta «Listo») cuando llega Service Changed o el redescubrimiento no responde. Esto puede ocurrir durante la negociación de MTU. Cualquier cambio de la base GATT en el iPhone o cualquier indicación de servicio provoca esa reconstrucción; no es un fallo del canal propio.
- **CCCD persistente.** Cerrar un cliente GATT no corta el enlace físico e iOS conserva el CCCD activo del par vinculado; por eso el reloj deshabilita y rehabilita Notification Source al recuperar. Para el canal propio se propone no tratar el CCCD como señal de sesión.
- **Perfil P1.** El reloj solo expone el servicio primario; las características reservadas devuelven `REQUEST_NOT_SUPPORTED` y no existen en el GATT hasta que se congele P2.
- **Permiso de ANCS.** El iPhone concede ANCS solo tras el vínculo y con «Compartir notificaciones del sistema» activo para el reloj; un error del canal propio no debe tocar ese enlace.

## Estado de evidencia (honesto)

| Elemento | Estado |
|---|---|
| Camino A con ANCS, AMS y tarjetas en Watch7 + iPhone | PASS observado (logs y observación del usuario); no es campaña formal |
| H05, R01–R04, 24/72 h | NOT_RUN |
| G1 | NO aprobado |
| Cualquier característica o mensaje del canal propio | NOT_RUN / sin implementar |
