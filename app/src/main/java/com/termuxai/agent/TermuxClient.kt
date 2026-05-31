package com.termuxai.agent

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ComponentName
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
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(scriptPath, userInput, apiKey))
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        }

        if (!tryRunCommand(intent)) {
            handler.postDelayed({
                if (!tryRunCommand(intent)) {
                    val errorIntent = Intent(MyResultReceiver.ACTION_RESULT).apply {
                        putExtra("stdout", "")
                        putExtra("stderr", "无法启动 Termux 服务。\n\n请在设置页点击「测试连接」，通过系统分享将命令发送到 Termux 以完成首次授权。之后即可正常使用。")
                        putExtra("exit_code", -1)
                    }
                    context.sendBroadcast(errorIntent)
                }
            }, 5000)
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
                activity.showTestResult("⏳ 正在测试连接...", false)
            }
        }

        if (tryRunCommand(buildWakeIntent())) {
            doTestIntent(activity)
            return
        }

        activity?.runOnUiThread {
            activity.showTestResult("⏳ 需要完成一次授权...", false)
            AlertDialog.Builder(activity)
                .setTitle("首次授权")
                .setMessage("ColorOS 限制了 App 自动打开 Termux。\n\n点击下方按钮 → 在系统分享中选择「Termux」→ 系统会弹出授权弹窗 → 选择「始终允许」。\n\n只需一次，后续全程自动。")
                .setPositiveButton("发送到 Termux") { _, _ ->
                    activity.showTestResult("⏳ 在分享中选择 Termux 并允许", false)
                    activity.pendingTest = true
                    context.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "echo hello")
                        },
                        "发送到 Termux"
                    ))
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
            if (!tryRunCommand(intent)) {
                TestResultReceiver.pendingCallback = null
                activity?.runOnUiThread {
                    activity.showTestResult("❌ 无法启动 Termux 服务", false)
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
