# ADR-001 · Roles BLE entre Watch7 e iPhone

Estado: **Propuesto, pendiente de evidencia física**. Editor: Windows. Revisión requerida: Mac.

## Contexto

El reloj debe consumir ANCS del iPhone (Kotlin) y disponer de un canal propio separado para configuración. Doc/01 §1 define A como preferido y B como alternativa, y exige elegir con Watch7 + iPhone reales.

## Opciones

- **A:** reloj periférico con servidor GATT del servicio `6f06b8b1-21d9-4c65-9d7e-118880e36a01`; iPhone central mediante AccessorySetupKit/CoreBluetooth. El reloj abre además un cliente GATT al servicio ANCS del iPhone sobre el mismo enlace y, tras vincular, se reconecta en segundo plano.
- **B:** iPhone periférico del servicio propio y reloj central.

## Implementación en curso

Ambos lados implementan A en la revisión de laboratorio (Windows `p1-lab-1`, Apple `development_codex`). Hechos de diseño ya conocidos:

- Android no permite un nombre de anuncio distinto del nombre Bluetooth del sistema. El reloj pone ese nombre en la respuesta de escaneo y ofrece renombrar el adaptador a `WristBridge` de forma explícita y reversible.
- El UUID de 128 bits va en el anuncio. Anuncio con UUID y nombre juntos excede los 31 bytes.

## Decisión

Sin decidir. Se completa con: capturas de estados de ambos lados, tiempos, resultado de vínculo, permiso de compartir avisos, suscripción confirmada, avisos con el iPhone bloqueado y las UI cerradas. Si A no logra ANCS autorizado en segundo plano, documentar el punto exacto y probar B en una rama aislada. Si ninguna funciona, se detiene la ampliación (plan §6.3).
