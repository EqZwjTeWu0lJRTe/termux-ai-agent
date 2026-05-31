package com.termuxai.agent

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

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

    private fun wakeTermux(): Boolean {
        try {
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", ": # wake"))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            }
            context.startService(intent)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun launchTermux() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName("com.termux", "com.termux.app.TermuxActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
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
            }, 3000)
        }
    }

    fun testConnection() {
        val activity = context as? SettingsActivity
        if (activity != null) {
            TestResultReceiver.pendingCallback = { exitCode, stdout, stderr ->
                activity.pendingTest = false
                val success = exitCode == 0 && stdout.contains("hello")
                val msg = if (success) "✅ Termux 连接测试成功！"
                else "❌ 测试失败：${if (stderr.isNotBlank()) stderr else stdout} (exit code: $exitCode)"
                activity.runOnUiThread { activity.showTestResult(msg, success) }
            }

            activity.runOnUiThread {
                activity.showTestResult("⏳ 正在唤醒 Termux...", false)
            }
        }

        if (wakeTermux()) {
            doTestIntent(activity)
        } else {
            activity?.runOnUiThread {
                activity.showTestResult("ℹ️ 需要允许关联启动 Termux", false)
                AlertDialog.Builder(activity)
                    .setTitle("需要系统权限")
                    .setMessage("ColorOS 拦截了 App 自动打开 Termux。\n\n请在系统设置中允许「Termux AI Agent」关联启动其他应用：\n\n设置 → 应用 → Termux AI Agent → 关联启动 → 打开「允许关联启动」\n\n完成后返回此页面，将自动重试。")
                    .setPositiveButton("去设置") { _, _ ->
                        activity.showTestResult("⏳ 请开启关联启动后返回", false)
                        activity.pendingTest = true
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try { context.startActivity(intent) } catch (_: Exception) {}
                        activity.testTimeoutHandler.postDelayed({
                            if (TestResultReceiver.pendingCallback != null) {
                                TestResultReceiver.pendingCallback = null
                                activity.pendingTest = false
                                activity.showTestResult("❌ 连接超时：Termux 未响应", false)
                            }
                        }, 60000)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun doTestIntent(activity: SettingsActivity?) {
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
        }, 2000)

        activity?.runOnUiThread {
            activity.testTimeoutHandler.postDelayed({
                if (TestResultReceiver.pendingCallback != null) {
                    TestResultReceiver.pendingCallback = null
                    activity.pendingTest = false
                    activity.showTestResult("❌ 连接超时：Termux 未响应", false)
                }
            }, 12000)
        }
    }
}
