package dev.wristbridge.notification

import dev.wristbridge.protocol.ancs.AncsUid
import dev.wristbridge.protocol.ancs.CategoryId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic values only: no real names, numbers or notification content. */
class CardPresentationTest {
    private fun card(
        category: CategoryId = CategoryId.SOCIAL,
        app: String? = "ph.telegra.Telegraph",
        name: String? = "Telegram",
        title: String = "Contacto sintético",
        subtitle: String = "",
        message: String = "Cuerpo sintético",
        truncated: Boolean = false,
        unavailable: Boolean = false,
    ) = NotificationCard(
        key = NotificationKey("iphone-A", 3, AncsUid(7)), category = category,
        appIdentifier = app, appName = name, title = title, subtitle = subtitle, message = message,
        truncated = truncated, important = false, preExisting = false, silent = false,
        detailsUnavailable = unavailable, malformedText = false,
        positiveActionLabel = null, negativeActionLabel = null, fixture = null,
    )

    @Test
    fun knownAppsGetLocalBadgeColorAndGroupRegardlessOfIdentifierCase() {
        val tg = SourceProfiles.resolve("ph.telegra.Telegraph", "Telegram", CategoryId.SOCIAL)
        assertEquals("TG", tg.badge)
        assertEquals(0xFF176CA4.toInt(), tg.accentArgb)
        assertEquals("ph.telegra.Telegraph", tg.groupKey)
        for (id in listOf("net.whatsapp.WhatsApp", "com.netflix.Netflix", "com.spotify.client", "com.google.Gmail")) {
            assertFalse(SourceProfiles.resolve(id, null, CategoryId.OTHER).badge == "APP", id)
        }
        assertEquals("Spotify", SourceProfiles.resolve("com.spotify.client", null, CategoryId.ENTERTAINMENT).label)
    }

    @Test
    fun iphoneDisplayNameWinsOverCatalogName() {
        assertEquals("Telegram X", SourceProfiles.resolve("ph.telegra.Telegraph", " Telegram X ", CategoryId.SOCIAL).label)
    }

    @Test
    fun unknownOrInvalidSourcesFallBackWithoutLeakingRawInput() {
        val unknown = SourceProfiles.resolve("com.example.Demo", "", CategoryId.NEWS)
        assertEquals("Demo", unknown.label)
        assertEquals("APP", unknown.badge)
        val invalid = SourceProfiles.resolve("bad id\nwith text", null, CategoryId.EMAIL)
        assertEquals("Correo", invalid.label)
        assertEquals("category-6", invalid.groupKey)
        assertEquals("iPhone", SourceProfiles.resolve(null, null, CategoryId.UNKNOWN).label)
    }

    @Test
    fun regularNotificationShowsTitleAndBody() {
        val p = CardPresentation.from(card(subtitle = "Grupo", truncated = true), hidePreview = false)
        assertEquals(CallKind.NONE, p.callKind)
        assertEquals("Contacto sintético", p.title)
        assertEquals("Grupo\nCuerpo sintético…", p.body)
        assertEquals("Telegram", p.source.label)
    }

    @Test
    fun callsAreLabelledExplicitlyAndKeepCallerInBody() {
        val call = card(CategoryId.INCOMING_CALL, "com.apple.mobilephone", "Teléfono", "Llamante sintético", message = "")
        val p = CardPresentation.from(call, hidePreview = false)
        assertEquals(CallKind.INCOMING, p.callKind)
        assertEquals("Llamada entrante", p.title)
        assertEquals("Llamante sintético", p.body)
        assertEquals("TEL", p.source.badge)
        assertEquals("Llamada perdida", CardPresentation.from(card(CategoryId.MISSED_CALL), false).title)
        assertEquals(CallKind.VOICEMAIL, CardPresentation.from(card(CategoryId.VOICEMAIL), false).callKind)
    }

    @Test
    fun hiddenPreviewNeverExposesCallerOrContent() {
        for (category in listOf(CategoryId.INCOMING_CALL, CategoryId.MISSED_CALL, CategoryId.VOICEMAIL, CategoryId.SOCIAL)) {
            val p = CardPresentation.from(
                card(category, title = "Llamante sintético", subtitle = "Sub sintético", message = "Cuerpo sintético"),
                hidePreview = true, hiddenText = "Oculto",
            )
            val visible = listOf(p.title, p.body, p.source.label).joinToString("|")
            assertFalse("sintético" in visible, "$category: $visible")
            assertEquals("Oculto", p.body)
        }
    }

    @Test
    fun unavailableDetailsUseFallbackText() {
        val p = CardPresentation.from(card(unavailable = true, title = "", message = ""), false, unavailableText = "N/D")
        assertEquals("Telegram", p.title)
        assertEquals("N/D", p.body)
        assertTrue(p.source.badge == "TG")
    }
}
