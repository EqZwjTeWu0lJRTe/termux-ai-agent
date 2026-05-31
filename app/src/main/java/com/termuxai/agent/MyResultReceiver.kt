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

        fun extractFromBundle(bundle: android.os.Bundle) {
            stdout = bundle.getString("stdout") ?: ""
            stderr = bundle.getString("stderr") ?: ""
            exitCode = bundle.getInt("exit_code", -1)
            if (stdout.isEmpty() && stderr.isEmpty()) {
                val sb = StringBuilder("bundle keys:")
                bundle.keySet().forEach { sb.append("\n  $it = ${bundle.get(it)}") }
                stdout = sb.toString()
            }
        }

        var found = false
        for (key in listOf("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE", "result", "RESULT")) {
            val bundle = intent.getBundleExtra(key)
            if (bundle != null) {
                extractFromBundle(bundle)
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
