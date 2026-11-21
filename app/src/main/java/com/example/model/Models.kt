package com.example.model

enum class AppLanguage(val code: String, val displayName: String, val isRtl: Boolean) {
    ARABIC("ar", "العربية", true),
    ENGLISH("en", "English", false),
    FRENCH("fr", "Français", false);

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: ARABIC
    }
}

enum class PostCategory(
    val id: String,
    val labelAr: String,
    val labelEn: String,
    val labelFr: String
) {
    ALL("ALL", "الكل", "All", "Tout"),
    GENERAL("GENERAL", "عام", "General", "Général"),
    ALERT("ALERT", "تنبيه", "Alert", "Alerte"),
    HELP("HELP", "مساعدة", "Help", "Aide"),
    EVENT("EVENT", "فعالية", "Event", "Événement"),
    MARKET("MARKET", "تبادل", "Exchange", "Échange");

    fun label(lang: AppLanguage): String = when (lang) {
        AppLanguage.ARABIC -> labelAr
        AppLanguage.ENGLISH -> labelEn
        AppLanguage.FRENCH -> labelFr
    }
}

enum class MessageType {
    TEXT,
    IMAGE,
    VOICE,
    SYSTEM
}

enum class DeliveryStatus {
    SENDING,
    SENT,
    RELAYED,
    DELIVERED
}

enum class MeshPowerMode(
    val labelAr: String,
    val labelEn: String,
    val labelFr: String
) {
    HIGH_PERFORMANCE("أداء فوري", "High Performance", "Haute performance"),
    BALANCED("متوازن", "Balanced", "Équilibré"),
    BATTERY_SAVER("توفير الطاقة", "Battery Saver", "Économie d'énergie");

    fun label(lang: AppLanguage): String = when (lang) {
        AppLanguage.ARABIC -> labelAr
        AppLanguage.ENGLISH -> labelEn
        AppLanguage.FRENCH -> labelFr
    }
}

data class MeshIdentity(
    val nodeId: String,
    val nickname: String,
    val publicKeyHex: String,
    val x25519PublicKeyHex: String = "",
    val fingerprint: String,
    val createdAt: Long
)

data class NearbyPeer(
    val peerId: String,
    val nickname: String,
    val rssi: Int,
    val hopCount: Int,
    val isVerified: Boolean,
    val isBlocked: Boolean = false,
    val publicKeyHex: String,
    val x25519PublicKeyHex: String = "",
    val fingerprint: String,
    val lastSeenMillis: Long,
    val isConnected: Boolean
)

data class PostComment(
    val id: String,
    val postId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val createdAt: Long
)

data class FeedPost(
    val id: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val imagePath: String?,
    val imageSizeKb: Int,
    val category: PostCategory,
    val ttlHours: Int,
    val createdAt: Long,
    val likesCount: Int,
    val isLikedByMe: Boolean,
    val reactions: Map<String, Int> = emptyMap(),
    val myReaction: String? = null,
    val comments: List<PostComment>,
    val hopCount: Int = 0
) {
    val expiresAt: Long get() = createdAt + ttlHours * 3_600_000L
}

data class ChatConversation(
    val id: String,
    val title: String,
    val isChannel: Boolean,
    val peerFingerprint: String = "",
    val peerX25519KeyHex: String = "",
    val isVerified: Boolean = false,
    val isProtectedChannel: Boolean = false,
    val lastMessage: String = "",
    val lastMessageTime: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val isOnline: Boolean = false
)

data class ChatMessage(
    val id: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val isFromMe: Boolean,
    val type: MessageType,
    val text: String,
    val mediaPath: String? = null,
    val voiceDurationSec: Int = 0,
    val replyPreview: String? = null,
    val reactions: Map<String, Int> = emptyMap(),
    val myReaction: String? = null,
    val timestamp: Long,
    val status: DeliveryStatus = DeliveryStatus.SENT,
    val isEncrypted: Boolean = true,
    val hopCount: Int = 0
)

data class VerifiedContact(
    val peerId: String,
    val nickname: String,
    val publicKeyHex: String,
    val x25519PublicKeyHex: String = "",
    val fingerprint: String,
    val verifiedAt: Long
)

data class MeshEngineStatus(
    val isHardwareAvailable: Boolean = true,
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val activeGattConnections: Int = 0,
    val packetsRelayed: Int = 0,
    val cachedPacketsCount: Int = 0,
    val powerMode: MeshPowerMode = MeshPowerMode.BALANCED,
    val statusMessage: String = "تهيئة شبكة البلوتوث..."
)
