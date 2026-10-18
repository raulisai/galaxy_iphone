package dev.wristbridge.watch.bridge

import dev.wristbridge.notification.ActionStatus
import dev.wristbridge.notification.NotificationCard
import dev.wristbridge.notification.NotificationKey
import dev.wristbridge.protocol.ams.MediaState
import dev.wristbridge.protocol.ancs.ActionId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** AMS (music) availability; independent of ANCS: an AMS failure never marks ANCS as failed. */
enum class AmsState { IDLE, NOT_FOUND, SUBSCRIBING, READY, FAILED }

data class MediaUiState(
    val ams: AmsState = AmsState.IDLE,
    val detail: String = "",
    val media: MediaState = MediaState(),
)

/** Process-wide now-playing state for UI and the media notification. Written only by the BLE thread. */
object MediaStatus {
    private val mutable = MutableStateFlow(MediaUiState())
    val state: StateFlow<MediaUiState> = mutable

    fun update(block: (MediaUiState) -> MediaUiState) = mutable.update(block)
}

/** A card currently shown on the watch, plus the last action requested on it from the watch. */
data class ActiveCard(
    val card: NotificationCard,
    val lastAction: ActionId? = null,
    val actionStatus: ActionStatus? = null,
)

/**
 * In-memory mirror of the visible cards for the in-app list (newest first). Holds content only while the
 * card is visible, never persists it and never logs it. Written by [CardPresenter].
 */
object ActiveCards {
    private const val MAX = 50
    private val mutable = MutableStateFlow<List<ActiveCard>>(emptyList())
    val state: StateFlow<List<ActiveCard>> = mutable

    fun upsert(card: NotificationCard) = mutable.update { list ->
        val old = list.firstOrNull { it.card.key == card.key }
        val next = old?.copy(card = card) ?: ActiveCard(card)
        (listOf(next) + list.filterNot { it.card.key == card.key }).take(MAX)
    }

    fun remove(key: NotificationKey) = mutable.update { list -> list.filterNot { it.card.key == key } }

    fun removeBefore(generation: Long) = mutable.update { list -> list.filter { it.card.key.sessionGeneration >= generation } }

    fun actionResult(key: NotificationKey, action: ActionId, status: ActionStatus) = mutable.update { list ->
        list.map { if (it.card.key == key) it.copy(lastAction = action, actionStatus = status) else it }
    }
}
