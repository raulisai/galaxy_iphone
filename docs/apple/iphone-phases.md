# Entrega iPhone y estado de todas las fases

18 de octubre de 2026 · `development_codex` · candidato **0.2.0 (5)**. Alcance autorizado: completar el trabajo iPhone que permita el plan mientras el teléfono está desconectado. El paquete firmado está preparado; no se declara instalado ni aceptado físicamente por haber compilado. No se editó Kotlin, Gradle, contratos compartidos ni documentos originales.

## Entregado en la app

- Conexión: autorización con AccessorySetupKit, selección entre accesorios ya autorizados, reconexión/detención/retirada, estado Bluetooth y comprobación del servicio separados de avisos.
- Restauración: inicialización desde la señal de relanzamiento Bluetooth, periféricos retenidos hasta resolver autorización ASK, rechazo de restauración detenida/no autorizada, descubrimiento después de radio disponible y consultas serializadas. El reinicio normal no convierte caché local en autorización. Continúa pendiente la campaña física.
- Pruebas: avisos sintéticos propios, metadatos de programación y observación iOS separados, resultados manuales por condición. Un reporte manual no cambia el estado automático ANCS. Los reportes solo se muestran asociados al accesorio que ASK autorice; su identificador privado nunca sale en la exportación.
- Ajustes: recuperación, retención 7/30/90 días, diagnóstico exportable sin mensajes, borrado explícito de datos locales. El borrado detiene configuración y retira los avisos sintéticos propios; retirar autorización es una acción separada. La app no puede borrar exportaciones que el usuario compartió fuera de ella.
- Persistencia: ajustes v2, migración desde flag legado/v1, resultados propios atómicos y protegidos, límites de 500 observaciones/1000 eventos sintéticos/64 pasos Bluetooth. Archivos futuros o corruptos se conservan, se informa el error y no se programan más lotes sobre un registro ilegible. Borrarlos requiere la acción explícita de Privacidad. Retención se ejecuta al abrir/cambiar ajuste, no con un proceso permanente.
- Entrega: Release firmado, comprobación de firma, huellas de binario/fuentes, inventario de dependencias Apple y scripts de preparación/instalación. No se publica ni se presume distribución App Store.

## Todas las fases del plan

| Fase | Parte del iPhone entregada | Qué impide cerrar la fase |
|---|---|---|
| P1 · Avisos | Configuración, permisos, fixtures, diagnóstico e instalación de laboratorio previa. El usuario reportó recepción incluso tras cerrar deslizando. | Consolidar G1/ADR-001, revisión exacta del APK y campaña conjunta completa; la captura de timeout anterior se conserva como FAIL. |
| P2 · Fiabilidad | Recuperación/restauración, ajustes versionados, observaciones y almacenamiento local. | Windows aún no entrega wire format/propiedades/vectores F01–F12 ni seguridad revisada. No implementar un codec incompatible. Faltan 20 ciclos y 24/72 h reales. |
| P3 · Experiencia | Tres pantallas SwiftUI, permisos por función disponible, recuperación guiada, privacidad y resultados manuales fechados. | Ajustes sincronizados y estado reportado por el reloj requieren canal P2. AMS/música son del reloj; los estímulos iPhone se describen en la campaña. G3 depende de G2. |
| P4 · IA | Revisión de responsabilidad: el iPhone no es cliente/proxy obligatorio. No hay credenciales iOS que gestionar en la arquitectura actual. | ADR de proveedor/ruta soportada y decisión conjunta de si iPhone participa; implementación principal Kotlin. No inventar autenticación móvil ni importar historial ChatGPT. |
| P5 · Voz/herramientas | Sin relay de audio iPhone. Ejecutor Apple no asignado en el MVP. | Implementación watch, ADR y capacidades aprobadas/canal seguro si se requiere un ejecutor iPhone. |
| P6 · Mac | No corresponde a esta aplicación iPhone; es otra app. | Trabajo independiente macOS y cliente watch, después de G2. No se crea por confundir «ecosistema Apple» con app iPhone. |
| P7 · Salud/contactos | Controles de datos propios disponibles; no se activaron permisos sensibles ni se fingió sincronización. | G2, sesión segura, esquema/tipos/ACK de dominio y outbox/snapshot Kotlin. Sin ello HealthKit/Contacts no constituyen una integración terminada. |
| P8 · Entrega | Candidato iPhone firmado/reproducible, regresión local, migraciones de ajustes, inventario y reporte. | Instalación/aceptación de este candidato, compatibilidad conjunta, batería de varios días y gates originales. No se declara G8. |

El archivo `evidence/apple-offline-completion/apple/task-status.json` conserva los **60 IDs** originales, responsabilidad Apple y estado individual. `run.json` distingue resultados PASS, FAIL, BLOCKED y NOT_RUN; «implementado» y «gate aprobado» no son sinónimos.

## Preparación e instalación

Preparar desde Xcode/firma local ya configurada:

```sh
bash scripts/apple/prepare-release.sh /tmp/WristBridge-iPhone-0.2.0
```

El directorio externo contiene el `.app` firmado y logs locales. No se suben perfiles, identidades o registros de firma a Git. `candidate.json` y `source-manifest.json` sí son informes sin identificadores personales.

Cuando el iPhone vuelva a estar conectado y desbloqueado:

```sh
python3 scripts/apple/install-candidate.py /tmp/WristBridge-iPhone-0.2.0 --launch
```

El instalador verifica firma y huella antes de instalar, no publica ni borra datos automáticamente. La actualización conserva preferencias y migra v1 a v2; desde Privacidad se puede reiniciar el almacenamiento propio. La app 0.1.1 (4) previamente instalada no se convierte en 0.2.0 porque exista este paquete.

## Aceptación que sigue siendo física

1. Abrir candidato, autorizar/recuperar accesorio, confirmar servicio real y recepción de avisos por separado.
2. Ejecutar `test/hil/apple/p2-recovery.md`: cierre normal, cierre deslizando, radio, revocación, distancia, reinicio y cancelación pendiente; distinguir relanzamiento del sistema de apertura manual.
3. Completar estímulos de `test/hil/apple/following-phases.md` cuando la contraparte los implemente, sin etiquetar funcionalidades futuras como aprobadas.
4. Medir duración/batería con baseline y tiempos reales. Un simulador o una pantalla correcta no cierra esos casos.
