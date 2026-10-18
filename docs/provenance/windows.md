# Procedencia Windows

Implementación original de WristBridge para Wear OS, escrita desde el contrato del proyecto (`Doc/01`), la especificación pública de Apple Notification Center Service y la documentación pública de Android. **No se copió código** de los repositorios R01–R08. Las correcciones conocidas de R01/R02/R03 (plan §3) se tomaron como requisitos y tienen prueba propia: sin avance fijo de 500 ms, cabecera fragmentada, sin longitud en atributos 6/7, sin descarte de 60 s, FIFO con versión por UID, Modified aplicado y Service Changed con nueva generación.

## Dependencias

| Ámbito | Dependencia | Versión | Licencia |
|---|---|---|---|
| Build | Gradle wrapper | 9.8.1 (checksum fijado) | Apache-2.0 |
| Build | Android Gradle Plugin | 9.4.1 | Apache-2.0 |
| Build | Kotlin Gradle Plugin / plugin Compose | 2.4.21 | Apache-2.0 |
| App | androidx.health:health-services-client | 1.1.0 | Apache-2.0 |
| App | androidx.core:core-ktx | 1.19.1 | Apache-2.0 |
| App | androidx.activity:activity-compose | 1.13.0 | Apache-2.0 |
| App | androidx.lifecycle (runtime-compose, service) | 2.11.0 | Apache-2.0 |
| App | androidx.compose BOM | 2026.09.00 | Apache-2.0 |
| App | androidx.wear.compose (material3, foundation) | 1.7.1 | Apache-2.0 |
| App | kotlinx-coroutines-android | 1.11.0 | Apache-2.0 |
| Pruebas JVM | JUnit BOM / Jupiter / platform-launcher | 6.1.3 | EPL-2.0 |
| Pruebas JVM | kotlin-test | 2.4.21 | Apache-2.0 |
| Pruebas Android | androidx.test runner / ext-junit | 1.7.0 / 1.3.0 | Apache-2.0 |

Los módulos `core/*` no dependen de Android. No se usa Nordic BLE Library: el spike P1-04 usa las APIs del sistema hasta comprobar en el reloj qué roles hacen falta (Doc/02, «no importar una biblioteca antes de verificar»).

## Fixtures

`test/fixtures/ancs/vectors.json` se escribió a mano desde la especificación ANCS. Los resultados esperados no se generaron con el código que se prueba.

Este inventario no es el SBOM conjunto de P8-05; Windows lo compondrá junto al inventario Apple sin sobrescribirlo.
