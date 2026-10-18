package dev.wristbridge.notification

import dev.wristbridge.protocol.ancs.CategoryId
import java.util.Locale

/** Local display metadata only. ANCS supplies identity/name, not application artwork. */
data class SourceProfile(val label: String, val badge: String, val accentArgb: Int, val groupKey: String)

object SourceProfiles {
    private data class Brand(val name: String, val badge: String, val color: Long)
    private val brands = mapOf(
        "ph.telegra.telegraph" to Brand("Telegram", "TG", 0xFF176CA4),
        "net.whatsapp.whatsapp" to Brand("WhatsApp", "WA", 0xFF128C7E),
        "com.netflix.netflix" to Brand("Netflix", "N", 0xFFB20710),
        "com.spotify.client" to Brand("Spotify", "SP", 0xFF147D3F),
        "com.google.gmail" to Brand("Gmail", "GM", 0xFFB3261E),
        "com.apple.mobilesms" to Brand("Mensajes", "MS", 0xFF2E7D32),
        "com.apple.mobilephone" to Brand("Teléfono", "TEL", 0xFF1B5E20),
        "com.apple.facetime" to Brand("FaceTime", "FT", 0xFF007A3D),
        "com.apple.mobilecal" to Brand("Calendario", "CAL", 0xFFC62828),
        "com.apple.mobilemail" to Brand("Mail", "MAIL", 0xFF1565C0),
    )
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")

    fun resolve(appIdentifier: String?, appName: String?, category: CategoryId): SourceProfile {
        val id = appIdentifier?.trim()?.takeIf { identifier.matches(it) }
        val brand = id?.lowercase(Locale.ROOT)?.let(brands::get)
        val label = appName?.trim()?.takeIf(String::isNotEmpty)?.take(80)
            ?: brand?.name
            ?: id?.substringAfterLast('.')?.takeIf(String::isNotEmpty)
            ?: categoryLabel(category)
        return SourceProfile(
            label, brand?.badge ?: "APP", (brand?.color ?: 0xFF455A64).toInt(),
            id ?: "category-${category.raw}",
        )
    }
}
