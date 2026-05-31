package com.termuxai.agent

import android.content.Context
import android.content.Intent
import android.util.Base64

class AgentUpdater(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "agent_updater"
        private const val KEY_PUSHED_VC = "pushed_version_code"
    }

    fun pushIfNeeded() {
        val versionCode = apkVersionCode()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_PUSHED_VC, 0) >= versionCode) return

        push()

        prefs.edit().putInt(KEY_PUSHED_VC, versionCode).apply()
    }

    private fun push() {
        val script = context.resources.openRawResource(R.raw.agent)
            .bufferedReader().readText()
        val b64 = Base64.encodeToString(script.toByteArray(), Base64.NO_WRAP)

        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/system/bin/sh")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(
                "-c",
                "echo '$b64' | base64 -d > ~/agent.py && chmod +x ~/agent.py"
            ))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }

        try {
            context.startService(intent)
        } catch (_: Exception) {
        }
    }

    private fun apkVersionCode(): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } catch (_: Exception) {
            0
        }
    }
}
