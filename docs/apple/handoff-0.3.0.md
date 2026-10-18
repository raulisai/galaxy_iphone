# iPhone 0.3.0 (7) · ensayo de reconexión

18 de octubre de 2026 · `development_codex` · sin commit. Estado vivo del proyecto: [`SEED.md`](../../SEED.md).

## Cambio
Sección «Ensayo de reconexión» en Conexión: 20 desconexiones/reconexiones del canal de configuración, máximo 30 s por intento, con la app en primer plano y servicio ya verificado. Se persiste solo el último ensayo (`Application Support/WristBridge/recovery-campaign.json`), con retención 7/30/90 días y borrado desde Privacidad. Salir de la app, perder autorización o radio, o cambiar de accesorio lo interrumpe; un proceso cerrado a mitad se marca interrumpido y no se reanuda.

Código: `RecoveryCampaign` y `RecoveryCampaignRepository` (paquete), `RecoveryCampaignController`, `RecoveryCampaignSection`, enganches en `BridgeModel`/`ContentView`.

## Rediseño de interfaz
Tres pestañas inspiradas en Galaxy Wearable con lenguaje iOS 26: **Reloj** (reloj redondo dibujado en SwiftUI cuya esfera refleja el estado local, píldora de estado, acciones rápidas en vidrio, estado del enlace, accesos a Notificaciones/Reconexión/Diagnóstico/Guía), **Notificaciones** (antes Pruebas) y **Ajustes** (cabecera del reloj, recuperación, ensayo, diagnóstico, privacidad). «Próximamente» lista esferas, salud, buscar reloj y música como bloqueadas: no se simulan datos sin canal seguro ni gates. La comprobación automática de avisos sigue mostrando «Sin comprobar».

## Animaciones
Sistema compartido en `Views/Motion.swift`. El reloj respira con el color del estado, ilumina las marcas al ritmo del segundero, muestra radar y un arco giratorio al conectar, y lanza una onda con vibración al verificar el servicio; se inclina en 3D al arrastrarlo. Las acciones rápidas cambian con morph de Liquid Glass y efectos de símbolo; las tarjetas aparecen escalonadas, las funciones abren con transición zoom y la barra de pestañas se minimiza al desplazar. Reconexión muestra un anillo y 20 puntos por intento. Reducir movimiento detiene los efectos continuos. Las animaciones solo reflejan estado local; no indican avisos verificados.

## Límites
Mide solo el servicio propio. El informe exportado declara `ancsVerified=false` y `jointGateApproved=false`; no aprueba entrega de avisos, P2 ni gates. Los 20 ciclos y las 24/72 h físicas siguen NOT_RUN.

## Validación (2026-10-18)
- `bash scripts/apple/test-package.sh`: PASS, 23 pruebas.
- `bash scripts/apple/check.sh` con `WRISTBRIDGE_TEST_DESTINATION` (iPhone 17 Pro, iOS 26.2): build PASS, UI tests PASS 6/6 (incluye pantalla Reconexión en demo). Simulador no aprueba G1.
- iPhone físico con 0.3.0: NOT_RUN (no firmado ni instalado).
