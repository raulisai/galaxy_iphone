package dev.wristbridge.watch.bridge

import android.os.SystemClock
import android.util.Log
import dev.wristbridge.notification.SessionCounters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

const val TAG = "WristBridge"

enum class RadioState { UNKNOWN, ON, OFF, UNAVAILABLE }

enum class OwnChannelState { STOPPED, STARTING, ADVERTISING, CONNECTED, FAILED }

enum class BondState { UNKNOWN, NONE, BONDING, BONDED }

/** ANCS states are separate from the own channel: a connected iPhone is not "ANCS ready" (Contract §1). */
enum class AncsState { IDLE, CONNECTING, DISCOVERING, NOT_FOUND, SUBSCRIBING, DATA_SOURCE_CONFIRMED, READY, FAILED }

data class BridgeUiState(
    val serviceRunning: Boolean = false,
    val radio: RadioState = RadioState.UNKNOWN,
    val ownChannel: OwnChannelState = OwnChannelState.STOPPED,
    val ownChannelDetail: String = "",
    val advertisedName: String? = null,
    val peer: String? = null,
    val bond: BondState = BondState.UNKNOWN,
    val ancs: AncsState = AncsState.IDLE,
    val ancsDetail: String = "",
    val generation: Long = 0,
    val mtu: Int = 23,
    val lastReceptionElapsedMs: Long? = null,
    val counters: SessionCounters = SessionCounters(),
    val lastGattStatus: Int? = null,
    val events: List<String> = emptyList(),
)

/**
 * Process-wide observable state for the UI and diagnostics. Contains no notification titles or bodies,
 * no Bluetooth addresses (peers are pseudonymized) and no personal names.
 */
object BridgeStatus {
    private val mutable = MutableStateFlow(BridgeUiState())
    val state: StateFlow<BridgeUiState> = mutable

    fun update(block: (BridgeUiState) -> BridgeUiState) = mutable.update(block)

    /** Records a redacted transition both on screen and in logcat (tag WristBridge). */
    fun event(message: String) {
        Log.i(TAG, message)
        val stamp = SystemClock.elapsedRealtime() / 1000
        mutable.update { it.copy(events = (listOf("[$stamp s] $message") + it.events).take(40)) }
    }
}
