# Estímulos iPhone para fases posteriores

Preparación local, **NOT_RUN** sobre hardware para esta entrega. Usar revisiones y OS identificados, contenido sintético y un solo reloj autorizado; registrar resultados manuales por condición sin cuerpos reales.

| Grupo original | Preparación iPhone | Dependencia y oráculo |
|---|---|---|
| N09–N14 | Generar lotes con IDs distintos antes, durante y después de alejar/reconectar reloj. Incluir corto, Unicode y largo. Observar centro iOS por separado. | Instrumentación watch para correlacionar recibido/presentado/retirado. Ausencia en centro no prueba pérdida; comparar relojes con incertidumbre explícita. |
| A01–A07 | Generar aviso sintético controlado y observar por separado el efecto de las acciones anunciadas por ANCS. | Flags/etiquetas reales del reloj. No mostrar respuestas que ANCS no anuncie ni declarar efecto por escritura GATT. |
| M01–M10 | Reproductor real: play/pause, cambiar pista y app, bloquear teléfono, desconectar y recuperar, atributos largos. Documentar app y autorización. | AMS Kotlin implementado; comprobar comandos/estado/error físico. iPhone no aloja un servidor multimedia alternativo. |
| UI08 | Probar las tres pantallas con VoiceOver, texto ampliado, idiomas/tamaño de pantalla, denegación y retirada. | UI automatizada cubre solo el simulador ensayado; accesibilidad y errores físicos se registran aparte. |
| I/V | Si el ADR requiere iPhone: configuración/login permitido, revocación y efectos Apple tipados; interrupciones externas durante voz watch. | Proveedor/ruta/capacidades aprobados y código watch. Sin relay obligatorio de audio o Internet iPhone. |
| S01–S10 | Denegación parcial, revocación, duplicados, crash tras efecto antes de ACK y snapshot interrumpido. | G2, contrato seguro, ledger HealthKit/snapshot Contacts y contraparte Kotlin. No ejecutables aún. |
| Q07–Q09 | Ajustes: actualizar legado/v1 a v2, reiniciar sin reactivar Detener. Resultados: reabrir, preservar archivo corrupto/futuro y borrar explícitamente. | Pruebas Swift locales incluidas. Crash de efecto HealthKit/ejecutor externo sigue bloqueado hasta existir esa capacidad. |
| Energía/G8 | Baseline y escenario con orden AB/BA, al menos tres pares de 24 h y uso prolongado indicado por plan. Cargar/Enfoque/red/alcance controlados. | Sin depurador conectado durante batería. Registrar puntos porcentuales, no atribuir todo el consumo al puente. |

No automatizar cambios de permisos privados ni simular consentimiento. No restablecer el reloj como sustituto de investigar un fallo. Los resultados de estas campañas pertenecen a sus gates conjuntos.
