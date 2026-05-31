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

        for (key in listOf("com.termux.EXTRA_PLUGIN_RESULT_BUNDLE", "result", "RESULT")) {
            val bundle = intent.getBundleExtra(key)
            if (bundle != null) {
                val sb = StringBuilder("bundle[$key] keys:")
                bundle.keySet().forEach { k ->
                    val v = bundle.get(k)
                    sb.append("\n  $k (${
                        if (v is String) "String"
                        else if (v is Int) "Int"
                        else v?.javaClass?.simpleName ?: "null"
                    }) = $v")
                }
                stdout = bundle.getString("stdout") ?: ""
                stderr = bundle.getString("stderr") ?: ""
                val ec = bundle.get("exit_code")
                exitCode = when (ec) {
                    is Int -> ec
                    is String -> ec.toIntOrNull() ?: -1
                    else -> -1
                }
                if (stdout.isNotEmpty() || stderr.isNotEmpty()) {
                    sb.insert(0, "--- 解析结果 ---\nstdout=$stdout\nstderr=$stderr\nexit_code=$exitCode\n\n--- Bundle dump ---")
                }
                stdout = sb.toString()
                break
            }
        }
        if (exitCode == -1 && stdout.isEmpty()) {
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
            } else {
                val sb = StringBuilder("intent extras (no bundle/json found):")
                intent.extras?.keySet()?.forEach { k ->
                    sb.append("\n  $k = ${intent.extras?.get(k)}")
                }
                stdout = sb.toString()
            }
        }

        val resultIntent = Intent(ACTION_TEST_RESULT).apply {
            putExtra("stdout", stdout)
            putExtra("stderr", stderr)
            putExtra("exit_code", exitCode)
        }
        context.sendBroadcast(resultIntent)
    }
}
