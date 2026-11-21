package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalIdentityDao {
    @Query("SELECT * FROM local_identities WHERE isActive = 1 LIMIT 1")
    fun observeActiveIdentity(): Flow<LocalIdentityEntity?>

    @Query("SELECT * FROM local_identities ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<LocalIdentityEntity>>

    @Query("SELECT * FROM local_identities WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveIdentity(): LocalIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LocalIdentityEntity)

    @Query("UPDATE local_identities SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE local_identities SET nickname = :nickname, isNicknameConfigured = 1, updatedAt = :updatedAt WHERE nodeId = :nodeId")
    suspend fun updateNickname(nodeId: String, nickname: String, updatedAt: Long)

    @Query("DELETE FROM local_identities")
    suspend fun deleteAll()
}

@Dao
interface ChannelMembershipDao {
    @Query("SELECT * FROM channel_memberships WHERE isActive = 1 ORDER BY lastSeenAt DESC")
    fun observeAllActive(): Flow<List<ChannelMembershipEntity>>

    @Query("SELECT * FROM channel_memberships WHERE channelId = :channelId AND isActive = 1 ORDER BY joinedAt ASC")
    fun observeMembersForChannel(channelId: String): Flow<List<ChannelMembershipEntity>>

    @Query("SELECT * FROM channel_memberships WHERE memberNodeId = :memberNodeId AND isActive = 1 ORDER BY joinedAt DESC")
    fun observeChannelsForMember(memberNodeId: String): Flow<List<ChannelMembershipEntity>>

    @Query("SELECT * FROM channel_memberships WHERE membershipId = :membershipId LIMIT 1")
    suspend fun getById(membershipId: String): ChannelMembershipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ChannelMembershipEntity)

    @Query("UPDATE channel_memberships SET memberNickname = :nickname, lastSeenAt = :seenAt WHERE memberNodeId = :memberNodeId")
    suspend fun updateMemberNickname(memberNodeId: String, nickname: String, seenAt: Long)

    @Query("UPDATE channel_memberships SET isActive = 0 WHERE channelId = :channelId AND memberNodeId = :memberNodeId")
    suspend fun markLeft(channelId: String, memberNodeId: String)

    @Query("DELETE FROM channel_memberships WHERE channelId = :channelId")
    suspend fun deleteByChannel(channelId: String)

    @Query("DELETE FROM channel_memberships")
    suspend fun deleteAll()
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Transaction
    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC")
    fun observeConversationsWithMessages(): Flow<List<ConversationWithMessages>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :chatId")
    suspend fun clearUnread(chatId: String)

    @Query("DELETE FROM conversations WHERE id = :chatId")
    suspend fun deleteById(chatId: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun observeMessagesForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE status = 'SENDING' ORDER BY timestamp ASC")
    fun observePendingMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun getMessageCountForChat(chatId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface FeedPostDao {
    @Query("SELECT * FROM feed_posts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<FeedPostEntity>>

    @Query("SELECT * FROM feed_posts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): FeedPostEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: FeedPostEntity)

    @Query("DELETE FROM feed_posts")
    suspend fun deleteAll()
}

@Dao
interface PostCommentDao {
    @Query("SELECT * FROM post_comments ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PostCommentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PostCommentEntity)

    @Query("DELETE FROM post_comments")
    suspend fun deleteAll()
}

@Dao
interface VerifiedContactDao {
    @Query("SELECT * FROM verified_contacts ORDER BY verifiedAt DESC")
    fun observeAll(): Flow<List<VerifiedContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VerifiedContactEntity)

    @Query("DELETE FROM verified_contacts WHERE peerId = :peerId")
    suspend fun deleteById(peerId: String)

    @Query("DELETE FROM verified_contacts")
    suspend fun deleteAll()
}

@Dao
interface BlockedPeerDao {
    @Query("SELECT * FROM blocked_peers ORDER BY blockedAt DESC")
    fun observeAll(): Flow<List<BlockedPeerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BlockedPeerEntity)

    @Query("DELETE FROM blocked_peers WHERE peerId = :peerId")
    suspend fun deleteById(peerId: String)

    @Query("DELETE FROM blocked_peers")
    suspend fun deleteAll()
}

@Dao
interface BlockReportDao {
    @Query("SELECT * FROM block_reports WHERE isDismissed = 0 ORDER BY reportedAt DESC")
    fun observeActive(): Flow<List<BlockReportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BlockReportEntity)

    @Query("UPDATE block_reports SET isDismissed = 1 WHERE reportId = :reportId")
    suspend fun dismiss(reportId: String)

    @Query("DELETE FROM block_reports")
    suspend fun deleteAll()
}
