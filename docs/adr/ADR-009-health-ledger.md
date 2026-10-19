# ADR-009 · Libro de salud del iPhone, retención y confirmación

19 de octubre de 2026 · Apple redacta, Windows revisa la parte del outbox del reloj. Estado: **propuesto**; implementado en iPhone 0.4.0 (`HealthLedger`, `HealthLedgerRepository`, `HealthStore`, `HealthSyncClient`).

## Contexto
El Watch7 captura pasos (instantánea diaria) y ritmo cardiaco con Health Services. El canal propio (P2) los entrega al iPhone. El plan exige idempotencia, confirmación solo tras guardar, retención controlable y privacidad (P7-02, P7-04, S01–S06).

## Decisiones
1. **Libro local propio, no Apple Salud.** La pantalla Salud lee un archivo JSON en Application Support, protegido (`completeFileProtectionUntilFirstUserAuthentication`), excluido de copias, escritura atómica. Apple Salud (HealthKit) es una copia **opcional y posterior**, por métrica y bajo demanda; hoy no se pide ningún permiso.
2. **Identidad = `syncId`** (ASCII 1–128, estable en cada reenvío); `syncVersion` mayor reemplaza, igual es duplicado, menor se ignora. Sin deduplicación por contenido ni caché de 500 ids.
3. **Pasos como instantánea diaria (`stepsDaily`).** El total del día es la instantánea **más reciente** de ese día (por `end`); nunca se suman. Se aceptan también deltas (`steps`, `distance`, `activeEnergy`). Un contador que se reinicia se guarda como nuevo valor no negativo; nunca se resta ni se fabrica actividad negativa.
4. **Validación estricta y rechazo con motivo** (`invalidIdentifier`, `invalidInterval`, `invalidValue`, `unitMismatch`, `expired`, `ledgerFull`). Ritmo cardiaco 20–250 con `min ≤ valor ≤ max`. Un lote con algún rechazo **no se confirma**.
5. **Confirmar solo tras guardar.** `HealthStore.ingest` devuelve `true` únicamente si todo el lote es válido y está escrito en disco; `HealthSyncClient` guarda el cursor y recién entonces envía `ack(through: last)`. Una caída entre guardar y confirmar se resuelve reenviando (idempotente, S03).
6. **Cursor persistido por reloj.** Un reloj nuevo (clave estática distinta) reinicia el cursor. El iPhone pide `after = cursor`; si la respuesta no repite ese `after`, se detiene.
7. **Huecos.** Se representan (`HealthGap`) y se muestran como «sin datos»; nunca se interpola.
8. **Retención 7/30/90 días**, la misma que el resto de datos locales, aplicada al abrir y al cambiar el ajuste. Lo caducado se **rechaza** también al llegar, para que un reenvío viejo no lo resucite (S04).
9. **Borrado.** «Borrar datos locales» (Ajustes → Privacidad) elimina el libro y el cursor; «Olvidar reloj» no borra datos ya guardados.
10. **Archivo ilegible o de versión futura:** se conserva, se avisa y se deja de escribir; el usuario decide borrarlo.
11. **Interruptor del usuario.** «Recibir datos de salud del reloj» (apagado por defecto): apagado, el iPhone rechaza y no confirma.

## Consecuencias
- El reloj conserva su outbox hasta el `ack`; el tope y los marcadores de hueco son de Windows (7 días ≈ 2 000 intervalos por métrica).
- Cambiar la semántica de pasos (deltas en vez de instantáneas) no exige migrar el libro: ambas coexisten.
- Datos sensibles: el canal propio no está revisado (K3); hasta entonces solo datos propios del usuario.
