# Android P1: recuperación de emparejamiento

18 de octubre de 2026, America/Mexico_City. Trabajo Android en development, base 0abcd51. Los cambios Apple previos se conservan; esta entrega no añade cambios Swift.

## Problema y comportamiento

La escritura de suscripción ANCS puede expirar mientras el usuario acepta el emparejamiento. Antes se mostraba error sin reanudar al completar el vínculo. Ahora una espera monotónica acotada a 60 segundos cubre esa autorización; repetir un fallo no extiende indefinidamente la espera. Si el sistema rechaza crear el vínculo, se cancela o vence, el cliente informa fallo y permite recuperación voluntaria.

Al confirmar el vínculo se abre una generación GATT nueva: un callback tardío del descriptor anterior no puede confirmar una suscripción nueva. Al retirar el vínculo se cierra la sesión y se deja de indicar ANCS listo. Listo sigue exigiendo confirmación remota de Data Source y Notification Source.

El arreglo toma como antecedente el pendiente descrito en el SEED de la sesión Android; su worktree no se modificó. Se añade BondWait en notification-domain con pruebas de vencimiento, repetición, cancelación y reintento.

## Configuración de compilación

El archivo local gradle/gradle-daemon-jvm.properties exigía Java 25 aunque el proyecto documenta Java 21. Se fija toolchainVersion=21; la copia anterior queda en build/local-backup, ignorada. Gradle --version confirma launcher Java 21 y criterio de daemon Java 21. Los criterios tienen precedencia sobre JAVA_HOME: https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria.

La caché global journal-1 permanece retenida por otro proceso. No se borran locks ni se terminan procesos ajenos. Para la validación se usa una copia de modules-2 sin archivos lock ni gc.properties, con Gradle User Home y caché de proyecto bajo build/. Procedimiento de copia documentado por Gradle: https://docs.gradle.org/current/userguide/dependency_caching.html#sec:cache_copy.

## Verificación física pendiente

1. Mantener intacto el vínculo que ya funciona; no resetear ni desvincular por rutina.
2. En un escenario de emparejamiento nuevo expresamente preparado por el operador, tardar más de 10 s y menos de 60 s en aceptar; comprobar que recupera sin pulsar Recuperar conexión y confirma DS y NS.
3. Rechazar y dejar vencer por separado. Comprobar error visible, sin espera infinita ni tormenta de prompts; reintento voluntario posible.
4. Retirar el vínculo solo en un caso negativo autorizado; verificar que deja de mostrar Listo. Restaurar y registrar una nueva generación.
5. Repetir H05 y R de la campaña con ambas builds identificadas. El usuario reporta avisos con iPhone bloqueado y ambas UI cerradas normalmente; duración y recuentos pendientes.

Las pruebas JVM y una build no aprueban estos casos físicos ni G1. No se habilitan P2, IA, AMS, salud ni acciones sensibles en esta entrega.

Diagnóstico adicional: el daemon aislado quedó dentro de PosixFileSystemFunctions.listFileSystems durante el arranque del observador. Se terminó solo ese daemon, creado por esta sesión; su lock siguió retenido. La validación posterior usa otra caché de escritura y el seed copiado en solo lectura. Con --no-watch-fs avanzó a compilación; org.gradle.vfs.watch=false deja el mismo ajuste para Android Studio. La caché global original no se borró ni se liberó artificialmente.

## Actualización instalada · 18 de octubre de 2026

WristBridge Watch 0.1.1-lab (2) compilado, firmado, instalado por actualización -r y abierto en SM-L300: PASS. Checksum instalado coincide con el APK validado. 52 tests JVM PASS (el build final reutiliza sus resultados con inputs idénticos), lint 0 errores/1 aviso. No requiere actualizar la app iPhone; no cambia el perfil P1. H05/R y G1 formales pendientes. Ver [release-0.1.1-lab.md](release-0.1.1-lab.md) y evidence/windows-p1-update-2/windows. AAPT2 se resolvió generando salidas en temporal local, conservando las salidas anteriores bloqueadas de F:.
