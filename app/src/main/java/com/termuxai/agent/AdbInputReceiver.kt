package com.termuxai.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AdbInputReceiver : BroadcastReceiver() {

    companion object {
        var pendingMessage: String? = null
        var onMessageReceived: ((String) -> Unit)? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val text = when {
            intent.hasExtra(Intent.EXTRA_TEXT) ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.hasExtra("text") ->
                intent.getStringExtra("text")
            else -> null
        }

        if (text.isNullOrBlank()) return

        onMessageReceived?.invoke(text)
        pendingMessage = text
    }
}
