package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.BlockReportEntity
import com.example.data.ChannelMembershipEntity
import com.example.data.ConversationEntity
import com.example.data.LocChatDatabase
import com.example.data.LocalIdentityEntity
import com.example.data.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomSchemaTest {

    private lateinit var db: LocChatDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LocChatDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `local identities persist switch and wipe cleanly`() = runBlocking {
        val id1 = LocalIdentityEntity(
            nodeId = "LC-11223344",
            nickname = "أحمد",
            ed25519PublicKeyHex = "a".repeat(64),
            x25519PublicKeyHex = "b".repeat(64),
            fingerprint = "1122 • 3344 • 5566 • 7788",
            isKeystoreProtected = true,
            isNicknameConfigured = true,
            isActive = true,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        db.localIdentityDao().upsert(id1)

        val active = db.localIdentityDao().observeActiveIdentity().first()
        assertNotNull(active)
        assertEquals("أحمد", active?.nickname)
        assertTrue(active?.isKeystoreProtected == true)

        db.localIdentityDao().updateNickname("LC-11223344", "أحمد-جديد", 2000L)
        assertEquals("أحمد-جديد", db.localIdentityDao().getActiveIdentity()?.nickname)

        db.localIdentityDao().deactivateAll()
        assertNull(db.localIdentityDao().getActiveIdentity())

        val id2 = id1.copy(nodeId = "LC-99AABBCC", nickname = "سارة", isActive = true, updatedAt = 3000L)
        db.localIdentityDao().upsert(id2)
        assertEquals("LC-99AABBCC", db.localIdentityDao().getActiveIdentity()?.nodeId)
        assertEquals(2, db.localIdentityDao().observeAll().first().size)

        db.localIdentityDao().deleteAll()
        assertTrue(db.localIdentityDao().observeAll().first().isEmpty())
    }

    @Test
    fun `channel memberships store owners remote peers and soft leaves`() = runBlocking {
        val channelId = "#طوارئ-الحي"
        val owner = ChannelMembershipEntity(
            membershipId = "$channelId|LC-OWNER",
            channelId = channelId,
            channelName = channelId,
            memberNodeId = "LC-OWNER",
            memberNickname = "مالك القناة",
            role = "OWNER",
            isProtected = true,
            passphraseHash = "hash123",
            isActive = true,
            joinedAt = 100L,
            lastSeenAt = 100L
        )
        val peer = ChannelMembershipEntity(
            membershipId = "$channelId|LC-PEER1",
            channelId = channelId,
            channelName = channelId,
            memberNodeId = "LC-PEER1",
            memberNickname = "جار قريب",
            role = "MEMBER",
            isProtected = true,
            passphraseHash = "hash123",
            isActive = true,
            joinedAt = 200L,
            lastSeenAt = 250L
        )
        db.channelMembershipDao().upsert(owner)
        db.channelMembershipDao().upsert(peer)

        val channelMembers = db.channelMembershipDao().observeMembersForChannel(channelId).first()
        assertEquals(2, channelMembers.size)
        assertEquals("OWNER", channelMembers.first().role)

        val memberChannels = db.channelMembershipDao().observeChannelsForMember("LC-PEER1").first()
        assertEquals(1, memberChannels.size)
        assertEquals(channelId, memberChannels.first().channelId)

        db.channelMembershipDao().markLeft(channelId, "LC-PEER1")
        val remaining = db.channelMembershipDao().observeMembersForChannel(channelId).first()
        assertEquals(1, remaining.size)
        assertEquals("LC-OWNER", remaining.first().memberNodeId)
    }

    @Test
    fun `chat messages persist offline with status updates and relational queries`() = runBlocking {
        val conv = ConversationEntity(
            id = "#عام-الحي",
            title = "#عام-الحي",
            isChannel = true,
            peerFingerprint = "BROADCAST",
            isVerified = false,
            lastMessage = "مرحباً بالجميع",
            lastMessageTime = 5000L,
            unreadCount = 0
        )
        db.conversationDao().upsert(conv)

        val msg1 = MessageEntity(
            id = "m1",
            chatId = "#عام-الحي",
            senderId = "LC-ME",
            senderName = "أنا",
            isFromMe = true,
            type = "TEXT",
            text = "رسالة محلية بدون إنترنت",
            mediaPath = null,
            voiceDurationSec = 0,
            replyPreview = null,
            reactionsJson = "{}",
            myReaction = null,
            timestamp = 4000L,
            status = "SENDING",
            isEncrypted = true,
            hopCount = 0
        )
        val msg2 = msg1.copy(
            id = "m2",
            senderId = "LC-PEER2",
            senderName = "خالد",
            isFromMe = false,
            text = "تم الاستلام عبر قفزتين",
            timestamp = 5000L,
            status = "DELIVERED",
            hopCount = 2
        )
        db.messageDao().upsert(msg1)
        db.messageDao().upsert(msg2)

        assertEquals(1, db.messageDao().observePendingMessages().first().size)
        db.messageDao().updateStatus("m1", "DELIVERED")
        assertEquals(0, db.messageDao().observePendingMessages().first().size)

        val chatMessages = db.messageDao().observeMessagesForChat("#عام-الحي").first()
        assertEquals(2, chatMessages.size)
        assertEquals(2, db.messageDao().getMessageCountForChat("#عام-الحي"))

        val withMessages = db.conversationDao().observeConversationsWithMessages().first()
        assertEquals(1, withMessages.size)
        assertEquals(2, withMessages.first().messages.size)

        db.messageDao().deleteByChatId("#عام-الحي")
        assertEquals(0, db.messageDao().getMessageCountForChat("#عام-الحي"))
    }

    @Test
    fun `stealth settings, membership nickname sync, and block reports persist in room v5`() = runBlocking {
        val identity = LocalIdentityEntity(
            nodeId = "LC-ABCD1234",
            nickname = "سالم",
            ed25519PublicKeyHex = "a".repeat(64),
            x25519PublicKeyHex = "b".repeat(64),
            fingerprint = "ABCD • 1234 • 5678 • 90EF",
            isKeystoreProtected = true,
            isNicknameConfigured = true,
            isStealthEnabled = true,
            autoApplyTrustedBlocks = false,
            maxHopTtl = 7,
            isActive = true,
            createdAt = 100L,
            updatedAt = 100L
        )
        db.localIdentityDao().upsert(identity)
        val loaded = db.localIdentityDao().getActiveIdentity()
        assertNotNull(loaded)
        assertTrue(loaded?.isStealthEnabled == true)
        assertEquals(false, loaded?.autoApplyTrustedBlocks)
        assertEquals(7, loaded?.maxHopTtl)

        db.channelMembershipDao().upsert(
            ChannelMembershipEntity(
                membershipId = "#عام-الحي|LC-ABCD1234",
                channelId = "#عام-الحي",
                channelName = "#عام-الحي",
                memberNodeId = "LC-ABCD1234",
                memberNickname = "سالم",
                role = "OWNER",
                isProtected = false,
                passphraseHash = "",
                isActive = true,
                joinedAt = 100L,
                lastSeenAt = 100L
            )
        )
        db.channelMembershipDao().updateMemberNickname("LC-ABCD1234", "سالم-المحدث", 500L)
        val updatedMemberships = db.channelMembershipDao().observeChannelsForMember("LC-ABCD1234").first()
        assertEquals("سالم-المحدث", updatedMemberships.first().memberNickname)

        val report = BlockReportEntity(
            reportId = "LC-FRIEND1|LC-SPAM99",
            blockedPeerId = "LC-SPAM99",
            blockedNickname = "مزعج",
            reporterId = "LC-FRIEND1",
            reporterNickname = "أحمد",
            reporterTrusted = true,
            reason = "رسائل مزعجة متكررة",
            isDismissed = false,
            reportedAt = 900L
        )
        db.blockReportDao().upsert(report)
        assertEquals(1, db.blockReportDao().observeActive().first().size)

        db.blockReportDao().dismiss(report.reportId)
        assertEquals(0, db.blockReportDao().observeActive().first().size)

        db.blockReportDao().deleteAll()
        assertEquals(0, db.blockReportDao().observeActive().first().size)
    }
}
