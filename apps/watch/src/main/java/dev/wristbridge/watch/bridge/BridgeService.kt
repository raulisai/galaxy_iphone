package dev.wristbridge.watch.bridge

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
import dev.wristbridge.notification.NotificationKey
import dev.wristbridge.protocol.ams.RemoteCommand
import dev.wristbridge.protocol.ancs.ActionId
import dev.wristbridge.protocol.contract.WristBridgeProfile
import dev.wristbridge.watch.R
import dev.wristbridge.watch.WristBridgeApp
import dev.wristbridge.watch.ui.MainActivity

/**
 * Foreground service of type connectedDevice (P1-06). Owns the BLE thread, the peripheral host and the
 * ANCS client so the bridge keeps running with the UI closed (H05). START_STICKY lets the system restart
 * it after killing the process (H07); a user force-stop is not restarted (H08), as Android intends.
 */
@SuppressLint("MissingPermission")
class BridgeService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var host: PeripheralHost
    private lateinit var ancs: AncsClient

    /** Written on the BLE thread; also read on the main thread to decide whether a user command may stop us. */
    @Volatile
    private var running = false

    private val prefs by lazy { getSharedPreferences(WristBridgeApp.PREFS, MODE_PRIVATE) }
    private lateinit var media: MediaPresenter

    /** Settings toggled in the UI apply to the now-playing notification at once, not at the next AMS update. */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == WristBridgeApp.PREF_MEDIA_NOTIFICATION || key == WristBridgeApp.PREF_HIDE_PREVIEW) {
            handler.post { media.render(MediaStatus.state.value) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    handler.post { ancs.onBondStateChanged(device, state) }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    handler.post { onRadioChanged(state) }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("wristbridge-ble").also { it.start() }
        handler = Handler(thread.looper)
        val presenter = CardPresenter(this)
        media = MediaPresenter(this)
        ancs = AncsClient(this, handler, presenter, media)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        live = this
        host = PeripheralHost(
            this, handler,
            onPeerConnected = { ancs.onPeerConnected(it) },
            onPeerDisconnected = { ancs.onPeerDisconnected(it) },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BlePermissions.bluetoothGranted(this)) {
            BridgeStatus.event("Faltan permisos Bluetooth: el puente no se inicia")
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, FOREGROUND_ID, foregroundNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (intent != null && intent.action in USER_COMMANDS) return onUserCommand(intent)
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.edit { putBoolean(WristBridgeApp.PREF_ENABLED, false) }
                handler.post { shutdown() }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECOVER -> handler.post { if (running) { host.restartAdvertising(); ancs.recover() } }
            ACTION_USE_PROPOSED_NAME -> handler.post { setAdapterName(useProposed = true) }
            ACTION_RESTORE_NAME -> handler.post { setAdapterName(useProposed = false) }
        }
        // Null intent = system restart after process death (START_STICKY).
        prefs.edit { putBoolean(WristBridgeApp.PREF_ENABLED, true) }
        handler.post { if (!running) startup(restarted = intent == null) }
        return START_STICKY
    }

    /**
     * Card buttons, card dismissals and media controls. They act on an enabled bridge (starting it if it is
     * not up yet) but never start one the user stopped: the foreground entered above is undone at once and
     * PREF_ENABLED is left false.
     */
    private fun onUserCommand(intent: Intent): Int {
        if (!prefs.getBoolean(WristBridgeApp.PREF_ENABLED, false)) {
            BridgeStatus.event("Puente detenido: acción ignorada")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        val task = userTask(intent)
        handler.post {
            if (!running) startup(restarted = false)
            if (running) task?.run()
        }
        return START_STICKY
    }

    /** Extras are parsed here; the returned task runs on the BLE thread. Null means nothing to send. */
    private fun userTask(intent: Intent): Runnable? = when (intent.action) {
        ACTION_CARD_ACTION, ACTION_CARD_DISMISSED -> cardTask(intent)
        ACTION_MEDIA -> RemoteCommand.of(intent.getIntExtra(EXTRA_COMMAND, -1))
            ?.let { command -> Runnable { ancs.mediaCommand(command) } }
            ?: run {
                BridgeStatus.event("Comando de música inválido")
                null
            }
        else -> null
    }

    private fun cardTask(intent: Intent): Runnable? {
        val dismissed = intent.action == ACTION_CARD_DISMISSED
        // A dismissal clears on the iPhone only when the user opted in, and only ever as the negative action.
        if (dismissed && !prefs.getBoolean(WristBridgeApp.PREF_SYNC_DISMISS, false)) return null
        val raw = intent.getIntExtra(EXTRA_ACTION, -1)
        val action = if (dismissed) ActionId.NEGATIVE else ActionId.entries.firstOrNull { it.raw == raw }
        if (action == null || !intent.hasExtra(EXTRA_GENERATION) || !intent.hasExtra(EXTRA_UID)) {
            BridgeStatus.event("Acción de tarjeta inválida")
            return null
        }
        val generation = intent.getLongExtra(EXTRA_GENERATION, 0)
        val uid = intent.getLongExtra(EXTRA_UID, -1)
        return Runnable { ancs.performAction(generation, uid, action, dismissed) }
    }

    /** Notification commands relayed by [CommandReceiver]; never starts a bridge the user stopped. */
    private fun dispatch(intent: Intent) {
        if (!prefs.getBoolean(WristBridgeApp.PREF_ENABLED, false)) {
            BridgeStatus.event("Puente detenido: acción ignorada")
            return
        }
        val task = userTask(intent) ?: return
        handler.post { if (running) task.run() }
    }

    override fun onDestroy() {
        live = null
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        handler.post { shutdown() }
        thread.quitSafely()
        BridgeStatus.update { it.copy(serviceRunning = false) }
        super.onDestroy()
    }

    private fun startup(restarted: Boolean) {
        running = true
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        BridgeStatus.update {
            it.copy(serviceRunning = true, radio = if (adapter?.isEnabled == true) RadioState.ON else RadioState.OFF)
        }
        BridgeStatus.event(if (restarted) "Servicio reiniciado por el sistema" else "Servicio iniciado")
        ancs.start()
        host.start()
    }

    private fun shutdown() {
        if (!running) return
        running = false
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        ancs.stop()
        host.stop()
        BridgeStatus.event("Servicio detenido")
    }

    private fun onRadioChanged(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_ON -> {
                BridgeStatus.update { it.copy(radio = RadioState.ON) }
                BridgeStatus.event("Bluetooth encendido")
                if (running) { ancs.start(); host.start() }
            }
            BluetoothAdapter.STATE_OFF -> {
                BridgeStatus.update { it.copy(radio = RadioState.OFF) }
                BridgeStatus.event("Bluetooth apagado")
                ancs.stop()
                ancs.start()
                host.stop()
            }
        }
    }

    /**
     * Laboratory helper for AccessorySetupKit name filtering (pending ADR-001). Renames the watch's
     * Bluetooth adapter to the proposed name and keeps the original to restore it. Explicit operator action.
     */
    private fun setAdapterName(useProposed: Boolean) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        try {
            if (useProposed) {
                val current = adapter.name
                if (current != WristBridgeProfile.ADVERTISED_NAME && !prefs.contains(WristBridgeApp.PREF_ORIGINAL_NAME)) {
                    prefs.edit { putString(WristBridgeApp.PREF_ORIGINAL_NAME, current) }
                }
                adapter.setName(WristBridgeProfile.ADVERTISED_NAME)
                BridgeStatus.event("Nombre Bluetooth cambiado a ${WristBridgeProfile.ADVERTISED_NAME} (reversible)")
            } else {
                val original = prefs.getString(WristBridgeApp.PREF_ORIGINAL_NAME, null) ?: return
                adapter.setName(original)
                prefs.edit { remove(WristBridgeApp.PREF_ORIGINAL_NAME) }
                BridgeStatus.event("Nombre Bluetooth original restaurado")
            }
        } catch (e: SecurityException) {
            BridgeStatus.event("No se pudo cambiar el nombre: permiso denegado")
        }
        // The adapter applies the name asynchronously; re-advertise shortly after.
        handler.postDelayed({ if (running) host.restartAdvertising() }, NAME_APPLY_DELAY_MS)
    }

    private fun foregroundNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, WristBridgeApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.wristbridge.watch.START"
        const val ACTION_STOP = "dev.wristbridge.watch.STOP"
        const val ACTION_RECOVER = "dev.wristbridge.watch.RECOVER"
        const val ACTION_USE_PROPOSED_NAME = "dev.wristbridge.watch.USE_PROPOSED_NAME"
        const val ACTION_RESTORE_NAME = "dev.wristbridge.watch.RESTORE_NAME"

        /** Perform an ANCS action on a card. Extras: [EXTRA_GENERATION], [EXTRA_UID], [EXTRA_ACTION]. */
        const val ACTION_CARD_ACTION = "dev.wristbridge.watch.CARD_ACTION"

        /** A card was dismissed on the watch (delete intent). Same extras; honoured only with PREF_SYNC_DISMISS. */
        const val ACTION_CARD_DISMISSED = "dev.wristbridge.watch.CARD_DISMISSED"

        /** Send an AMS remote command. Extra: [EXTRA_COMMAND] = RemoteCommand.raw. */
        const val ACTION_MEDIA = "dev.wristbridge.watch.MEDIA"

        const val EXTRA_GENERATION = "generation"
        const val EXTRA_UID = "uid"
        const val EXTRA_ACTION = "action"
        const val EXTRA_COMMAND = "command"
        private const val FOREGROUND_ID = 100
        private const val NAME_APPLY_DELAY_MS = 1_500L

        /** Triggered from cards, notifications or the UI: they never start a bridge the user stopped. */
        private val USER_COMMANDS = setOf(ACTION_CARD_ACTION, ACTION_CARD_DISMISSED, ACTION_MEDIA)

        /** The service instance of this process while it exists; used only by [deliver]. */
        @Volatile
        private var live: BridgeService? = null

        /** Hands a notification command to the live service. False when no bridge service exists. */
        fun deliver(intent: Intent): Boolean {
            val service = live ?: return false
            if (intent.action !in USER_COMMANDS) return false
            service.dispatch(intent)
            return true
        }

        fun send(context: Context, action: String) {
            val intent = Intent(context, BridgeService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cardIntent(context: Context, intentAction: String, key: NotificationKey, action: ActionId): Intent =
            Intent(context, BridgeService::class.java).setAction(intentAction)
                .putExtra(EXTRA_GENERATION, key.sessionGeneration)
                .putExtra(EXTRA_UID, key.uid.value)
                .putExtra(EXTRA_ACTION, action.raw)

        fun mediaIntent(context: Context, command: RemoteCommand): Intent =
            Intent(context, BridgeService::class.java).setAction(ACTION_MEDIA).putExtra(EXTRA_COMMAND, command.raw)

        /** From the UI: request [action] on the card [key] (same path as the notification button). */
        fun sendCardAction(context: Context, key: NotificationKey, action: ActionId) =
            ContextCompat.startForegroundService(context, cardIntent(context, ACTION_CARD_ACTION, key, action))

        /** From the UI: send an AMS remote command. */
        fun sendMedia(context: Context, command: RemoteCommand) =
            ContextCompat.startForegroundService(context, mediaIntent(context, command))

        /** Distinct, stable request codes so PendingIntents of different cards/actions never collide. */
        fun requestCode(key: NotificationKey, kind: Int): Int = (key.tag.hashCode() * 31) + kind
    }
}
