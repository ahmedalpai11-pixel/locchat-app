package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        LocalIdentityEntity::class,
        ConversationEntity::class,
        ChannelMembershipEntity::class,
        MessageEntity::class,
        FeedPostEntity::class,
        PostCommentEntity::class,
        VerifiedContactEntity::class,
        BlockedPeerEntity::class,
        BlockReportEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class LocChatDatabase : RoomDatabase() {
    abstract fun localIdentityDao(): LocalIdentityDao
    abstract fun conversationDao(): ConversationDao
    abstract fun channelMembershipDao(): ChannelMembershipDao
    abstract fun messageDao(): MessageDao
    abstract fun feedPostDao(): FeedPostDao
    abstract fun postCommentDao(): PostCommentDao
    abstract fun verifiedContactDao(): VerifiedContactDao
    abstract fun blockedPeerDao(): BlockedPeerDao
    abstract fun blockReportDao(): BlockReportDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `local_identities` (
                        `nodeId` TEXT NOT NULL,
                        `nickname` TEXT NOT NULL,
                        `ed25519PublicKeyHex` TEXT NOT NULL,
                        `x25519PublicKeyHex` TEXT NOT NULL,
                        `fingerprint` TEXT NOT NULL,
                        `isKeystoreProtected` INTEGER NOT NULL,
                        `isNicknameConfigured` INTEGER NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`nodeId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `channel_memberships` (
                        `membershipId` TEXT NOT NULL,
                        `channelId` TEXT NOT NULL,
                        `channelName` TEXT NOT NULL,
                        `memberNodeId` TEXT NOT NULL,
                        `memberNickname` TEXT NOT NULL,
                        `role` TEXT NOT NULL,
                        `isProtected` INTEGER NOT NULL,
                        `passphraseHash` TEXT NOT NULL,
                        `isActive` INTEGER NOT NULL,
                        `joinedAt` INTEGER NOT NULL,
                        `lastSeenAt` INTEGER NOT NULL,
                        PRIMARY KEY(`membershipId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channel_memberships_channelId` ON `channel_memberships` (`channelId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_channel_memberships_memberNodeId` ON `channel_memberships` (`memberNodeId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_chatId_timestamp` ON `messages` (`chatId`, `timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_messages_senderId` ON `messages` (`senderId`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `local_identities` ADD COLUMN `isStealthEnabled` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `local_identities` ADD COLUMN `autoApplyTrustedBlocks` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `local_identities` ADD COLUMN `maxHopTtl` INTEGER NOT NULL DEFAULT 7")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `block_reports` (
                        `reportId` TEXT NOT NULL,
                        `blockedPeerId` TEXT NOT NULL,
                        `blockedNickname` TEXT NOT NULL,
                        `reporterId` TEXT NOT NULL,
                        `reporterNickname` TEXT NOT NULL,
                        `reporterTrusted` INTEGER NOT NULL,
                        `reason` TEXT NOT NULL,
                        `isDismissed` INTEGER NOT NULL,
                        `reportedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`reportId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_block_reports_blockedPeerId` ON `block_reports` (`blockedPeerId`)")
            }
        }

        @Volatile
        private var INSTANCE: LocChatDatabase? = null

        fun getInstance(context: Context): LocChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocChatDatabase::class.java,
                    "locchat_mesh.db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
