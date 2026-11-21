package com.example.data

import com.example.model.VerifiedContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ContactsManager(
    private val verifiedDao: VerifiedContactDao,
    private val blockedDao: BlockedPeerDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _verifiedContacts = MutableStateFlow<List<VerifiedContact>>(emptyList())
    val verifiedContacts: StateFlow<List<VerifiedContact>> = _verifiedContacts.asStateFlow()

    private val _blockedPeerIds = MutableStateFlow<Set<String>>(emptySet())
    val blockedPeerIds: StateFlow<Set<String>> = _blockedPeerIds.asStateFlow()

    private val _blockedPeers = MutableStateFlow<List<BlockedPeerEntity>>(emptyList())
    val blockedPeers: StateFlow<List<BlockedPeerEntity>> = _blockedPeers.asStateFlow()

    init {
        scope.launch {
            verifiedDao.observeAll().collect { entities ->
                _verifiedContacts.value = entities.map {
                    VerifiedContact(
                        peerId = it.peerId,
                        nickname = it.nickname,
                        publicKeyHex = it.publicKeyHex,
                        x25519PublicKeyHex = it.x25519PublicKeyHex,
                        fingerprint = it.fingerprint,
                        verifiedAt = it.verifiedAt
                    )
                }
            }
        }
        scope.launch {
            blockedDao.observeAll().collect { entities ->
                _blockedPeers.value = entities
                _blockedPeerIds.value = entities.map { it.peerId.uppercase() }.toSet()
            }
        }
    }

    fun isVerified(peerId: String): Boolean {
        return _verifiedContacts.value.any { it.peerId.equals(peerId, ignoreCase = true) }
    }

    fun isBlocked(peerId: String): Boolean {
        return _blockedPeerIds.value.contains(peerId.trim().uppercase())
    }

    fun getContact(peerId: String): VerifiedContact? {
        return _verifiedContacts.value.firstOrNull { it.peerId.equals(peerId, ignoreCase = true) }
    }

    suspend fun verifyContact(
        peerId: String,
        nickname: String,
        fingerprint: String,
        publicKeyHex: String = "",
        x25519PublicKeyHex: String = ""
    ) {
        val cleanId = peerId.trim().uppercase()
        val cleanFp = fingerprint.trim().uppercase()
        val entity = VerifiedContactEntity(
            peerId = cleanId,
            nickname = nickname.trim().ifEmpty { cleanId },
            publicKeyHex = publicKeyHex,
            x25519PublicKeyHex = x25519PublicKeyHex,
            fingerprint = cleanFp,
            verifiedAt = System.currentTimeMillis()
        )
        verifiedDao.upsert(entity)
    }

    suspend fun blockPeer(peerId: String, nickname: String) {
        val cleanId = peerId.trim().uppercase()
        blockedDao.upsert(
            BlockedPeerEntity(
                peerId = cleanId,
                nickname = nickname.trim().ifEmpty { cleanId },
                blockedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun unblockPeer(peerId: String) {
        blockedDao.deleteById(peerId.trim().uppercase())
    }

    suspend fun removeVerification(peerId: String) {
        verifiedDao.deleteById(peerId.trim().uppercase())
    }

    suspend fun wipeAll() {
        verifiedDao.deleteAll()
        blockedDao.deleteAll()
    }
}
