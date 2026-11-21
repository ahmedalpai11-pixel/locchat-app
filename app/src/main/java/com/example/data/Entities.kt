package com.example.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "local_identities")
data class LocalIdentityEntity(
    @PrimaryKey val nodeId: String,
    val nickname: String,
    val ed25519PublicKeyHex: String,
    val x25519PublicKeyHex: String,
    val fingerprint: String,
    val isKeystoreProtected: Boolean,
    val isNicknameConfigured: Boolean,
    val isStealthEnabled: Boolean = false,
    val autoApplyTrustedBlocks: Boolean = true,
    val maxHopTtl: Int = 7,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isChannel: Boolean,
    val peerFingerprint: String,
    val peerX25519KeyHex: String = "",
    val channelPassphraseHash: String = "",
    val isVerified: Boolean,
    val lastMessage: String,
    val lastMessageTime: Long,
    val unreadCount: Int
)

@Entity(
    tableName = "channel_memberships",
    indices = [
        Index(value = ["channelId"]),
        Index(value = ["memberNodeId"])
    ]
)
data class ChannelMembershipEntity(
    @PrimaryKey val membershipId: String,
    val channelId: String,
    val channelName: String,
    val memberNodeId: String,
    val memberNickname: String,
    val role: String,
    val isProtected: Boolean,
    val passphraseHash: String = "",
    val isActive: Boolean = true,
    val joinedAt: Long,
    val lastSeenAt: Long
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["chatId", "timestamp"]),
        Index(value = ["senderId"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val senderId: String,
    val senderName: String,
    val isFromMe: Boolean,
    val type: String,
    val text: String,
    val mediaPath: String?,
    val voiceDurationSec: Int,
    val replyPreview: String?,
    val reactionsJson: String,
    val myReaction: String?,
    val timestamp: Long,
    val status: String,
    val isEncrypted: Boolean,
    val hopCount: Int
)

data class ConversationWithMessages(
    @Embedded val conversation: ConversationEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "chatId"
    )
    val messages: List<MessageEntity>
)

@Entity(tableName = "feed_posts")
data class FeedPostEntity(
    @PrimaryKey val id: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val imagePath: String?,
    val imageSizeKb: Int,
    val category: String,
    val ttlHours: Int,
    val createdAt: Long,
    val likesCount: Int,
    val isLikedByMe: Boolean,
    val reactionsJson: String = "{}",
    val myReaction: String? = null,
    val hopCount: Int
)

@Entity(tableName = "post_comments")
data class PostCommentEntity(
    @PrimaryKey val id: String,
    val postId: String,
    val authorId: String,
    val authorName: String,
    val text: String,
    val createdAt: Long
)

@Entity(tableName = "verified_contacts")
data class VerifiedContactEntity(
    @PrimaryKey val peerId: String,
    val nickname: String,
    val publicKeyHex: String,
    val x25519PublicKeyHex: String = "",
    val fingerprint: String,
    val verifiedAt: Long
)

@Entity(tableName = "blocked_peers")
data class BlockedPeerEntity(
    @PrimaryKey val peerId: String,
    val nickname: String,
    val blockedAt: Long
)

@Entity(
    tableName = "block_reports",
    indices = [Index(value = ["blockedPeerId"])]
)
data class BlockReportEntity(
    @PrimaryKey val reportId: String,
    val blockedPeerId: String,
    val blockedNickname: String,
    val reporterId: String,
    val reporterNickname: String,
    val reporterTrusted: Boolean,
    val reason: String,
    val isDismissed: Boolean = false,
    val reportedAt: Long
)
