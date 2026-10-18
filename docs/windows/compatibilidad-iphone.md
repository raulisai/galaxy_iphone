# Compatibilidad reloj ↔ iPhone: qué es posible y qué no

18 de octubre de 2026. Resultado del análisis pedido tras la prueba física de 0.3.0-lab. Los estados son de ingeniería, no de campaña: nada de esto sustituye una prueba en hardware.

## Ya cubierto con servicios del sistema iOS (sin código Swift)

| Función | Mecanismo | Estado |
|---|---|---|
| Avisos de cualquier app | ANCS (Apple Notification Center Service) | Implementado y probado |
| Aceptar/rechazar llamada, «Borrar» u otra acción que ofrece iOS | ANCS `PerformNotificationAction` | Implementado; efecto final lo confirma el iPhone con `Removed` |
| Música: título, artista, tiempo, siguiente/anterior/pausa, volumen | AMS (Apple Media Service) | Implementado; sesión de medios nativa en el reloj |
| Volumen del iPhone con la corona (música y llamada) | AMS `VolumeUp/VolumeDown` | En desarrollo en 0.3.1 |

## Lo que NO se puede con el diseño actual (y por qué)

- **Hablar por el micrófono del reloj y oír la llamada en el reloj.** El audio de una llamada del iPhone viaja por el perfil Bluetooth Hands-Free (HFP). Ese perfil lo negocia el sistema, no una app, y Wear OS solo lo ofrece con teléfonos Android. Una app del reloj no puede registrarse como auricular HFP ante un iPhone ni capturar ese audio. Mientras tanto la llamada se contesta desde el reloj pero el audio sigue en el iPhone (o en sus auriculares). Alternativas reales, ninguna dentro de este alcance: un reloj con Wear OS emparejado como auricular HFP (no lo soporta Wear OS con iOS), o una llamada propia por Internet (VoIP) entre el reloj y un servicio, que es otro producto (P5).
- **Responder mensajes de WhatsApp o de otras apps.** ANCS solo permite las acciones positiva y negativa que la app de origen declara (p. ej. «Borrar»). Las respuestas rápidas de iOS no pasan por ANCS. Responder exigiría una app en el iPhone que intercepte la notificación, y iOS no da a las apps acceso a las notificaciones de otras apps. Ruta posible solo para apps propias o con API oficial (p. ej. WhatsApp Business API), fuera de alcance.
- **Abrir una app o una conversación concreta en el iPhone desde el reloj.** ANCS no tiene «abrir». Con la app WristBridge del iPhone sí se podría, mediante el canal propio P2 y un esquema de URL/App Intent, pero requiere contrato conjunto y código Swift (carpeta del Mac), y iOS pide que la app esté en primer plano o use un App Intent con confirmación del usuario. Queda como propuesta para el contrato.

## Propuesta para el contrato conjunto (P2, requiere Swift)

1. Acción «Abrir en el iPhone» sobre un aviso: el reloj envía `bundleId` + `uid` por el canal propio; la app iPhone lanza una Live Activity/notificación local con enlace universal o `UIApplication.open` si está activa. Límite: iOS puede no abrir la app si no está en primer plano.
2. Respuestas rápidas: lista de frases predefinidas enviada al iPhone, que las envía mediante los Atajos (Shortcuts) con consentimiento del usuario. Solo para apps con acción de Atajos (Mensajes, y WhatsApp si el usuario crea el atajo).
3. Ajustes y estado desde el iPhone (ocultar contenido, silenciar apps).

Nada de esto se implementa sin el contrato y la revisión del lado Apple.
