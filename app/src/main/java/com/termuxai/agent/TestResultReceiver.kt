package com.termuxai.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class TestResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TEST_RESULT = "com.termuxai.agent.ACTION_TEST_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        var stdout = ""
        var stderr = ""
        var exitCode = -1

        val bundle = intent.getBundleExtra("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE")
        if (bundle != null) {
            stdout = bundle.getString("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE_STDOUT") ?: ""
            stderr = bundle.getString("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE_STDERR") ?: ""
            exitCode = bundle.getInt("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE_EXIT_CODE", -1)
        } else {
            val jsonStr = intent.getStringExtra("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE")
            if (jsonStr != null) {
                try {
                    val json = JSONObject(jsonStr)
                    stdout = json.optString("stdout", "")
                    stderr = json.optString("stderr", "")
                    exitCode = json.optInt("exit_code", -1)
                } catch (_: Exception) {
                    stdout = jsonStr
                }
            }
        }

        val resultIntent = Intent(ACTION_TEST_RESULT).apply {
            putExtra("stdout", stdout)
            putExtra("stderr", stderr)
            putExtra("exit_code", exitCode)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        context.sendBroadcast(resultIntent)
    }
}
