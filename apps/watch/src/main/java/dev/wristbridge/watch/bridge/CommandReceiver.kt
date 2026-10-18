package dev.wristbridge.watch.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Receives card buttons, card dismissals and media controls from notifications. A broadcast reaches the
 * running bridge directly, so delete intents (which carry no foreground-service start exemption) work while
 * the watch is idle. Without a live bridge the command is dropped: its card belongs to a session that ended.
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BridgeService.deliver(intent)) BridgeStatus.event("Puente no activo: acción de notificación ignorada")
    }

    companion object {
        /** Same action and extras as [intent], addressed to this receiver. */
        fun wrap(context: Context, intent: Intent): Intent =
            Intent(intent).setClass(context, CommandReceiver::class.java)
    }
}
