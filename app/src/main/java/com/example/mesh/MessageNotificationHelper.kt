package com.example.mesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.model.AppLanguage
import com.example.ui.stringsFor
import kotlin.math.abs

class MessageNotificationHelper(private val context: Context) {

    fun ensureNotificationChannel(language: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val strings = stringsFor(language)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            strings.notifChannelMessagesName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = strings.notifChannelMessagesDesc
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    fun shouldNotifyInBackground(isAppInForeground: Boolean): Boolean {
        return !isAppInForeground
    }

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun notifyIncomingMessageIfBackground(
        isAppInForeground: Boolean,
        chatId: String,
        isChannel: Boolean,
        senderName: String,
        messagePreview: String,
        language: AppLanguage,
        hideSensitiveContent: Boolean
    ): Boolean {
        if (!shouldNotifyInBackground(isAppInForeground)) return false
        if (!hasNotificationPermission()) return false

        ensureNotificationChannel(language)
        val strings = stringsFor(language)
        val cleanSender = senderName.trim().ifEmpty { chatId }
        val title = if (isChannel) {
            strings.notifChannelTitle(chatId, cleanSender)
        } else {
            strings.notifDirectTitle(cleanSender)
        }
        val bodyText = if (hideSensitiveContent) {
            strings.notifLockedPreview
        } else {
            messagePreview.trim().ifEmpty { strings.notifLockedPreview }.take(180)
        }

        val notifId = notificationIdForChat(chatId)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(EXTRA_CHAT_TITLE, if (isChannel) chatId else cleanSender)
            putExtra(EXTRA_IS_CHANNEL, isChannel)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val publicFallback = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(strings.notifPublicFallbackTitle)
            .setContentText(strings.notifLockedPreview)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicFallback)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancelNotificationForChat(chatId: String) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationIdForChat(chatId))
        } catch (_: Exception) {
        }
    }

    fun cancelAllMessageNotifications() {
        try {
            NotificationManagerCompat.from(context).cancelAll()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHANNEL_ID = "locchat_incoming_messages"
        const val EXTRA_CHAT_ID = "extra_open_chat_id"
        const val EXTRA_CHAT_TITLE = "extra_open_chat_title"
        const val EXTRA_IS_CHANNEL = "extra_open_chat_is_channel"

        fun notificationIdForChat(chatId: String): Int {
            return 5000 + (abs(chatId.hashCode()) % 50_000)
        }
    }
}
