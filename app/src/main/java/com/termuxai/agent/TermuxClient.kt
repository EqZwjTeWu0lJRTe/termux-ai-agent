package com.termuxai.agent

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

    private fun pendingIntentFlags(): Int {
        val base = PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            base or PendingIntent.FLAG_MUTABLE
        } else {
            base or PendingIntent.FLAG_IMMUTABLE
        }
    }

    private fun wakeTermux() {
        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", ": # wake"))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
        }
        try {
            context.startService(intent)
        } catch (_: Exception) {
            context.startActivity(
                context.packageManager.getLaunchIntentForPackage("com.termux")
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun execute(userInput: String) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            Intent(context, MyResultReceiver::class.java),
            pendingIntentFlags()
        )

        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python")
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf(scriptPath, userInput, apiKey)
            )
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        }

        try {
            context.startService(intent)
        } catch (e: Exception) {
            wakeTermux()
            handler.postDelayed({
                try {
                    context.startService(intent)
                } catch (_: Exception) {
                    val errorIntent = Intent(MyResultReceiver.ACTION_RESULT).apply {
                        putExtra("stdout", "")
                        putExtra("stderr", "无法启动 Termux 服务：${e.message}\n\n请确保：\n1. Termux 已安装\n2. 已执行 echo \"allow-external-apps = true\" >> ~/.termux/termux.properties\n3. Termux 已重启")
                        putExtra("exit_code", -1)
                    }
                    context.sendBroadcast(errorIntent)
                }
            }, 1500)
        }
    }

    fun testConnection() {
        val activity = context as? SettingsActivity
        if (activity != null) {
            TestResultReceiver.pendingCallback = { exitCode, stdout, stderr ->
                val success = exitCode == 0 && stdout.contains("hello")
                val msg = if (success) "✅ Termux 连接测试成功！"
                else "❌ 测试失败：${if (stderr.isNotBlank()) stderr else stdout} (exit code: $exitCode)"
                activity.runOnUiThread { activity.showTestResult(msg, success) }
            }
        }

        wakeTermux()

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

            try {
                context.startService(intent)
            } catch (e: Exception) {
                TestResultReceiver.pendingCallback = null
                activity?.runOnUiThread {
                    activity.showTestResult("❌ 无法启动 Termux 服务：${e.message}", false)
                }
            }
        }, 1500)

        activity?.runOnUiThread {
            activity.showTestResult("⏳ 正在唤醒 Termux...", false)
            activity.testTimeoutHandler.postDelayed({
                if (TestResultReceiver.pendingCallback != null) {
                    TestResultReceiver.pendingCallback = null
                    activity.showTestResult("❌ 连接超时：Termux 未响应（10s）", false)
                }
            }, 11500)
        }
    }
}
