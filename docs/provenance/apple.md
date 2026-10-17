# Procedencia Apple

Implementación inicial original de WristBridge desde el contrato del proyecto y documentación pública Apple. No se copió código de los ocho repositorios candidatos ni se descargaron sus dependencias.

Dependencias de producto: frameworks del SDK Apple (`SwiftUI`, `UIKit`, `AccessorySetupKit`, `CoreBluetooth`, `UserNotifications`, `Foundation`, `Combine`) y paquete local `WristBridgeProtocol`. Pruebas: Swift Testing del toolchain y XCTest para UI. No hay paquetes de terceros ni versiones flotantes que resolver; no se genera un `Package.resolved` vacío como si fijara un SDK.

El selector y la pantalla usan símbolos incluidos en el SDK Apple, sin logos Samsung ni Company IDs ajenos. No hay icono de distribución final; se preparará cuando corresponda el empaquetado, sin bloquear el spike.

El workflow usa `actions/checkout` fijado a commit `11bd71901bbe5b1630ceea73d27597364c9af683`. El runner y la combinación Xcode no se consideran congelados para producto hasta una ejecución registrada. Este inventario Apple no sustituye el SBOM conjunto Windows.

No se añadió licencia de distribución del producto por suposición. Cualquier incorporación futura de un candidato MIT/Apache debe preservar licencia/copyright, revisión, hash y adaptaciones antes de integrarse.
