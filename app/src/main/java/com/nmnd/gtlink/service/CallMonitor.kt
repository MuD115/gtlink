package com.nmnd.gtlink.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.nmnd.gtlink.GtApp
import kotlinx.coroutines.launch

/**
 * Shows incoming calls on the watch, dismisses them when answered or ended, and
 * turns unanswered ones into a missed-call notification. PHONE_STATE is one of the
 * broadcasts still delivered to manifest receivers.
 */
class CallMonitor : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val app = context.applicationContext as GtApp
        if (!app.store.settings.value.forwardCalls) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // Android sends RINGING twice when the app can read the call log: once
                // without the number, once with it. Show the call once, with the best info.
                if (number == null && ringing) return
                ringing = true
                answered = false
                lastNumber = number ?: lastNumber
                val name = number?.let { lookupName(context, it) } ?: number ?: "Unknown caller"
                app.scope.launch { app.connection.sendIncomingCall(name, number) }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (ringing) answered = true
                ringing = false
                app.scope.launch { app.connection.stopCall() }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val missed = ringing && !answered
                ringing = false
                app.scope.launch {
                    app.connection.stopCall()
                    if (missed) {
                        val n = lastNumber
                        app.connection.sendMissedCall(n?.let { lookupName(context, it) } ?: n ?: "Unknown caller", n)
                    }
                }
            }
        }
    }

    private fun lookupName(context: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
    }

    companion object {
        @Volatile private var ringing = false
        @Volatile private var answered = false
        @Volatile private var lastNumber: String? = null
    }
}
