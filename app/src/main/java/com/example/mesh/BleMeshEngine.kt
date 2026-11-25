@file:Suppress("DEPRECATION")

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
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.ContactsManager
import com.example.data.CryptoManager
import com.example.model.MeshEngineStatus
import com.example.model.MeshIdentity
import com.example.model.MeshPowerMode
import com.example.model.NearbyPeer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * محرك شبكة BLE Mesh على طريقة bitchat:
 *  - كل جهاز يعمل كـ Peripheral (GATT server) وكـ Central (GATT client) في آنٍ واحد.
 *  - اتصالات GATT مستمرة (لا اتصال جديد لكل رسالة)، مع تفاوض MTU حتى 512.
 *  - حزم ثنائية بمعرّفات 8 بايت (انظر MeshPacketCodec) وتدفق بإطارات ذات بادئة طول.
 *  - فيضان مُنظَّم (TTL + منع التكرار + عدم الإرجاع للمصدر) مع مزامنة store-and-forward عبر HELLO.
 */
@SuppressLint("MissingPermission")
class BleMeshEngine(
    private val context: Context,
    private val cryptoManager: CryptoManager,
    private val contactsManager: ContactsManager,
    private val onPeerKeyLearned: (peerId: String, x25519KeyHex: String) -> Unit = { _, _ -> },
    private val onPacketReceived: suspend (MeshPacket) -> Unit
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Unhandled coroutine error", e)
        }
    )

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    // ---- اتصالات Central (نحن العميل) ----
    private class ClientLink(val device: BluetoothDevice) {
        @Volatile var gatt: BluetoothGatt? = null
        @Volatile var rx: BluetoothGattCharacteristic? = null
        @Volatile var payloadSize: Int = 20
        @Volatile var ready: Boolean = false
        @Volatile var nodeId: String? = null
        @Volatile var writeAck: CompletableDeferred<Boolean>? = null
        val setupDone = CompletableDeferred<Boolean>()
        val sendMutex = Mutex()
        val pending = AtomicInteger(0)
        val closed = AtomicBoolean(false)
        val address: String get() = device.address
    }

    private val clientLinks = ConcurrentHashMap<String, ClientLink>()

    // ---- اتصالات Peripheral (الطرف الآخر هو العميل) ----
    private val subscribedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val serverMtus = ConcurrentHashMap<String, Int>()
    private val serverNotifyAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val serverSendMutexes = ConcurrentHashMap<String, Mutex>()
    private val serverAddrNode = ConcurrentHashMap<String, String>()

    private val inboundBuffers = ConcurrentHashMap<String, StreamFrameBuffer>()

    // ---- اكتشاف ----
    private val discoveredDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val addressToNodeId = ConcurrentHashMap<String, String>()
    private val nodeFirstSeen = ConcurrentHashMap<String, Long>()
    private val noMetaSeen = ConcurrentHashMap<String, Int>()
    private val connectFailures = ConcurrentHashMap<String, Pair<Long, Int>>() // addr -> (nextAttemptAt, failures)
    private val connectQueue = ConcurrentLinkedQueue<String>()
    private val queuedAddresses = ConcurrentHashMap.newKeySet<String>()

    private val peerMap = ConcurrentHashMap<String, NearbyPeer>()
    private val lastSyncPerNode = ConcurrentHashMap<String, Long>()

    private class SeenCache(private val max: Int) {
        private val map = object : LinkedHashMap<String, Boolean>(512, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
                size > max
        }

        @Synchronized fun add(id: String): Boolean = map.put(id, true) == null
        @Synchronized fun snapshot(): List<String> = map.keys.toList()
        @Synchronized fun clear() = map.clear()
    }

    private val seenPackets = SeenCache(3000)
    private val outboxCache = ArrayList<MeshPacket>()

    private val _nearbyPeers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val nearbyPeers: StateFlow<List<NearbyPeer>> = _nearbyPeers.asStateFlow()

    private val _status = MutableStateFlow(MeshEngineStatus())
    val status: StateFlow<MeshEngineStatus> = _status.asStateFlow()

    private var currentIdentity: MeshIdentity = cryptoManager.getOrCreateIdentity()
    private var currentPowerMode: MeshPowerMode = MeshPowerMode.BALANCED
    @Volatile private var isStealthMode: Boolean = cryptoManager.isStealthModeEnabled()
    @Volatile private var maxHopTtl: Int = cryptoManager.getMaxHopTtl()
    private var maintenanceJob: Job? = null
    private var connectJob: Job? = null
    @Volatile private var isStarted = false
    @Volatile private var lastPeerEmit = 0L

    // =====================================================================
    // واجهة عامة
    // =====================================================================

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

    fun refreshPeersFromContacts() = emitPeers()

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
        synchronized(outboxCache) { outboxCache.clear() }
        peerMap.clear()
        addressToNodeId.clear()
        nodeFirstSeen.clear()
        lastSyncPerNode.clear()
        emitPeers()
        _status.value = _status.value.copy(cachedPacketsCount = 0, packetsRelayed = 0)
    }

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
        startConnectLoop()

        maintenanceJob?.cancel()
        maintenanceJob = scope.launch {
            delay(1_500L)
            safely { if (isStarted && !isStealthMode) sendNodeAnnounce() }
            var tick = 0
            while (isActive && isStarted) {
                delay(12_000)
                tick++
                safely {
                    val now = System.currentTimeMillis()
                    var changed = false
                    val iter = peerMap.entries.iterator()
                    while (iter.hasNext()) {
                        val e = iter.next()
                        if (now - e.value.lastSeenMillis > PEER_TIMEOUT_MS) {
                            iter.remove()
                            changed = true
                        }
                    }
                    synchronized(outboxCache) {
                        outboxCache.removeAll { now - it.timestamp > MAX_PACKET_AGE_MS }
                        _status.value = _status.value.copy(cachedPacketsCount = outboxCache.size)
                    }
                    if (changed) emitPeers()
                    if (!isStealthMode) {
                        if (tick % 4 == 0) sendNodeAnnounce()
                        if (tick % 5 == 0) sendHelloBroadcast() // gossip sync دوري
                    }
                }
            }
        }
    }

    fun stop() {
        isStarted = false
        maintenanceJob?.cancel()
        connectJob?.cancel()
        connectQueue.clear()
        queuedAddresses.clear()
        try { if (hasScanPermission()) scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        try { if (hasAdvertisePermission()) advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        clientLinks.values.toList().forEach { closeLink(it) }
        clientLinks.clear()
        try { if (hasConnectPermission()) gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        txCharacteristic = null
        subscribedCentrals.clear()
        serverAddrNode.clear()
        inboundBuffers.clear()
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

    fun broadcastPacket(
        packet: MeshPacket,
        cacheForStoreAndForward: Boolean = true,
        exceptSourceKey: String? = null
    ) {
        seenPackets.add(packet.packetId)
        if (cacheForStoreAndForward && packet.type != MeshPacketType.PEER_HELLO) cachePacket(packet)
        val framed = encodeForWire(packet) ?: return
        sendToAllLinks(framed, exceptSourceKey)
    }

    fun getPeerX25519Key(peerId: String): String? {
        return peerMap[peerId]?.x25519PublicKeyHex?.takeIf { it.length == 64 }
            ?: contactsManager.getContact(peerId)?.x25519PublicKeyHex?.takeIf { it.length == 64 }
    }

    /** نافذة إعادة التشغيل، توقيع Ed25519، ربط senderId بالمفتاح، تثبيت المفاتيح، والحظر. */
    internal fun validateIncomingPacket(packet: MeshPacket, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (packet.senderId == currentIdentity.nodeId) return false
        if (contactsManager.isBlocked(packet.senderId)) return false
        if (nowMs - packet.timestamp > MAX_PACKET_AGE_MS) return false
        if (packet.timestamp - nowMs > MAX_FUTURE_SKEW_MS) return false

        val sigOk = cryptoManager.verifyPacketIdentityAndSignature(
            claimedSenderId = packet.senderId,
            publicKeyHex = packet.senderPubKeyHex,
            data = MeshPacketCodec.signingBytes(packet),
            signatureB64 = packet.signature
        )
        if (!sigOk) return false

        val pinned = contactsManager.getContact(packet.senderId)
        if (pinned != null && pinned.publicKeyHex.isNotBlank() &&
            !pinned.publicKeyHex.equals(packet.senderPubKeyHex, ignoreCase = true)
        ) return false
        return true
    }

    // =====================================================================
    // ترميز وإرسال
    // =====================================================================

    private fun encodeForWire(packet: MeshPacket): ByteArray? {
        return try {
            MeshPacketCodec.withLengthPrefix(MeshPacketCodec.encodeFrame(packet))
        } catch (e: Exception) {
            Log.w(TAG, "Dropping unencodable packet ${packet.type}: ${e.message}")
            null
        }
    }

    private fun cachePacket(packet: MeshPacket) {
        synchronized(outboxCache) {
            if (outboxCache.none { it.packetId == packet.packetId }) {
                outboxCache.add(packet)
                if (outboxCache.size > MAX_OUTBOX_PACKETS) {
                    // FIX: كانت قطع الوسائط الكبيرة تطرد الرسائل النصية القديمة؛ نطرد الوسائط أولاً
                    val bulkIdx = outboxCache.indexOfFirst {
                        it.type == MeshPacketType.MEDIA_CHUNK || it.type == MeshPacketType.CHAT_MEDIA
                    }
                    outboxCache.removeAt(if (bulkIdx >= 0) bulkIdx else 0)
                }
            }
            _status.value = _status.value.copy(cachedPacketsCount = outboxCache.size)
        }
    }

    private fun sendToAllLinks(framed: ByteArray, exceptSourceKey: String?) {
        scope.launch {
            for ((addr, link) in clientLinks) {
                if (!link.ready || exceptSourceKey == "c:$addr") continue
                launch { sendClient(link, framed) }
            }
            for ((addr, dev) in subscribedCentrals) {
                if (exceptSourceKey == "s:$addr") continue
                // تجنّب إرسال نسخة مكررة إذا كان لنفس العقدة رابط عميل جاهز
                val node = serverAddrNode[addr]
                if (node != null && clientLinks.values.any { it.ready && it.nodeId == node }) continue
                launch { notifyDevice(dev, framed) }
            }
        }
    }

    private suspend fun sendDirect(sourceKey: String, framed: ByteArray) {
        when {
            sourceKey.startsWith("c:") -> clientLinks[sourceKey.removePrefix("c:")]?.let { sendClient(it, framed) }
            sourceKey.startsWith("s:") -> subscribedCentrals[sourceKey.removePrefix("s:")]?.let { notifyDevice(it, framed) }
        }
    }

    private suspend fun sendClient(link: ClientLink, framed: ByteArray) {
        if (!link.ready || link.closed.get()) return
        if (link.pending.incrementAndGet() > MAX_PENDING_PER_LINK) {
            link.pending.decrementAndGet()
            return
        }
        try {
            link.sendMutex.withLock {
                val gatt = link.gatt ?: return
                val rx = link.rx ?: return
                for (chunk in splitBytes(framed, link.payloadSize)) {
                    val ack = CompletableDeferred<Boolean>()
                    link.writeAck = ack
                    val started = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(
                                rx, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                        } else {
                            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            rx.value = chunk
                            gatt.writeCharacteristic(rx)
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (!started || !(withTimeoutOrNull(WRITE_TIMEOUT_MS) { ack.await() } ?: false)) {
                        handleLinkClosed(link)
                        return
                    }
                }
            }
        } finally {
            link.pending.decrementAndGet()
        }
    }

    private suspend fun notifyDevice(device: BluetoothDevice, framed: ByteArray) {
        val server = gattServer ?: return
        val char = txCharacteristic ?: return
        if (!hasConnectPermission()) return
        val addr = device.address
        val mutex = serverSendMutexes.getOrPut(addr) { Mutex() }
        val chunkSize = (serverMtus[addr] ?: 20).coerceIn(20, 509)
        mutex.withLock {
            for (chunk in splitBytes(framed, chunkSize)) {
                if (!subscribedCentrals.containsKey(addr)) return
                val ack = CompletableDeferred<Boolean>()
                serverNotifyAcks[addr] = ack
                val started = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        server.notifyCharacteristicChanged(device, char, false, chunk) ==
                            android.bluetooth.BluetoothStatusCodes.SUCCESS
                    } else {
                        char.value = chunk
                        server.notifyCharacteristicChanged(device, char, false)
                    }
                } catch (_: Exception) {
                    false
                }
                if (!started) {
                    serverNotifyAcks.remove(addr)
                    return
                }
                if (!(withTimeoutOrNull(WRITE_TIMEOUT_MS) { ack.await() } ?: false)) return
            }
        }
    }

    private fun splitBytes(data: ByteArray, size: Int): List<ByteArray> {
        val step = size.coerceAtLeast(1)
        val out = ArrayList<ByteArray>((data.size + step - 1) / step)
        var i = 0
        while (i < data.size) {
            out.add(data.copyOfRange(i, minOf(i + step, data.size)))
            i += step
        }
        return out
    }

    // =====================================================================
    // HELLO / ANNOUNCE
    // =====================================================================

    private fun shortId(packetId: String) = packetId.replace("-", "").take(12)

    private fun buildHelloPacket(): MeshPacket {
        val recent = seenPackets.snapshot().takeLast(40).map { shortId(it) }
        val payload = JSONObject().apply { put("seen", JSONArray(recent)) }.toString()
        val base = MeshPacket(
            type = MeshPacketType.PEER_HELLO,
            senderId = currentIdentity.nodeId,
            senderName = currentIdentity.nickname,
            senderPubKeyHex = currentIdentity.publicKeyHex,
            senderX25519KeyHex = currentIdentity.x25519PublicKeyHex,
            targetId = "*",
            isChannel = true,
            ttl = 1,
            payload = payload
        )
        return base.copy(signature = cryptoManager.signPayload(MeshPacketCodec.signingBytes(base)))
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
            payload = "{}"
        )
        broadcastPacket(
            base.copy(signature = cryptoManager.signPayload(MeshPacketCodec.signingBytes(base))),
            cacheForStoreAndForward = true
        )
    }

    private suspend fun sendHelloTo(sourceKey: String) {
        if (isStealthMode) return
        val framed = encodeForWire(buildHelloPacket()) ?: return
        sendDirect(sourceKey, framed)
    }

    // =====================================================================
    // GATT server (Peripheral)
    // =====================================================================

    private fun setupGattServer() {
        if (!hasConnectPermission()) return
        try {
            val server = bluetoothManager?.openGattServer(context, gattServerCallback) ?: return
            val service = BluetoothGattService(MESH_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
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
            gattServer = server
            txCharacteristic = txChar
            server.addService(service)
        } catch (e: Exception) {
            Log.e(TAG, "GATT server setup failed", e)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val addr = device.address
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribedCentrals.remove(addr)
                inboundBuffers.remove("s:$addr")
                serverMtus.remove(addr)
                serverAddrNode.remove(addr)
                serverSendMutexes.remove(addr)
                serverNotifyAcks.remove(addr)?.complete(false)
            }
            updateConnectionStatus()
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            serverMtus[device.address] = (mtu - 3).coerceIn(20, 509)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            serverNotifyAcks.remove(device.address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enable = value != null &&
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                val addr = device.address
                if (enable) {
                    subscribedCentrals[addr] = device
                    scope.launch {
                        delay(200)
                        safely { sendHelloTo("s:$addr") }
                    }
                } else {
                    subscribedCentrals.remove(addr)
                }
                updateConnectionStatus()
            }
            respond(device, requestId, responseNeeded)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) {
            respond(device, requestId, responseNeeded)
            if (!preparedWrite && characteristic.uuid == MESH_RX_CHAR_UUID && value != null) {
                handleInboundBytes("s:${device.address}", value)
            }
        }

        private fun respond(device: BluetoothDevice, requestId: Int, needed: Boolean) {
            if (!needed || !hasConnectPermission()) return
            try {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            } catch (_: Exception) {
            }
        }
    }

    // =====================================================================
    // الإعلان والمسح
    // =====================================================================

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
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advMode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        // الحزمة الأساسية: Flags(3) + UUID 128-بت(18) = 21 بايت. الهوية في Scan Response.
        val advData = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(
                LOCCHAT_MANUFACTURER_ID,
                encodeBleNodeMeta(currentIdentity.nodeId, currentIdentity.nickname, isStealthMode)
            )
            .build()

        try {
            adv.startAdvertising(settings, advData, scanResponse, advertiseCallback)
        } catch (e: Exception) {
            Log.e(TAG, "startAdvertising failed", e)
        }
    }

    private fun restartAdvertising() {
        try {
            if (hasAdvertisePermission()) advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        startAdvertising()
    }

    private fun startScanning() {
        if (!hasScanPermission()) return
        val bleScanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        scanner = bleScanner

        val scanMode = when (currentPowerMode) {
            MeshPowerMode.HIGH_PERFORMANCE -> ScanSettings.SCAN_MODE_LOW_LATENCY
            MeshPowerMode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
            MeshPowerMode.BATTERY_SAVER -> ScanSettings.SCAN_MODE_LOW_POWER
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MESH_SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(scanMode).build()
        try {
            bleScanner.startScan(listOf(filter), settings, scanCallback)
            _status.value = _status.value.copy(
                isHardwareAvailable = true,
                isScanning = true,
                statusMessage = "شبكة BLE Mesh نشطة (${currentPowerMode.labelAr})"
            )
        } catch (e: Exception) {
            Log.e(TAG, "startScan failed", e)
        }
    }

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
            safely { handleScanResult(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { r -> safely { handleScanResult(r) } }
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

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        discoveredDevices[address] = device

        val meta = result.scanRecord?.getManufacturerSpecificData(LOCCHAT_MANUFACTURER_ID)
            ?.let { parseBleNodeMeta(it) }

        var advertisedId: String? = null
        val metaNodeId: String? = meta?.nodeId
        if (meta != null && !meta.stealth && metaNodeId != null) {
            advertisedId = metaNodeId
            if (metaNodeId == currentIdentity.nodeId) return
            addressToNodeId[address] = metaNodeId
            nodeFirstSeen.putIfAbsent(metaNodeId, System.currentTimeMillis())

            val existing = peerMap[metaNodeId]
            peerMap[metaNodeId] = NearbyPeer(
                peerId = metaNodeId,
                nickname = meta.nickname.ifBlank { existing?.nickname ?: "عقدة $metaNodeId" },
                rssi = result.rssi,
                hopCount = existing?.hopCount ?: 1,
                isVerified = contactsManager.isVerified(metaNodeId),
                isBlocked = contactsManager.isBlocked(metaNodeId),
                publicKeyHex = existing?.publicKeyHex ?: "",
                x25519PublicKeyHex = existing?.x25519PublicKeyHex ?: "",
                fingerprint = existing?.fingerprint ?: metaNodeId,
                lastSeenMillis = System.currentTimeMillis(),
                isConnected = existing?.isConnected ?: false
            )
            emitPeersThrottled()
        } else if (meta == null) {
            // لم تصل الـ Scan Response بعد؛ انتظر بضع نتائج قبل الاتصال بدون هوية.
            val n = (noMetaSeen[address] ?: 0) + 1
            noMetaSeen[address] = n
            if (n < 3 && addressToNodeId[address] == null) return
        }

        considerConnecting(address, advertisedId ?: addressToNodeId[address])
    }

    // =====================================================================
    // إدارة الاتصالات المستمرة (Central)
    // =====================================================================

    private fun considerConnecting(address: String, nodeId: String?) {
        if (!isStarted || !hasConnectPermission()) return
        if (clientLinks.containsKey(address) || queuedAddresses.contains(address)) return
        if (clientLinks.size >= MAX_CLIENT_LINKS) return
        if (nodeId != null && clientLinks.values.any { it.nodeId == nodeId }) return

        val now = System.currentTimeMillis()
        val failure = connectFailures[address]
        if (failure != null && now < failure.first) return

        // كسر التعادل: الطرف الأصغر معرّفًا يبادر بالاتصال لتفادي اتصالين متقابلين.
        // احتياطيًا: إن لم يتصل بنا الطرف الآخر خلال مهلة، نتصل نحن.
        if (nodeId != null && currentIdentity.nodeId > nodeId) {
            val inbound = serverAddrNode.values.contains(nodeId)
            val waited = now - (nodeFirstSeen[nodeId] ?: now)
            if (inbound || waited < TIEBREAK_FALLBACK_MS) return
        }

        if (queuedAddresses.add(address)) connectQueue.add(address)
    }

    private fun startConnectLoop() {
        connectJob?.cancel()
        connectJob = scope.launch {
            while (isActive && isStarted) {
                val address = connectQueue.poll()
                if (address == null) {
                    delay(400)
                    continue
                }
                queuedAddresses.remove(address)
                val device = discoveredDevices[address] ?: continue
                if (clientLinks.containsKey(address) || clientLinks.size >= MAX_CLIENT_LINKS) continue
                safely { connectOnce(device) }
                delay(150)
            }
        }
    }

    private suspend fun connectOnce(device: BluetoothDevice) {
        val address = device.address
        val link = ClientLink(device)
        clientLinks[address] = link
        val gatt = try {
            device.connectGatt(context, false, clientCallback(link), BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Log.w(TAG, "connectGatt failed: ${e.message}")
            null
        }
        if (gatt == null) {
            clientLinks.remove(address, link)
            noteConnectFailure(address)
            return
        }
        link.gatt = gatt
        val ok = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { link.setupDone.await() } ?: false
        if (!ok) {
            handleLinkClosed(link)
            noteConnectFailure(address)
            return
        }
        connectFailures.remove(address)
        updateConnectionStatus()
        sendHelloTo("c:$address")
    }

    private fun noteConnectFailure(address: String) {
        val prev = connectFailures[address]?.second ?: 0
        val n = prev + 1
        val backoff = minOf(60_000L, 3_000L * (1L shl minOf(n, 5)))
        connectFailures[address] = (System.currentTimeMillis() + backoff) to n
    }

    private fun clientCallback(link: ClientLink) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                try {
                    if (!gatt.requestMtu(MTU_REQUEST)) gatt.discoverServices()
                } catch (_: Exception) {
                    handleLinkClosed(link)
                }
            } else {
                handleLinkClosed(link)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            link.payloadSize = if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) {
                (mtu - 3).coerceIn(20, 509)
            } else 20
            try {
                gatt.discoverServices()
            } catch (_: Exception) {
                handleLinkClosed(link)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(MESH_SERVICE_UUID)
            val rx = service?.getCharacteristic(MESH_RX_CHAR_UUID)
            val tx = service?.getCharacteristic(MESH_TX_CHAR_UUID)
            val cccd = tx?.getDescriptor(CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || rx == null || tx == null || cccd == null) {
                handleLinkClosed(link)
                return
            }
            link.rx = rx
            val started = try {
                gatt.setCharacteristicNotification(tx, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
            } catch (_: Exception) {
                false
            }
            if (!started) handleLinkClosed(link)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                link.ready = true
                link.setupDone.complete(true)
            } else {
                handleLinkClosed(link)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            link.writeAck?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (characteristic.uuid == MESH_TX_CHAR_UUID) handleInboundBytes("c:${link.address}", value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == MESH_TX_CHAR_UUID) {
                characteristic.value?.let { handleInboundBytes("c:${link.address}", it) }
            }
        }
    }

    private fun handleLinkClosed(link: ClientLink) {
        if (!link.closed.compareAndSet(false, true)) return
        link.ready = false
        link.setupDone.complete(false)
        link.writeAck?.complete(false)
        closeLinkResources(link)
        clientLinks.remove(link.address, link)
        inboundBuffers.remove("c:${link.address}")
        link.nodeId?.let { id ->
            peerMap[id]?.let { peerMap[id] = it.copy(isConnected = false) }
        }
        updateConnectionStatus()
        emitPeersThrottled()
    }

    private fun closeLink(link: ClientLink) {
        link.closed.set(true)
        link.ready = false
        link.setupDone.complete(false)
        link.writeAck?.complete(false)
        closeLinkResources(link)
    }

    private fun closeLinkResources(link: ClientLink) {
        try {
            link.gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            link.gatt?.close()
        } catch (_: Exception) {
        }
    }

    private fun updateConnectionStatus() {
        val count = clientLinks.values.count { it.ready } + subscribedCentrals.size
        _status.value = _status.value.copy(activeGattConnections = count)
    }

    // =====================================================================
    // الاستقبال والترحيل
    // =====================================================================

    /** sourceKey = "c:<addr>" (وصلت عبر اتصال نحن بدأناه) أو "s:<addr>" (كتب الطرف الآخر إلينا). */
    private fun handleInboundBytes(sourceKey: String, chunk: ByteArray) {
        val buf = inboundBuffers.getOrPut(sourceKey) { StreamFrameBuffer() }
        val frames = synchronized(buf) { buf.feed(chunk) }
        for (frame in frames) {
            scope.launch {
                val packet = MeshPacketCodec.decodeFrame(frame) ?: return@launch
                processDecodedPacket(sourceKey, packet)
            }
        }
    }

    private suspend fun processDecodedPacket(sourceKey: String, packet: MeshPacket) {
        if (!validateIncomingPacket(packet)) return

        // هوية الجار المباشر تُستنتج فقط من الحزم التي بدأها (hop = 0)
        if (packet.hopCount == 0) {
            if (sourceKey.startsWith("c:")) {
                clientLinks[sourceKey.removePrefix("c:")]?.nodeId = packet.senderId
            } else {
                serverAddrNode[sourceKey.removePrefix("s:")] = packet.senderId
            }
            addressToNodeId[sourceKey.substring(2)] = packet.senderId
        }

        val prev = peerMap[packet.senderId]
        val resolvedX = packet.senderX25519KeyHex.ifBlank { prev?.x25519PublicKeyHex ?: "" }
        peerMap[packet.senderId] = NearbyPeer(
            peerId = packet.senderId,
            nickname = packet.senderName,
            rssi = prev?.rssi ?: -65,
            hopCount = packet.hopCount + 1,
            isVerified = contactsManager.isVerified(packet.senderId),
            isBlocked = contactsManager.isBlocked(packet.senderId),
            publicKeyHex = packet.senderPubKeyHex,
            x25519PublicKeyHex = resolvedX,
            fingerprint = cryptoManager.formatFingerprintFromHex(packet.senderPubKeyHex, resolvedX),
            lastSeenMillis = System.currentTimeMillis(),
            isConnected = packet.hopCount == 0
        )
        emitPeersThrottled()

        if (resolvedX.length == 64) onPeerKeyLearned(packet.senderId, resolvedX)

        if (packet.type == MeshPacketType.PEER_HELLO) {
            handlePeerHelloSync(sourceKey, packet)
            return
        }

        if (!seenPackets.add(packet.packetId)) return

        if (packet.type != MeshPacketType.NODE_ANNOUNCE) onPacketReceived(packet)

        // رسائل خاصة موجهة إلينا لا تُرحَّل
        val me = currentIdentity.nodeId
        if (!packet.isChannel && packet.targetId == me) return

        // FIX: TTL غير موقّع؛ نقصّه بسقف محلي حتى لا يُغرق مرسلٌ خبيث الشبكة بـ ttl=255
        val boundedTtl = minOf(packet.ttl, maxHopTtl)
        if (boundedTtl > 1) {
            delay(Random.nextLong(20L, 120L))
            val relayed = packet.copy(ttl = boundedTtl - 1, hopCount = packet.hopCount + 1)
            _status.value = _status.value.copy(packetsRelayed = _status.value.packetsRelayed + 1)
            broadcastPacket(relayed, cacheForStoreAndForward = true, exceptSourceKey = sourceKey)
        }
    }

    private suspend fun handlePeerHelloSync(sourceKey: String, hello: MeshPacket) {
        val peerSeen = HashSet<String>()
        try {
            val arr = JSONObject(hello.payload).optJSONArray("seen")
            if (arr != null) for (i in 0 until arr.length()) peerSeen.add(arr.optString(i))
        } catch (_: Exception) {
        }
        val missing = synchronized(outboxCache) {
            outboxCache.filter { it.ttl > 0 && !peerSeen.contains(shortId(it.packetId)) }.takeLast(MAX_SYNC_PACKETS)
        }
        for (pkt in missing) {
            val framed = encodeForWire(pkt) ?: continue
            sendDirect(sourceKey, framed)
            delay(30)
        }
    }

    // =====================================================================
    // مساعدات
    // =====================================================================

    private fun emitPeersThrottled() {
        val now = System.currentTimeMillis()
        if (now - lastPeerEmit < 1_500L) return
        lastPeerEmit = now
        emitPeers()
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

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(TAG, "Recovered from error", e)
        }
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasAdvertisePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

    data class BleNodeMeta(val nodeId: String?, val nickname: String, val stealth: Boolean)

    companion object {
        private const val TAG = "BleMeshEngine"

        val MESH_SERVICE_UUID: UUID = UUID.fromString("A7B80001-4E5F-42A1-9C3D-7E8F9A0B1C2D")
        val MESH_RX_CHAR_UUID: UUID = UUID.fromString("A7B80002-4E5F-42A1-9C3D-7E8F9A0B1C2D")
        val MESH_TX_CHAR_UUID: UUID = UUID.fromString("A7B80003-4E5F-42A1-9C3D-7E8F9A0B1C2D")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        internal const val LOCCHAT_MANUFACTURER_ID = 0x4C43
        private const val META_VERSION: Byte = 0x01
        private const val META_STEALTH: Byte = 0xFF.toByte()
        private const val META_MAX_BYTES = 27 // 31 - len/type(2) - company id(2)

        private const val MAX_PACKET_AGE_MS = 12 * 3_600_000L
        private const val MAX_FUTURE_SKEW_MS = 5 * 60_000L
        private const val PEER_TIMEOUT_MS = 150_000L // أطول ليبقى الأقران البعيدون (عدة قفزات) ثابتين في القائمة

        private const val MTU_REQUEST = 512
        private const val MAX_CLIENT_LINKS = 6
        private const val MAX_PENDING_PER_LINK = 64
        private const val MAX_SYNC_PACKETS = 40
        private const val MAX_OUTBOX_PACKETS = 300
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val WRITE_TIMEOUT_MS = 3_000L
        private const val TIEBREAK_FALLBACK_MS = 8_000L

        /**
         * Scan Response: [0x01][nodeId 16 بايت خام][اسم UTF-8 ≤ 10 بايت] (≤ 27 بايت).
         * في وضع التخفي: بايت واحد 0xFF فقط (لا هوية).
         */
        internal fun encodeBleNodeMeta(nodeId: String, nickname: String, stealth: Boolean): ByteArray {
            if (stealth) return byteArrayOf(META_STEALTH)
            val idBytes = MeshPacketCodec.hexToBytesOrNull(nodeId.removePrefix("LC-"), 16)
                ?: return byteArrayOf(META_STEALTH)
            val maxName = META_MAX_BYTES - 1 - 16
            val name = StringBuilder()
            var used = 0
            for (ch in nickname.replace("\n", " ").trim()) {
                val len = ch.toString().toByteArray(Charsets.UTF_8).size
                if (used + len > maxName) break
                name.append(ch)
                used += len
            }
            return byteArrayOf(META_VERSION) + idBytes + name.toString().toByteArray(Charsets.UTF_8)
        }

        internal fun parseBleNodeMeta(data: ByteArray): BleNodeMeta? {
            if (data.isEmpty()) return null
            if (data.size == 1 && data[0] == META_STEALTH) return BleNodeMeta(null, "", true)
            if (data[0] != META_VERSION || data.size < 17) return null
            val id = "LC-" + data.copyOfRange(1, 17).joinToString("") { "%02X".format(it) }
            val name = String(data, 17, data.size - 17, Charsets.UTF_8)
            return BleNodeMeta(id, name, false)
        }
    }
}
