package dev.wristbridge.watch.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import dev.wristbridge.protocol.contract.AncsUuids
import dev.wristbridge.protocol.contract.WristBridgeProfile
import dev.wristbridge.protocol.p2.secure.P2Profile
import dev.wristbridge.watch.p2.P2Channel
import dev.wristbridge.watch.p2.P2SelfTest
import dev.wristbridge.watch.p2.P2Bridge

/**
 * Path A, own service side (P1-04): advertises the reserved service UUID and hosts a GATT server whose
 * primary service exposes the three own-channel characteristics (draft P2 section 1, NOT FROZEN). The iPhone
 * connects as central; each connection is reported to [onPeerConnected] so the ANCS client can start.
 *
 * All callbacks are re-posted to [handler] (the single BLE thread). Every entry point is reached only after
 * [BlePermissions.bluetoothGranted] passed in [BridgeService]; SecurityException is still caught.
 */
@SuppressLint("MissingPermission")
class PeripheralHost(
    private val context: Context,
    private val handler: Handler,
    private val onPeerConnected: (BluetoothDevice) -> Unit,
    private val onPeerDisconnected: (BluetoothDevice) -> Unit,
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = manager?.adapter
    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var includeNameInScanResponse = true
    private val connected = LinkedHashSet<String>()
    private var events: BluetoothGattCharacteristic? = null
    private val p2 = P2Channel(context, handler, { server }, { events })

    fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            BridgeStatus.update { it.copy(ownChannel = OwnChannelState.FAILED, ownChannelDetail = "Bluetooth apagado") }
            return
        }
        if (server != null) {
            startAdvertising()
            return
        }
        BridgeStatus.update { it.copy(ownChannel = OwnChannelState.STARTING, ownChannelDetail = "") }
        val s = try {
            manager.openGattServer(context, serverCallback)
        } catch (e: SecurityException) {
            fail("Sin permiso Bluetooth")
            return
        }
        if (s == null) {
            fail("No se pudo abrir el servidor GATT")
            return
        }
        server = s
        val service = BluetoothGattService(WristBridgeProfile.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val caps = BluetoothGattCharacteristic(
            WristBridgeProfile.CAPABILITIES, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )
        val requests = BluetoothGattCharacteristic(
            WristBridgeProfile.REQUESTS, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        val ev = BluetoothGattCharacteristic(WristBridgeProfile.EVENTS, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        ev.addDescriptor(
            BluetoothGattDescriptor(
                AncsUuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        events = ev
        service.addCharacteristic(caps)
        service.addCharacteristic(requests)
        service.addCharacteristic(ev)
        p2.attach()
        BridgeStatus.event("P2: autoprueba criptográfica " + P2SelfTest.run().also { r -> P2Bridge.update { it.copy(cryptoSelfTest = r) } })
        if (!s.addService(service)) fail("addService rechazado")
    }

    fun stop() {
        p2.detach()
        stopAdvertising()
        try {
            server?.close()
        } catch (_: SecurityException) {
        }
        server = null
        connected.clear()
        BridgeStatus.update { it.copy(ownChannel = OwnChannelState.STOPPED, ownChannelDetail = "", advertisedName = null) }
    }

    /** Re-applies advertising, for example after the adapter name changed. */
    fun restartAdvertising() {
        stopAdvertising()
        includeNameInScanResponse = true
        startAdvertising()
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            fail("Este reloj no ofrece publicidad BLE")
            return
        }
        if (advertising) return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // 31-byte budget: flags + 128-bit service UUID in the advertisement; local name in the scan response.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(WristBridgeProfile.SERVICE))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(includeNameInScanResponse).build()
        try {
            advertiser.startAdvertising(settings, data, scan, advertiseCallback)
        } catch (e: SecurityException) {
            fail("Sin permiso de publicidad")
        }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        try {
            adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
        }
        advertising = false
    }

    private fun fail(detail: String) {
        BridgeStatus.update { it.copy(ownChannel = OwnChannelState.FAILED, ownChannelDetail = detail) }
        BridgeStatus.event("Canal propio: $detail")
    }

    private fun currentName(): String? = try {
        adapter?.name
    } catch (_: SecurityException) {
        null
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            handler.post {
                advertising = true
                val name = if (includeNameInScanResponse) currentName() else null
                val matches = name?.contains(WristBridgeProfile.ADVERTISED_NAME) == true
                BridgeStatus.update {
                    it.copy(
                        ownChannel = if (connected.isEmpty()) OwnChannelState.ADVERTISING else OwnChannelState.CONNECTED,
                        ownChannelDetail = if (matches) "" else "El nombre no contiene WristBridge: el selector del iPhone puede no verlo",
                        advertisedName = name,
                    )
                }
                BridgeStatus.event("Anunciando servicio ${WristBridgeProfile.SERVICE} nombreValido=$matches")
            }
        }

        override fun onStartFailure(errorCode: Int) {
            handler.post {
                advertising = false
                if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && includeNameInScanResponse) {
                    // Name too long for the scan response: advertise the service only and say so.
                    includeNameInScanResponse = false
                    BridgeStatus.event("Nombre demasiado largo para la respuesta de escaneo; se anuncia sin nombre")
                    startAdvertising()
                } else if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                    advertising = true
                } else {
                    fail("Publicidad fallida código $errorCode")
                }
            }
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    BridgeStatus.event("Servicio propio registrado")
                    startAdvertising()
                } else {
                    fail("Registro de servicio falló código $status")
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                val id = Pseudonym.of(device)
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connected += id
                    BridgeStatus.update { it.copy(ownChannel = OwnChannelState.CONNECTED, peer = id) }
                    BridgeStatus.event("Central conectado peer=$id")
                    onPeerConnected(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected -= id
                    BridgeStatus.update {
                        it.copy(ownChannel = if (advertising) OwnChannelState.ADVERTISING else OwnChannelState.STOPPED)
                    }
                    BridgeStatus.event("Central desconectado peer=$id estado=$status")
                    p2.onDisconnected(device)
                    onPeerDisconnected(device)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post { p2.onMtu(device, mtu) }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post { p2.onNotificationSent(device, status) }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid == WristBridgeProfile.CAPABILITIES && offset == 0) {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, P2Profile.CAPABILITIES)
            } else {
                reject(device, requestId, offset)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (characteristic.uuid != WristBridgeProfile.REQUESTS || preparedWrite || offset != 0 || value == null) {
                if (responseNeeded) reject(device, requestId, offset)
                return
            }
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            val copy = value.copyOf()
            handler.post { p2.onWrite(device, copy) }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            if (descriptor.uuid == AncsUuids.CCCD) {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            } else {
                reject(device, requestId, offset)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (descriptor.uuid != AncsUuids.CCCD || preparedWrite || offset != 0 || value == null || value.size != 2) {
                if (responseNeeded) reject(device, requestId, offset)
                return
            }
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            handler.post { if (enable) p2.onSubscribed(device) else p2.onUnsubscribed(device) }
        }

        private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
            try {
                server?.sendResponse(device, requestId, status, offset, value)
            } catch (_: SecurityException) {
            }
        }

        private fun reject(device: BluetoothDevice, requestId: Int, offset: Int) {
            try {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            } catch (_: SecurityException) {
            }
        }
    }
}
