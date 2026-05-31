package com.termuxai.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MyResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESULT = "com.termuxai.agent.ACTION_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val bundle = intent.getBundleExtra("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE")
        val stdout = bundle?.getString("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT") ?: ""
        val stderr = bundle?.getString("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR") ?: ""
        val exitCode = bundle?.getInt("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE") ?: -1

        val resultIntent = Intent(ACTION_RESULT).apply {
            putExtra("stdout", stdout)
            putExtra("stderr", stderr)
            putExtra("exit_code", exitCode)
        }
        context.sendBroadcast(resultIntent)
    }
}
