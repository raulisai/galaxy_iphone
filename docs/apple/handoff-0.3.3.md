# iPhone 0.3.3 (10) · continuación del relevo

18 de octubre de 2026. Trabajo en `development_codex`, integración solicitada en `developer`. Base revisada: `5c79d64`, que incorpora los cambios Apple 0.3.1/0.3.2 y Windows 0.4.0-lab y su respuesta de salud P7. No se modifican Kotlin, contratos ni `Doc/**`.

## Lo que dejó el otro agente

- 0.3.1: recuperación al volver a primer plano y comprobación cada ocho segundos mientras la app está activa; pantallas Esferas/Salud/Buscar/Música. Esferas guarda solo una preferencia local; no instala una esfera en el reloj.
- 0.3.2: capacidad de escritura que informa CoreBluetooth tras encontrar el servicio, sin escribir características.
- Windows 0.4.0-lab: diario de campaña instalado en el reloj y propuesta de protocolo P2. Apple ya contestó Q-A1–Q-A10 y señaló una contradicción bloqueante en la fragmentación. El borrador sigue sin congelar; no autoriza un codec.
- Respuesta Windows P7: sensores presentes según inspección del reloj, sin ejecución de una app de salud de terceros. No demuestra permisos, recogida pasiva ni sincronización. Salud continúa bloqueada por G2 y sesión segura.

Revisión Apple de ADR-001 completada en [adr-001-review.md](adr-001-review.md): mantener A como recomendación condicionada; campaña final pendiente.

## Continuación Apple

- Texto de accesibilidad: acciones principales en una columna con texto completo; accesos en una columna y tarjetas de funciones más anchas al usar tamaños de accesibilidad.
- Reducir movimiento: sin giro continuo del botón, rebote, escala al pulsar ni onda de éxito; transacciones sin animación. Se conservan estados y etiquetas.
- Las líneas de tiempo decorativas se pausan cuando la escena no está activa. Fondo degradado limitado a 15 actualizaciones/s. Esto es un cambio de implementación, **no una medición de consumo**.
- Capacidad ATT guardada como dos eventos numéricos fechados en el diagnóstico protegido y acotado: `writeWithResponseCapacity` y `writeWithoutResponseCapacity`. La exportación identifica la capacidad como la del último enlace verificado, no como garantía de la conexión actual. No se infiere la capacidad de notificaciones ni se resta tres de nuevo.
- Regresión UI añade comprobación de acciones alcanzables y auditoría de texto recortado al tamaño máximo de accesibilidad.

## Validación local

PASS: originales y estructura; 26 pruebas Swift; build de simulador; regresión UI 8/8. Después del ajuste final de presentación, build aislado y repetición de las dos pruebas afectadas: 2/2 PASS (texto máximo con auditoría de recorte, navegación de las cuatro funciones). Captura del texto máximo inspeccionada. Release firmado y manifiesto de fuentes final verificados. Las ejecuciones preliminares interrumpidas no se contabilizan como aprobadas. Sin validación física de Reducir movimiento ni consumo.

## Comprobación física

Instalación final: **PASS**; apertura final: **BLOCKED**, el teléfono volvió a bloquearse. Un candidato 0.3.3 (10) anterior al último ajuste de presentación abrió y verificó el servicio (**PASS**); su binario y fuentes se identifican por separado en `physical-smoke-candidate.json`. No se atribuye ese PASS al binario final. Traza propia del 18 de octubre, 21:26:27 UTC: restauración → radio disponible → descubrimiento → capacidades 512/512 → `serviceVerified`. PASS del servicio en ese intento, sin atribuirle verificación ANCS ni relanzamiento en segundo plano por el sistema. El primer intento de apertura quedó bloqueado por el teléfono bloqueado y se repitió después de que el usuario lo desbloqueara.

## Pendientes por responsable

| Responsable | Pendiente | Estado |
|---|---|---|
| Apple + usuario | Ejecutar 20 reconexiones en primer plano y exportar informe; confirmar vuelta del reloj con la app abierta | NOT_RUN |
| Apple + Windows | Campaña G1/ADR-001 y reconexión conjunta; 24/72 h y batería con referencia | NOT_RUN |
| Windows, con revisión Apple | Elegir regla A/B de fragmentación, fijar esquema/propiedades/vectores; revisión independiente de seguridad | BLOCKED para implementación Swift |
| Apple tras contrato | Codec/backpressure Swift, identidad/Keychain y sesión autenticada con vectores cruzados | BLOCKED |
| Apple | Q-A5: 512 bytes con respuesta y 512 sin respuesta informados por CoreBluetooth; sin inferir capacidad Notify | PASS físico; traza fechada |
| Apple + usuario | Verificar ciclo de Keychain en una prueba aislada autorizada, sin desinstalar la app de uso | NOT_RUN |
| Apple tras G2 | Ajustes remotos, Buscar/Hacer sonar y transferencia de esferas según capacidades acordadas | BLOCKED |
| Apple + Windows tras G2 | ADR-009, ledger/retención y contrato/outbox de salud; permisos por capacidad | BLOCKED; propuesta de diseño solamente |

Los resultados efectivos de build, instalación y apertura se registran en `evidence/apple-0.3.3/apple/run.json`. Ningún resultado de simulador aprueba G1/G2.

## Prueba para el usuario

1. Abrir Ajustes dentro de WristBridge y comprobar versión **0.3.3 (10)**.
2. Con el reloj conectado, entrar en Reloj → Reconexión e iniciar los 20 intentos. Mantener la app visible y compartir el informe al terminar. Mide configuración, no avisos ANCS.
3. Apagar y encender el reloj con WristBridge abierta y comprobar que vuelve el servicio sin relanzar la app. Registrar cuánto tarda y probar un aviso por separado.
4. En Ajustes de iOS, probar Texto más grande y Reducir movimiento; comprobar que los botones siguen accesibles. Compartir diagnóstico sin mensajes para obtener las capacidades medidas.
