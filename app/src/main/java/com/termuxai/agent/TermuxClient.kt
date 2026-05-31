package com.termuxai.agent

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

class TermuxClient(private val context: Context) {

    companion object {
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    }

    private val prefs by lazy {
        context.getSharedPreferences("config", Context.MODE_PRIVATE)
    }

    private val scriptPath: String
        get() = prefs.getString(
            "script_path",
            "/data/data/com.termux/files/home/agent.py"
        ) ?: "/data/data/com.termux/files/home/agent.py"

    private val apiKey: String
        get() = prefs.getString("api_key", "") ?: ""

    private val handler = Handler(Looper.getMainLooper())

    fun isTermuxRunning(): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.runningAppProcesses?.any { it.processName == "com.termux" } == true
        } catch (_: Exception) {
            false
        }
    }

    fun execute(userInput: String, onUnavailable: (() -> Unit)? = null) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            Intent(context, MyResultReceiver::class.java),
            pendingIntentFlags()
        )

        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(scriptPath, userInput, apiKey))
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        }

        if (!tryRunCommand(intent)) {
            onUnavailable?.invoke()
            waitForTermux(intent)
        }
    }

    private fun waitForTermux(intent: Intent) {
        handler.postDelayed({
            if (isTermuxRunning() && tryRunCommand(intent)) return@postDelayed
            waitForTermux(intent)
        }, 3000)
    }

    fun testConnection(onDone: ((Boolean) -> Unit)? = null) {
        TestResultReceiver.pendingCallback = { exitCode, stdout, stderr ->
            val ok = exitCode == 0 && stdout.contains("hello")
            onDone?.invoke(ok)
        }

        if (isTermuxRunning() && tryRunCommand(buildWakeIntent())) {
            doTestIntent()
            return
        }

        waitForTermuxForTest()
    }

    private fun waitForTermuxForTest() {
        handler.postDelayed({
            if (isTermuxRunning() && tryRunCommand(buildWakeIntent())) {
                doTestIntent()
            } else {
                waitForTermuxForTest()
            }
        }, 2000)
    }

    private fun tryRunCommand(intent: Intent): Boolean {
        return try {
            context.startService(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun buildWakeIntent(): Intent = Intent("com.termux.RUN_COMMAND").apply {
        setClassName("com.termux", "com.termux.app.RunCommandService")
        putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
        putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", ": # wake"))
        putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
    }

    private fun pendingIntentFlags(): Int {
        val base = PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base or PendingIntent.FLAG_MUTABLE
        } else {
            base or PendingIntent.FLAG_IMMUTABLE
        }
    }

    private fun doTestIntent() {
        handler.postDelayed({
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                (System.currentTimeMillis() + 9999).toInt(),
                Intent(context, TestResultReceiver::class.java),
                pendingIntentFlags()
            )
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "echo hello"))
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
            }
            tryRunCommand(intent)
        }, 2000)
    }
}
