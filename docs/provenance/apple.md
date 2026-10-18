# Procedencia Apple

Implementación inicial original de WristBridge desde el contrato del proyecto y documentación pública Apple. No se copió código de los ocho repositorios candidatos ni se descargaron sus dependencias.

Dependencias de producto: frameworks del SDK Apple (`SwiftUI`, `UIKit`, `AccessorySetupKit`, `CoreBluetooth`, `UserNotifications`, `Foundation`, `Combine`) y paquete local `WristBridgeProtocol`. Pruebas: Swift Testing del toolchain y XCTest para UI. No hay paquetes de terceros ni versiones flotantes que resolver; no se genera un `Package.resolved` vacío como si fijara un SDK.

El selector y la pantalla usan símbolos incluidos en el SDK Apple, sin logos Samsung ni Company IDs ajenos. No hay icono de distribución final; se preparará cuando corresponda el empaquetado, sin bloquear el spike.

El workflow usa `actions/checkout` fijado a commit `11bd71901bbe5b1630ceea73d27597364c9af683`. El runner y la combinación Xcode no se consideran congelados para producto hasta una ejecución registrada. Este inventario Apple no sustituye el SBOM conjunto Windows.

No se añadió licencia de distribución del producto por suposición. Cualquier incorporación futura de un candidato MIT/Apache debe preservar licencia/copyright, revisión, hash y adaptaciones antes de integrarse.

Entrega iPhone 0.2.0 (5): diario propio, persistencia atómica, migración v1→v2, UIKit app delegate para restauración y vistas SwiftUI implementados originalmente. Sin incorporación de código o librerías de terceros. El identificador ASK del accesorio se usa solo como asociación local de observaciones, nunca como identidad criptográfica ni como autorización; la exportación lo omite. Los archivos de resultados/fixtures/diagnóstico están protegidos y excluidos de backup al escribir; exportaciones externas quedan fuera del control de borrado de la app. Inventario de dependencias en `docs/provenance/apple-inventory.json`.
