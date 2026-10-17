# Campaña Apple P1 · no ejecutada

Usar esta guía con el catálogo original; no reemplaza sus expectativas ni las pruebas Kotlin. Operador con ambos dispositivos presentes; únicamente datos sintéticos. Estado de los casos físicos: **NOT_RUN**. Xcode ya está disponible y la app compila; instalación física pendiente de iPhone detectado y firma propia. Ver `docs/apple/validation-xcode.md`.

## Precondiciones y revisión

- Mismo commit integrado para las apps, versión/hash de APK y build iOS, perfil BLE y camino A identificados. No hay aún commit compartido en este checkout.
- Registrar OS/build real de iPhone y reloj, modelos, vínculo anterior sin identificar dispositivos, radios, Enfoque, previsualizaciones y permisos. No resetear ni borrar el bond anterior por rutina.
- Confirmar nombre de publicidad `WristBridge` con Windows y UUID reservado `6f06b8b1-21d9-4c65-9d7e-118880e36a01`.
- Desactivar exploradores BLE u otras apps de laboratorio que compitan. Una sola ruta A/B activa.
- Generar una carpeta `evidence/<run>/apple`; incluir metadata de versión, operador seudónimo, casos/estados y motivo de lo no ejecutado. Exportar registro de fixtures y diagnóstico de ambas apps sin contenido real.

## H01–H04 · instalar, seleccionar y negar

1. H01: instalar ambas builds y contrastar versión/revisión/checksum. **PASS solo si corren las builds previstas**, no por existir los archivos del proyecto.
2. H02: abrir selector limpio; seleccionar Watch7 correcto; observar autorización ASK y conexión. Registrar por separado bond, permiso de compartir notificaciones y suscripciones ANCS confirmadas en Kotlin.
3. H03: cancelar selector y repetir. Probar denegación/revocación disponible en esa versión de iOS. La app debe permitir reintento voluntario sin pedir permisos repetidamente.
4. H04: desactivar compartir avisos si aparece o impedir suscripción ANCS en el laboratorio. La app nunca indica ANCS listo. El servicio propio conectado no cambia ese resultado.

## Emisión y fronteras

1. En **Laboratorio de avisos**, autorizar los avisos propios de WristBridge. Esto no autoriza ANCS para todas las apps.
2. Usar ID único, por ejemplo `mvp1042-a`. Programar diez avisos: empieza a los diez segundos y separa solicitudes tres segundos. Bloquear el iPhone antes del primero.
3. Registrar cada `runId/fixtureId` en observación iPhone, evento ANCS, atributos completos y tarjeta. El plan incluye texto breve, tildes/emojis y cuerpo >1024 bytes.
4. Tras la ventana, volver a la app y tocar **Observar avisos entregados**. Esa consulta solo registra los avisos propios que siguen en el centro en ese momento. Un ausente puede haber sido retirado o suprimido: marcarlo indeterminado si no existe otra observación.
5. Exportar registro antes de superar 1000 registros locales. `scheduleAccepted` no prueba entrega; `foregroundCallback` prueba callback; `deliveredSnapshot` prueba presencia en el centro en la consulta, no presentación en watch. `cancelled` documenta una solicitud de cancelación, no que no haya habido carrera con entrega.
6. Repetir con diez IDs nuevos para completar cien solicitudes en tres sesiones. Comparar **emisión realmente observada**; no usar diez lotes como cien entregas demostradas.
7. Para N03 y otras apps, provocar estímulos sintéticos explícitos en tres apps disponibles y conservar observación independiente. Para N04–N06, usar un origen que permita actualización/retirada real del mismo aviso; descartar manualmente desde centro de notificaciones cubre retirada. El generador actual no automatiza Modified ni una respuesta ANCS tardía.

## H05–H09 · ciclo de vida

| Caso | Condición | Evidencia requerida |
|---|---|---|
| H05 | Volver a inicio, bloquear iPhone; UI watch cerrada y pantalla dormida ≥30 min | ANCS y tarjetas observadas sin UI visible. Sesión total ≥2 h. |
| H06 | Suspender y volver normalmente | ANCS y canal propio evaluados por separado; diagnosticar estado real. |
| H07 | Terminación por sistema, con procedimiento controlado y registrado | Causa de terminación documentada; no confundir simulación con presión real de memoria. |
| H08 | Deslizar fuera del selector de apps; abrir después | Condiciones ASK/iOS anotadas; recuperación al abrir sin corrupción. No usarlo como prueba H06. |
| H09 | Revocar y restaurar permisos/radios durante sesión | Estado degradado y reintento voluntario; sin crash ni tormenta de prompts. |

## R01–R04 · recuperación conjunta

- R01: veinte salidas/regresos al alcance. Medir con reloj monotónico del mismo dispositivo. El objetivo original es 19/20 en 30 s; la reconexión manual del spike no cuenta como recuperación automática aprobada.
- R02: Bluetooth off/on desde Ajustes en cada dispositivo. Diferenciar Control Center y modo avión. El spike pide Reconectar después de apagado; registrar esa limitación.
- R03: Service Changed real o inyectado con método indicado. Confirmar redescubrimiento propio en iOS y ANCS en Kotlin; no basta un log.
- R04: cortar enlace durante consulta de atributos. Verificar en watch que el trabajo viejo no contamina nueva sesión. Swift no implementa ese parser.

## Cierre G1

Consolidar U01–U20 de Windows, H01–H09, N01–N08 y R01–R04 con estado individual. La app Swift actual prepara estímulos y diagnóstico, no completa todo el MVP. No hay aceptación hasta demostrar Added/Modified/Removed, permisos, pantallas dormidas, recuperación y contadores por frontera. ADR-001 se cierra con el resultado A/B; si ninguno funciona en segundo plano, parar ampliación y documentar la frontera del bloqueo.

Para batería posterior: retirar depuradores/carga y ADB inalámbrico, usar baseline y campañas AB/BA. No inferir autonomía de esta prueba funcional.
