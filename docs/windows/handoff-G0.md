# Handoff Windows · G0 y laboratorio P1

Actualizado el 17 de octubre de 2026 (America/Mexico_City). Primera entrega Kotlin. Rama de trabajo `claude/android-studio-windows-setup-672676`, creada desde `development_codex` (`c573a82`). No se modificó código Swift, proyectos Xcode, firma Apple ni `Doc/**`.

## Revisión y artefactos

| Campo | Valor |
|---|---|
| Commit del código | `4e06b70c1e4c`. El APK muestra ese commit en la primera línea de la app (`BuildConfig.GIT_COMMIT`, sin `-dirty`). |
| SHA-256 del APK | `fa66f53075c2185d026c01e683f656fdd279ebf5d3f8430f8d7fa7079a03c8f3` (35 357 182 bytes). Detalle en `evidence/windows-g0/windows/build.json`. |
| APK | `apps/watch/build/outputs/apk/debug/watch-debug.apk`, `dev.wristbridge.watch` 0.1.0-lab (1). Firma debug local de Android Studio; ninguna clave en Git. |
| Instalación | ADB inalámbrico, ver [installation.md](installation.md). Sin reset del reloj. |
| Contrato | Documento 1.0; `contracts/ble-profile.json` revisión `p1-lab-1`. Formato P2 y propiedades GATT **no congelados**. |
| Camino | A: reloj periférico y servidor GATT propio, iPhone central; reloj cliente GATT de ANCS. B queda como alternativa de ADR-001. |

## Herramientas

Windows 11 Pro, Temurin 21.0.12, Gradle 9.8.1, AGP 9.4.1, Kotlin 2.4.21, compileSdk 37, targetSdk 36, minSdk 33. Detalle y fuentes en [environment.md](environment.md). Destino físico: Galaxy Watch7, versión de Wear OS **pendiente de inventario** (NOT_RUN).

## Capacidad

| Pieza | Estado |
|---|---|
| Publicidad del UUID `6f06b8b1-…-36a01` (anuncio) y nombre del sistema en la respuesta de escaneo | Implementado; no probado en reloj. |
| Nombre `WristBridge` para el filtro ASK | Implementado como acción explícita y reversible del operador (renombra el adaptador del reloj y guarda el original). **Confirmación de la propuesta Apple:** el reloj puede anunciar `WristBridge`, pero solo por esta vía; Android no permite un nombre de anuncio distinto del nombre del sistema. Pendiente ADR-001. |
| Servidor GATT con el servicio primario propio, sin características | Implementado. Las características reservadas no se exponen en P1. Lecturas y escrituras responden `REQUEST_NOT_SUPPORTED`. |
| Cliente ANCS: MTU, descubrimiento, CCCD Data Source y luego Notification Source, vínculo ante error de autenticación | Implementado; no probado en reloj. |
| Cola GATT serial con plazos, transacción única de atributos, generaciones, Service Changed | Implementado y probado en JVM. |
| Reductor Added/Modified/Removed, backlog silencioso, tarjetas nativas Wear OS, ocultar contenido | Implementado; tarjetas no probadas en reloj. |
| Servicio foreground `connectedDevice`, `START_STICKY` | Implementado; H05/H07/H08 no ejecutados. |
| Reconexión: espera en segundo plano (`autoConnect`) tras vincular y recuperación manual | Implementado; R01–R04 no ejecutados. |
| Acciones ANCS (positiva/negativa) | Codificadas y probadas (U04); **desactivadas** en la UI hasta P2-07. |
| Canal propio P2, codec, seguridad, IA, AMS, salud | No implementado (fuera de gate). `core:assistant-domain` existe vacío y desactivado. |

## Pruebas

`.\gradlew.bat :core:protocol-kotlin:test :core:notification-domain:test :core:assistant-domain:test :apps:watch:lintDebug :apps:watch:assembleDebug`: **48 pruebas PASS**, lint sin errores (un aviso `DataExtractionRules` aceptado: backup desactivado).

| IDs | Estado | Dónde |
|---|---|---|
| U01–U12 | PASS (JVM) | `NotificationSourceParserTest`, `AncsCommandsTest`, `DataSourceAssemblerTest` |
| U13–U14 | PASS (JVM) | `GattOperationQueueTest`, `AncsSessionEngineTest` |
| U15–U20 | PASS (JVM) | `AncsSessionEngineTest` |
| N01–N08 | PASS solo lógica JVM; **NOT_RUN físico** | `AncsSessionEngineTest`; el oráculo físico requiere iPhone |
| Consistencia de contrato (UUID frente a `Doc/01` y JSON) | PASS | `ContractConsistencyTest` |
| H01–H09, R01–R04, N físicos, ADR-001 | NOT_RUN | Requieren Watch7 + iPhone ([campaña](../../test/hil/windows/p1-campaign.md)) |

El APK compila; eso **no** aprueba G0 conjunto ni G1.

## Dependencia con Mac

1. Confirmar que el filtro ASK acepta el nombre en la **respuesta de escaneo** y el UUID en el anuncio. Si no, acordar datos de servicio y máscara en ADR-001 (cambio común de contrato).
2. Con el reloj anunciando: selector, emparejamiento LE y permiso de compartir avisos. El reloj registra por separado vínculo, Data Source confirmado y «Listo».
3. Emitir fixtures `runId · F00n`. El reloj registra `fixture=runId/F00n` en logcat para correlacionar sin contenido.
4. Misma revisión integrada en ambos lados durante la campaña. No había commit integrado común al cerrar esta entrega: falta llevar esta rama a `development_codex`/`main`.

## Siguiente acción Windows

Inventario del reloj por ADB (P1-01.K), instalar el APK (H01) y ejecutar el camino A con el iPhone. Si A falla, documentar el punto exacto y abrir B en rama aislada. No avanzar a P2 sin G1.

## Actualización instalada · 18 de octubre de 2026

WristBridge Watch 0.1.1-lab (2) compilado, firmado, instalado por actualización -r y abierto en SM-L300: PASS. Checksum instalado coincide con el APK validado. 52 tests JVM PASS (el build final reutiliza sus resultados con inputs idénticos), lint 0 errores/1 aviso. No requiere actualizar la app iPhone; no cambia el perfil P1. H05/R y G1 formales pendientes. Ver [release-0.1.1-lab.md](release-0.1.1-lab.md) y evidence/windows-p1-update-2/windows. AAPT2 se resolvió generando salidas en temporal local, conservando las salidas anteriores bloqueadas de F:.
