package com.nmnd.gtlink.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.nmnd.gtlink.GtApp
import com.nmnd.gtlink.link.Commands
import kotlinx.coroutines.launch

/**
 * Forwards phone notifications to the watch, clears them there when dismissed here,
 * and carries replies typed (or picked) on the watch back into the app that posted them.
 * The same listener grant is what lets [MediaBridge] see the active media session.
 */
class NotificationRelay : NotificationListenerService() {
    private val recent = LinkedHashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as GtApp
        val n = sbn.notification ?: return

        if (n.category == Notification.CATEGORY_TRANSPORT) {
            MediaBridge.pushCurrent(this)
            return
        }

        val settings = app.store.settings.value
        if (!settings.notificationsEnabled) return
        if (!app.connection.state.value.connected) return
        if (sbn.packageName == packageName) return
        if (sbn.packageName in settings.blockedApps) return
        if (settings.skipOngoing && (sbn.isOngoing || n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // Calls are handled by CallMonitor with the proper call screen.
        if (n.category == Notification.CATEGORY_CALL) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        // Apps re-post the same notification constantly (progress, re-sorts); only send real changes.
        val fingerprint = "$title|$text"
        synchronized(recent) {
            if (recent[sbn.key] == fingerprint) return
            recent[sbn.key] = fingerprint
            if (recent.size > 200) recent.remove(recent.keys.first())
        }

        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        val type = typeOf(sbn)
        val key = Replies.keyFor(sbn.key)
        val replyAction = if (settings.notificationReplies) Replies.findReplyAction(n) else null
        if (replyAction != null) Replies.remember(key, replyAction)

        app.scope.launch {
            app.connection.sendNotification(
                type, title ?: appName, appName, text, sbn.packageName,
                key = key, phoneId = sbn.id, channelId = n.channelId, replyable = replyAction != null,
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(recent) { recent.remove(sbn.key) }
        val app = application as GtApp
        val settings = app.store.settings.value
        if (!settings.syncDismissals || !app.connection.state.value.connected) return
        if (sbn.packageName == packageName || sbn.packageName in settings.blockedApps) return
        val key = Replies.keyFor(sbn.key)
        Replies.forget(key)
        app.scope.launch {
            app.connection.removeNotification(typeOf(sbn), sbn.packageName, key, sbn.id, sbn.notification?.channelId ?: "")
        }
    }

    private fun typeOf(sbn: StatusBarNotification): Int = when {
        sbn.packageName == Telephony.Sms.getDefaultSmsPackage(this) -> Commands.NotificationType.SMS
        sbn.notification?.category == Notification.CATEGORY_EMAIL -> Commands.NotificationType.EMAIL
        else -> Commands.NotificationType.GENERIC
    }
}

/**
 * Remembers the "Reply" action of notifications we forwarded, so a reply from the
 * watch can be handed to the app exactly as if it had been typed in the shade.
 */
object Replies {
    private val actions = object : LinkedHashMap<String, Notification.Action>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Notification.Action>?) = size > 100
    }

    /** Short, stable key for a notification; the watch echoes it back with the reply. */
    fun keyFor(sbnKey: String): String = "gt" + Integer.toHexString(sbnKey.hashCode())

    fun findReplyAction(n: Notification): Notification.Action? {
        val all = n.actions.orEmpty().toList() +
            runCatching { Notification.WearableExtender(n).actions }.getOrDefault(emptyList())
        return all.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
    }

    @Synchronized fun remember(key: String, action: Notification.Action) { actions[key] = action }
    @Synchronized fun forget(key: String) { actions.remove(key) }

    /** Returns false if the notification is gone or had no reply action. */
    @Synchronized
    fun reply(context: Context, key: String, text: String): Boolean {
        val action = actions[key] ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        val results = Bundle()
        for (input in inputs) results.putCharSequence(input.resultKey, text)
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return runCatching { action.actionIntent.send(context, 0, intent); true }.getOrDefault(false)
    }
}
