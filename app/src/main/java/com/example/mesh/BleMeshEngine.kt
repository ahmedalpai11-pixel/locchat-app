package com.example.mesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
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
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.example.data.ContactsManager
import com.example.data.CryptoManager
import com.example.model.MeshEngineStatus
import com.example.model.MeshIdentity
import com.example.model.MeshPowerMode
import com.example.model.NearbyPeer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class BleMeshEngine(
    private val context: Context,
    private val cryptoManager: CryptoManager,
    private val contactsManager: ContactsManager,
    private val onPeerKeyLearned: (peerId: String, x25519KeyHex: String) -> Unit = { _, _ -> },
    private val onPacketReceived: suspend (MeshPacket) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var codedPhyAdvCallback: AdvertisingSetCallback? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val addressToNodeId = ConcurrentHashMap<String, String>()
    private val nodeIdToAddress = ConcurrentHashMap<String, String>()
    private val peerMap = ConcurrentHashMap<String, NearbyPeer>()
    private val incomingBuffers = ConcurrentHashMap<String, ByteArrayOutputStream>()
    private val subscribedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val lastSyncPerNode = ConcurrentHashMap<String, Long>()
    private val deviceSendMutexes = ConcurrentHashMap<String, Mutex>()
    private val deviceMtus = ConcurrentHashMap<String, Int>()
    private val serverNotifyAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    private val seenPackets: MutableSet<String> = Collections.synchronizedSet(
        object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > 800
            }
        }.keys
    )

    private val outboxCache = Collections.synchronizedList(mutableListOf<MeshPacket>())

    private val _nearbyPeers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val nearbyPeers: StateFlow<List<NearbyPeer>> = _nearbyPeers.asStateFlow()

    private val _status = MutableStateFlow(MeshEngineStatus())
    val status: StateFlow<MeshEngineStatus> = _status.asStateFlow()

    private var currentIdentity: MeshIdentity = cryptoManager.getOrCreateIdentity()
    private var currentPowerMode: MeshPowerMode = MeshPowerMode.BALANCED
    @Volatile
    private var isStealthMode: Boolean = cryptoManager.isStealthModeEnabled()
    @Volatile
    private var maxHopTtl: Int = cryptoManager.getMaxHopTtl()
    private var maintenanceJob: Job? = null
    private var isStarted = false

    fun updateIdentity(identity: MeshIdentity) {
        currentIdentity = identity
        if (isStarted) {
            restartAdvertising()
            if (!isStealthMode) sendHelloBroadcast()
        }
    }

    fun setStealthMode(enabled: Boolean) {
        isStealthMode = enabled
        if (isStarted) {
            restartAdvertising()
            if (!enabled) {
                sendHelloBroadcast()
                sendNodeAnnounce()
            }
        }
    }

    fun setMaxHopTtl(hops: Int) {
        maxHopTtl = hops.coerceIn(1, 7)
    }

    fun refreshPeersFromContacts() {
        emitPeers()
    }

    fun setPowerMode(mode: MeshPowerMode) {
        currentPowerMode = mode
        _status.value = _status.value.copy(powerMode = mode)
        if (isStarted) {
            restartScanning()
            restartAdvertising()
        }
    }

    fun clearCaches() {
        seenPackets.clear()
        outboxCache.clear()
        peerMap.clear()
        addressToNodeId.clear()
        nodeIdToAddress.clear()
        lastSyncPerNode.clear()
        incomingBuffers.clear()
        emitPeers()
        _status.value = _status.value.copy(cachedPacketsCount = 0, packetsRelayed = 0)
    }

    @SuppressLint("MissingPermission")
    fun start(identity: MeshIdentity) {
        currentIdentity = identity
        if (isStarted) return

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            _status.value = _status.value.copy(
                isHardwareAvailable = adapter != null,
                isAdvertising = false,
                isScanning = false,
                statusMessage = if (adapter == null) {
                    "لا يوجد محول بلوتوث في هذا الجهاز — وضع المعاينة المحلية"
                } else {
                    "البلوتوث متوقف — بانتظار التشغيل"
                }
            )
            return
        }

        isStarted = true
        setupGattServer()
        startAdvertising()
        startScanning()

        maintenanceJob?.cancel()
        maintenanceJob = scope.launch {
            delay(1_500L)
            if (isStarted && !isStealthMode) sendNodeAnnounce()
            var tick = 0
            while (isActive && isStarted) {
                delay(12_000)
                tick++
                val now = System.currentTimeMillis()
                var changed = false
                val iter = peerMap.entries.iterator()
                while (iter.hasNext()) {
                    val entry = iter.next()
                    if (now - entry.value.lastSeenMillis > 75_000L) {
                        iter.remove()
                        changed = true
                    }
                }
                synchronized(outboxCache) {
                    outboxCache.removeAll { now - it.timestamp > MAX_PACKET_AGE_MS }
                }
                _status.value = _status.value.copy(cachedPacketsCount = outboxCache.size)
                if (changed) emitPeers()
                if (tick % 4 == 0 && !isStealthMode) {
                    sendNodeAnnounce()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        isStarted = false
        maintenanceJob?.cancel()
        try {
            if (hasScanPermission()) scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        try {
            if (hasAdvertisePermission()) {
                advertiser?.stopAdvertising(advertiseCallback)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    codedPhyAdvCallback?.let { advertiser?.stopAdvertisingSet(it) }
                }
            }
        } catch (_: Exception) {
        }
        codedPhyAdvCallback = null
        try {
            if (hasConnectPermission()) gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
        txCharacteristic = null
        subscribedCentrals.clear()
        _status.value = _status.value.copy(
            isAdvertising = false,
            isScanning = false,
            activeGattConnections = 0,
            statusMessage = "تم إيقاف محرك الشبكة"
        )
    }

    fun requestKeyHandshake() {
        if (!isStealthMode) {
            sendHelloBroadcast()
            sendNodeAnnounce()
        }
    }

    fun broadcastPacket(packet: MeshPacket, cacheForStoreAndForward: Boolean = true) {
        seenPackets.add(packet.packetId)
        if (cacheForStoreAndForward && packet.type != MeshPacketType.PEER_HELLO) {
            cachePacket(packet)
        }
        val frameBytes = MeshPacketCodec.encodeFrame(packet)

        scope.launch {
            notifySubscribedCentrals(frameBytes)
            val targets = discoveredDevices.values.toList()
            for (device in targets) {
                launch {
                    sendFrameToPeripheralWithRetry(device, frameBytes)
                }
            }
        }
    }

    fun getPeerX25519Key(peerId: String): String? {
        return peerMap[peerId]?.x25519PublicKeyHex?.takeIf { it.length == 64 }
            ?: contactsManager.getContact(peerId)?.x25519PublicKeyHex?.takeIf { it.length == 64 }
    }

    /**
     * Enforces replay window, Ed25519 signature, senderId-to-publicKey binding,
     * contact key pinning, and blocklist filtering.
     */
    internal fun validateIncomingPacket(packet: MeshPacket, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (packet.senderId == currentIdentity.nodeId) return false
        if (contactsManager.isBlocked(packet.senderId)) return false

        // Replay & clock-skew protection
        if (nowMs - packet.timestamp > MAX_PACKET_AGE_MS) return false
        if (packet.timestamp - nowMs > MAX_FUTURE_SKEW_MS) return false

        // Mandatory cryptographic identity & Ed25519 signature verification
        val sigOk = cryptoManager.verifyPacketIdentityAndSignature(
            claimedSenderId = packet.senderId,
            publicKeyHex = packet.senderPubKeyHex,
            data = MeshPacketCodec.signingBytes(packet),
            signatureB64 = packet.signature
        )
        if (!sigOk) return false

        // Key pinning check for verified contacts
        val pinned = contactsManager.getContact(packet.senderId)
        if (pinned != null && pinned.publicKeyHex.isNotBlank()) {
            if (!pinned.publicKeyHex.equals(packet.senderPubKeyHex, ignoreCase = true)) {
                return false
            }
        }
        return true
    }

    private fun cachePacket(packet: MeshPacket) {
        synchronized(outboxCache) {
            if (outboxCache.none { it.packetId == packet.packetId }) {
                outboxCache.add(packet)
                if (outboxCache.size > 150) {
                    outboxCache.removeAt(0)
                }
            }
        }
        _status.value = _status.value.copy(cachedPacketsCount = outboxCache.size)
    }

    private fun buildHelloPacket(): MeshPacket {
        val recentIds = synchronized(seenPackets) {
            seenPackets.toList().takeLast(64)
        }
        val payload = JSONObject().apply {
            put("seen", JSONArray(recentIds))
        }.toString()

        val base = MeshPacket(
            type = MeshPacketType.PEER_HELLO,
            senderId = currentIdentity.nodeId,
            senderName = currentIdentity.nickname,
            senderPubKeyHex = currentIdentity.publicKeyHex,
            senderX25519KeyHex = currentIdentity.x25519PublicKeyHex,
            targetId = "*",
            isChannel = true,
            ttl = 1,
            hopCount = 0,
            payload = payload
        )
        val sig = cryptoManager.signPayload(MeshPacketCodec.signingBytes(base))
        return base.copy(signature = sig)
    }

    private fun sendHelloBroadcast() {
        if (isStealthMode) return
        broadcastPacket(buildHelloPacket(), cacheForStoreAndForward = false)
    }

    private fun sendNodeAnnounce() {
        if (isStealthMode) return
        val base = MeshPacket(
            type = MeshPacketType.NODE_ANNOUNCE,
            senderId = currentIdentity.nodeId,
            senderName = currentIdentity.nickname,
            senderPubKeyHex = currentIdentity.publicKeyHex,
            senderX25519KeyHex = currentIdentity.x25519PublicKeyHex,
            targetId = "*",
            isChannel = true,
            ttl = maxHopTtl,
            hopCount = 0,
            payload = "{}"
        )
        val sig = cryptoManager.signPayload(MeshPacketCodec.signingBytes(base))
        broadcastPacket(base.copy(signature = sig), cacheForStoreAndForward = true)
    }

    @SuppressLint("MissingPermission")
    private fun setupGattServer() {
        if (!hasConnectPermission()) return
        try {
            val server = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return
            val service = BluetoothGattService(
                MESH_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            val rxChar = BluetoothGattCharacteristic(
                MESH_RX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val txChar = BluetoothGattCharacteristic(
                MESH_TX_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )
            txChar.addDescriptor(
                BluetoothGattDescriptor(
                    CCCD_UUID,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                )
            )
            service.addCharacteristic(rxChar)
            service.addCharacteristic(txChar)
            server.addService(service)
            gattServer = server
            txCharacteristic = txChar
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        if (!hasAdvertisePermission()) return
        val adapter = bluetoothAdapter ?: return
        val adv = adapter.bluetoothLeAdvertiser ?: run {
            _status.value = _status.value.copy(
                isAdvertising = false,
                statusMessage = "وضع الاستقبال المركزي (البث غير مدعوم بالجهاز)"
            )
            return
        }
        advertiser = adv

        val advMode = when (currentPowerMode) {
            MeshPowerMode.HIGH_PERFORMANCE -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            MeshPowerMode.BALANCED -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
            MeshPowerMode.BATTERY_SAVER -> AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
        }

        // Max TX Power for maximum physical distance
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advMode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val nodeMeta = encodeBleNodeMeta(
            nodeId = currentIdentity.nodeId,
            nickname = currentIdentity.nickname,
            stealth = isStealthMode
        )

        // Legacy BLE adv packet has a strict 31-byte cap:
        // Flags (3B) + 128-bit Service UUID (18B) = 21B.
        // Put node metadata into ScanResponse using compact Manufacturer Data (4B overhead + <=20B payload <= 24B).
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        val scanResponseData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(LOCCHAT_MANUFACTURER_ID, nodeMeta)
            .build()

        try {
            adv.startAdvertising(settings, advData, scanResponseData, advertiseCallback)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        // BLE 5.0 Coded PHY (Long Range) secondary advertising set supports up to 254 bytes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            adapter.isLeCodedPhySupported &&
            adapter.isLeExtendedAdvertisingSupported
        ) {
            try {
                val interval = when (currentPowerMode) {
                    MeshPowerMode.HIGH_PERFORMANCE -> AdvertisingSetParameters.INTERVAL_LOW
                    MeshPowerMode.BALANCED -> AdvertisingSetParameters.INTERVAL_MEDIUM
                    MeshPowerMode.BATTERY_SAVER -> AdvertisingSetParameters.INTERVAL_HIGH
                }
                val extParams = AdvertisingSetParameters.Builder()
                    .setLegacyMode(false)
                    .setConnectable(true)
                    .setScannable(false)
                    .setInterval(interval)
                    .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                    .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                    .build()
                val extData = AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
                    .addManufacturerData(LOCCHAT_MANUFACTURER_ID, nodeMeta)
                    .build()
                val cb = object : AdvertisingSetCallback() {
                    override fun onAdvertisingSetStarted(
                        advertisingSet: AdvertisingSet?,
                        txPower: Int,
                        status: Int
                    ) {
                    }
                }
                codedPhyAdvCallback = cb
                adv.startAdvertisingSet(extParams, extData, null, null, null, cb)
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartAdvertising() {
        try {
            if (hasAdvertisePermission()) {
                advertiser?.stopAdvertising(advertiseCallback)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    codedPhyAdvCallback?.let { advertiser?.stopAdvertisingSet(it) }
                }
            }
        } catch (_: Exception) {
        }
        codedPhyAdvCallback = null
        startAdvertising()
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (!hasScanPermission()) return
        val adapter = bluetoothAdapter ?: return
        val bleScanner = adapter.bluetoothLeScanner ?: return
        scanner = bleScanner

        val scanMode = when (currentPowerMode) {
            MeshPowerMode.HIGH_PERFORMANCE -> ScanSettings.SCAN_MODE_LOW_LATENCY
            MeshPowerMode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
            MeshPowerMode.BATTERY_SAVER -> ScanSettings.SCAN_MODE_LOW_POWER
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(scanMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter.isLeExtendedAdvertisingSupported) {
            settingsBuilder
                .setLegacy(false)
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        }

        try {
            bleScanner.startScan(listOf(filter), settingsBuilder.build(), scanCallback)
            _status.value = _status.value.copy(
                isHardwareAvailable = true,
                isScanning = true,
                statusMessage = "شبكة BLE Mesh نشطة (${currentPowerMode.labelAr})"
            )
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartScanning() {
        try {
            if (hasScanPermission()) scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        startScanning()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _status.value = _status.value.copy(
                isAdvertising = true,
                statusMessage = "شبكة BLE Mesh نشطة (${currentPowerMode.labelAr})"
            )
        }

        override fun onStartFailure(errorCode: Int) {
            _status.value = _status.value.copy(
                isAdvertising = false,
                statusMessage = "استقبال BLE Mesh نشط (رمز البث $errorCode)"
            )
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _status.value = _status.value.copy(
                isScanning = false,
                statusMessage = "إعادة محاولة المسح عبر البلوتوث (رمز $errorCode)..."
            )
            if (isStarted) {
                scope.launch {
                    delay(3_000L)
                    if (isStarted) startScanning()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        discoveredDevices[address] = device

        val rawMeta = result.scanRecord?.getManufacturerSpecificData(LOCCHAT_MANUFACTURER_ID)
            ?: result.scanRecord?.getServiceData(ParcelUuid(MESH_SERVICE_UUID))
        val parsedMeta = rawMeta?.let { String(it, Charsets.UTF_8) } ?: ""
        val parts = parsedMeta.split("|")
        val advertisedNodeId = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
        // Respect peer stealth mode: keep relay device reference without exposing fake IDs in Radar
        if (advertisedNodeId == STEALTH_MARKER) return

        val peerId = advertisedNodeId
            ?: addressToNodeId[address]
            ?: "BLE-${address.takeLast(5).replace(":", "")}"
        val peerName = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: peerMap[peerId]?.nickname
            ?: (if (hasConnectPermission()) device.name else null)
            ?: "عقدة $peerId"

        if (peerId == currentIdentity.nodeId) return

        val oldAddr = nodeIdToAddress.put(peerId, address)
        if (oldAddr != null && oldAddr != address) {
            discoveredDevices.remove(oldAddr)
        }
        addressToNodeId[address] = peerId

        val existing = peerMap[peerId]
        val peer = NearbyPeer(
            peerId = peerId,
            nickname = peerName,
            rssi = result.rssi,
            hopCount = existing?.hopCount ?: 1,
            isVerified = contactsManager.isVerified(peerId),
            isBlocked = contactsManager.isBlocked(peerId),
            publicKeyHex = existing?.publicKeyHex ?: "",
            x25519PublicKeyHex = existing?.x25519PublicKeyHex ?: "",
            fingerprint = existing?.fingerprint ?: peerId,
            lastSeenMillis = System.currentTimeMillis(),
            isConnected = subscribedCentrals.containsKey(address)
        )
        peerMap[peerId] = peer
        emitPeers()

        val lastSync = lastSyncPerNode[peerId] ?: 0L
        if (!isStealthMode && System.currentTimeMillis() - lastSync > 30_000L) {
            lastSyncPerNode[peerId] = System.currentTimeMillis()
            scope.launch {
                sendFrameToPeripheralWithRetry(device, MeshPacketCodec.encodeFrame(buildHelloPacket()))
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                discoveredDevices[device.address] = device
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedCentrals.remove(device.address)
                incomingBuffers.remove(device.address)
                deviceMtus.remove(device.address)
                serverNotifyAcks.remove(device.address)?.complete(false)
            }
            _status.value = _status.value.copy(activeGattConnections = subscribedCentrals.size)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            deviceMtus[device.address] = (mtu - 3).coerceIn(20, 480)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            serverNotifyAcks.remove(device.address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enable = value != null && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enable) {
                    subscribedCentrals[device.address] = device
                    if (!isStealthMode) {
                        scope.launch {
                            delay(150)
                            notifyDevice(device, MeshPacketCodec.encodeFrame(buildHelloPacket()))
                        }
                    }
                } else {
                    subscribedCentrals.remove(device.address)
                }
                _status.value = _status.value.copy(activeGattConnections = subscribedCentrals.size)
            }
            if (responseNeeded && hasConnectPermission()) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (_: SecurityException) {
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded && hasConnectPermission()) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                } catch (_: SecurityException) {
                }
            }
            if (characteristic.uuid == MESH_RX_CHAR_UUID && value != null) {
                handleIncomingBytes(device.address, device, value)
            }
        }
    }

    private fun handleIncomingBytes(address: String, sourceDevice: BluetoothDevice?, chunk: ByteArray) {
        val buf = incomingBuffers.getOrPut(address) { ByteArrayOutputStream() }
        synchronized(buf) {
            if (buf.size() + chunk.size > MAX_FRAME_BUFFER_BYTES) {
                buf.reset()
                return
            }
            buf.write(chunk)
            val rawStr = buf.toString(Charsets.UTF_8.name())
            if (!rawStr.contains("\n")) return

            val lines = rawStr.split("\n")
            buf.reset()
            val remainder = lines.last()
            if (remainder.isNotEmpty()) {
                buf.write(remainder.toByteArray(Charsets.UTF_8))
            }

            for (i in 0 until lines.size - 1) {
                val frame = lines[i].trim()
                if (frame.isEmpty()) continue
                val packet = MeshPacketCodec.decodeFrame(frame) ?: continue
                processDecodedPacket(address, sourceDevice, packet)
            }
        }
    }

    private fun processDecodedPacket(
        sourceAddress: String,
        sourceDevice: BluetoothDevice?,
        packet: MeshPacket
    ) {
        if (!validateIncomingPacket(packet)) return

        addressToNodeId[sourceAddress] = packet.senderId
        nodeIdToAddress[packet.senderId] = sourceAddress
        val prevPeer = peerMap[packet.senderId]
        val resolvedX25519 = packet.senderX25519KeyHex.ifBlank { prevPeer?.x25519PublicKeyHex ?: "" }

        peerMap[packet.senderId] = NearbyPeer(
            peerId = packet.senderId,
            nickname = packet.senderName,
            rssi = prevPeer?.rssi ?: -65,
            hopCount = packet.hopCount + 1,
            isVerified = contactsManager.isVerified(packet.senderId),
            isBlocked = contactsManager.isBlocked(packet.senderId),
            publicKeyHex = packet.senderPubKeyHex,
            x25519PublicKeyHex = resolvedX25519,
            fingerprint = cryptoManager.formatFingerprintFromHex(packet.senderPubKeyHex, resolvedX25519),
            lastSeenMillis = System.currentTimeMillis(),
            isConnected = true
        )
        emitPeers()

        if (resolvedX25519.length == 64) {
            onPeerKeyLearned(packet.senderId, resolvedX25519)
        }

        if (packet.type == MeshPacketType.PEER_HELLO) {
            handlePeerHelloSync(sourceDevice, packet)
            return
        }

        if (!seenPackets.add(packet.packetId)) return

        scope.launch {
            if (packet.type != MeshPacketType.NODE_ANNOUNCE) {
                onPacketReceived(packet)
            }
            if (packet.ttl > 1) {
                delay(Random.nextLong(45L, 210L))
                val relayed = packet.copy(
                    ttl = packet.ttl - 1,
                    hopCount = packet.hopCount + 1
                )
                _status.value = _status.value.copy(
                    packetsRelayed = _status.value.packetsRelayed + 1
                )
                broadcastPacket(relayed, cacheForStoreAndForward = true)
            }
        }
    }

    private fun handlePeerHelloSync(sourceDevice: BluetoothDevice?, hello: MeshPacket) {
        val peerSeen = mutableSetOf<String>()
        try {
            val obj = JSONObject(hello.payload)
            val arr = obj.optJSONArray("seen")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    peerSeen.add(arr.optString(i))
                }
            }
        } catch (_: Exception) {
        }

        val missing = synchronized(outboxCache) {
            outboxCache.filter { !peerSeen.contains(it.packetId) && it.ttl > 0 }
        }
        if (missing.isEmpty() || sourceDevice == null) return

        scope.launch {
            for (pkt in missing) {
                val frame = MeshPacketCodec.encodeFrame(pkt)
                if (subscribedCentrals.containsKey(sourceDevice.address)) {
                    notifyDevice(sourceDevice, frame)
                } else {
                    sendFrameToPeripheralWithRetry(sourceDevice, frame)
                }
                delay(40)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun notifySubscribedCentrals(payload: ByteArray) {
        if (!hasConnectPermission()) return
        val targets = subscribedCentrals.values.toList()
        for (device in targets) {
            notifyDevice(device, payload)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun notifyDevice(device: BluetoothDevice, payload: ByteArray) {
        val server = gattServer ?: return
        val char = txCharacteristic ?: return
        if (!hasConnectPermission()) return
        val mutex = deviceSendMutexes.getOrPut(device.address) { Mutex() }
        val chunkSize = (deviceMtus[device.address] ?: 20).coerceIn(20, 480)
        mutex.withLock {
            val chunks = payload.toList().chunked(chunkSize).map { it.toByteArray() }
            for (chunk in chunks) {
                val ack = CompletableDeferred<Boolean>()
                serverNotifyAcks[device.address] = ack
                val initiated = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        server.notifyCharacteristicChanged(device, char, false, chunk) ==
                            android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = chunk
                        @Suppress("DEPRECATION")
                        server.notifyCharacteristicChanged(device, char, false)
                    }
                } catch (_: Exception) {
                    false
                }
                if (!initiated) {
                    serverNotifyAcks.remove(device.address)
                    break
                }
                val ok = withTimeoutOrNull(1_500L) { ack.await() } ?: false
                if (!ok) break
            }
        }
    }

    private suspend fun sendFrameToPeripheralWithRetry(device: BluetoothDevice, frameBytes: ByteArray) {
        val mutex = deviceSendMutexes.getOrPut(device.address) { Mutex() }
        mutex.withLock {
            repeat(2) { attempt ->
                val ok = sendFrameOnce(device, frameBytes)
                if (ok) return@withLock
                if (attempt == 0) delay(250)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendFrameOnce(device: BluetoothDevice, frameBytes: ByteArray): Boolean {
        if (!hasConnectPermission()) return false
        val completion = CompletableDeferred<Boolean>()
        var writeAck: CompletableDeferred<Boolean>? = null
        var descriptorAck: CompletableDeferred<Boolean>? = null
        var negotiatedPayloadSize = 20

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                    gatt.close()
                    if (!completion.isCompleted) completion.complete(false)
                    return
                }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        bluetoothAdapter?.isLeCodedPhySupported == true
                    ) {
                        try {
                            gatt.setPreferredPhy(
                                BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK,
                                BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK,
                                BluetoothDevice.PHY_OPTION_S8
                            )
                        } catch (_: Exception) {
                        }
                    }
                    gatt.requestMtu(512)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                    if (!completion.isCompleted) completion.complete(false)
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) {
                    negotiatedPayloadSize = (mtu - 3).coerceIn(20, 480)
                }
                gatt.discoverServices()
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    gatt.disconnect()
                    if (!completion.isCompleted) completion.complete(false)
                    return
                }
                val service = gatt.getService(MESH_SERVICE_UUID)
                val rxChar = service?.getCharacteristic(MESH_RX_CHAR_UUID)
                val txChar = service?.getCharacteristic(MESH_TX_CHAR_UUID)
                if (rxChar == null) {
                    gatt.disconnect()
                    if (!completion.isCompleted) completion.complete(false)
                    return
                }

                scope.launch {
                    // Subscribe to TX notifications and wait for onDescriptorWrite before writing RX
                    if (txChar != null) {
                        gatt.setCharacteristicNotification(txChar, true)
                        txChar.getDescriptor(CCCD_UUID)?.let { desc ->
                            val dAck = CompletableDeferred<Boolean>()
                            descriptorAck = dAck
                            val startedDesc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                                    android.bluetooth.BluetoothStatusCodes.SUCCESS
                            } else {
                                @Suppress("DEPRECATION")
                                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(desc)
                            }
                            if (startedDesc) {
                                withTimeoutOrNull(1_500L) { dAck.await() }
                            }
                        }
                    }

                    var allSent = true
                    val chunks = frameBytes.toList().chunked(negotiatedPayloadSize).map { it.toByteArray() }
                    for (chunk in chunks) {
                        val ack = CompletableDeferred<Boolean>()
                        writeAck = ack
                        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(
                                rxChar,
                                chunk,
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            rxChar.value = chunk
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(rxChar)
                        }
                        if (!started) {
                            allSent = false
                            break
                        }
                        val ackOk = withTimeoutOrNull(2_500L) { ack.await() } ?: false
                        if (!ackOk) {
                            allSent = false
                            break
                        }
                    }
                    delay(80)
                    gatt.disconnect()
                    if (!completion.isCompleted) completion.complete(allSent)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                descriptorAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == MESH_TX_CHAR_UUID) {
                    handleIncomingBytes(device.address, device, value)
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (characteristic.uuid == MESH_TX_CHAR_UUID) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: return
                    handleIncomingBytes(device.address, device, bytes)
                }
            }
        }

        val gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, gattCallback)
            }
        } catch (_: SecurityException) {
            return false
        } catch (_: Exception) {
            return false
        }

        val dynamicTimeoutMs = (8_000L + frameBytes.size * 3L).coerceAtMost(45_000L)
        val result = withTimeoutOrNull(dynamicTimeoutMs) { completion.await() } ?: false
        if (!result) {
            try {
                gatt?.disconnect()
                gatt?.close()
            } catch (_: Exception) {
            }
        }
        return result
    }

    private fun emitPeers() {
        _nearbyPeers.value = peerMap.values
            .map {
                it.copy(
                    isVerified = contactsManager.isVerified(it.peerId),
                    isBlocked = contactsManager.isBlocked(it.peerId)
                )
            }
            .sortedByDescending { it.rssi }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("A7B80001-4E5F-42A1-9C3D-7E8F9A0B1C2D")
        val MESH_RX_CHAR_UUID: UUID = UUID.fromString("A7B80002-4E5F-42A1-9C3D-7E8F9A0B1C2D")
        val MESH_TX_CHAR_UUID: UUID = UUID.fromString("A7B80003-4E5F-42A1-9C3D-7E8F9A0B1C2D")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        internal const val STEALTH_MARKER = "STEALTH"
        internal const val LOCCHAT_MANUFACTURER_ID = 0x4C43
        private const val MAX_PACKET_AGE_MS = 12 * 3_600_000L
        private const val MAX_FUTURE_SKEW_MS = 5 * 60_000L
        private const val MAX_FRAME_BUFFER_BYTES = 256 * 1024

        /**
         * Encodes nodeId|nickname cleanly within BLE legacy service data limit (<= 20 UTF-8 bytes)
         * without slicing multi-byte Arabic UTF-8 codepoints in half.
         */
        internal fun encodeBleNodeMeta(nodeId: String, nickname: String, stealth: Boolean): ByteArray {
            if (stealth) return STEALTH_MARKER.toByteArray(Charsets.UTF_8)
            val prefix = "$nodeId|"
            val cleanName = nickname.replace("|", " ").trim()
            val out = StringBuilder(prefix)
            for (ch in cleanName) {
                val candidate = (out.toString() + ch).toByteArray(Charsets.UTF_8)
                if (candidate.size > 20) break
                out.append(ch)
            }
            return out.toString().toByteArray(Charsets.UTF_8).let {
                if (it.size <= 20) it else nodeId.toByteArray(Charsets.UTF_8).copyOf(minOf(20, nodeId.length))
            }
        }
    }
}
