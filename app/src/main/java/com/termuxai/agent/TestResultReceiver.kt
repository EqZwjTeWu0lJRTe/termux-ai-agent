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

        fun extractFromBundle(bundle: android.os.Bundle) {
            val keys = bundle.keySet()
            stdout = bundle.getString("stdout") ?: ""
            stderr = bundle.getString("stderr") ?: ""
            exitCode = bundle.getInt("exit_code", -1)
            if (stdout.isEmpty() && stderr.isEmpty() && keys.size > 0) {
                val sb = StringBuilder("bundle keys:")
                keys.forEach { sb.append("\n  $it = ${bundle.get(it)}") }
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
                    found = true
                } catch (_: Exception) {
                    stdout = jsonStr
                }
            }
        }
        if (!found) {
            val sb = StringBuilder("intent extras:")
            intent.extras?.keySet()?.forEach { k ->
                sb.append("\n  $k = ${intent.extras?.get(k)}")
            }
            stdout = sb.toString()
        }

        val resultIntent = Intent(ACTION_TEST_RESULT).apply {
            putExtra("stdout", stdout)
            putExtra("stderr", stderr)
            putExtra("exit_code", exitCode)
        }
        context.sendBroadcast(resultIntent)
    }
}
