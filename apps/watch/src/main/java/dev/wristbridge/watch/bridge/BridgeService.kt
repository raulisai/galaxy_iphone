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
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.edit
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
    private var running = false

    private val prefs by lazy { getSharedPreferences(WristBridgeApp.PREFS, MODE_PRIVATE) }

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
        ancs = AncsClient(this, handler, presenter)
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

    override fun onDestroy() {
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
                if (running) host.start()
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
        private const val FOREGROUND_ID = 100
        private const val NAME_APPLY_DELAY_MS = 1_500L

        fun send(context: Context, action: String) {
            val intent = Intent(context, BridgeService::class.java).setAction(action)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
