package com.termuxai.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

class MyResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RESULT = "com.termuxai.agent.ACTION_RESULT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        var stdout = ""
        var stderr = ""
        var exitCode = -1

        var found = false
        for (key in listOf("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE", "result", "RESULT")) {
            val bundle = intent.getBundleExtra(key)
            if (bundle != null) {
                stdout = bundle.getString("stdout") ?: ""
                stderr = bundle.getString("stderr") ?: ""
                val ec = bundle.get("exitCode")
                exitCode = when (ec) {
                    is Int -> ec
                    is String -> ec.toIntOrNull() ?: -1
                    else -> -1
                }
                found = true
                break
            }
        }
        if (!found) {
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

        val resultIntent = Intent(ACTION_RESULT).apply {
            putExtra("stdout", stdout)
            putExtra("stderr", stderr)
            putExtra("exit_code", exitCode)
        }
        context.sendBroadcast(resultIntent)
    }
}
