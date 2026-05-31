package com.termuxai.agent

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

class TermuxClient(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("config", Context.MODE_PRIVATE)
    }

    private val scriptPath: String
        get() = prefs.getString(
            "script_path",
            "/data/data/com.termux/files/home/agent.py"
        ) ?: "/data/data/com.termux/files/home/agent.py"

    fun execute(userInput: String) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            Intent(context, MyResultReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/python")
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf(scriptPath, userInput)
            )
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        }

        try {
            context.startService(intent)
        } catch (e: Exception) {
            val errorIntent = Intent(MyResultReceiver.ACTION_RESULT).apply {
                putExtra("stdout", "")
                putExtra("stderr", "无法启动 Termux 服务：${e.message}\n\n请确保：\n1. Termux 已安装\n2. 已执行 echo \"allow-external-apps = true\" >> ~/.termux/termux.properties\n3. Termux 已重启")
                putExtra("exit_code", -1)
            }
            context.sendBroadcast(errorIntent)
        }
    }

    fun testConnection(callback: (Boolean, String) -> Unit) {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            (System.currentTimeMillis() + 9999).toInt(),
            Intent(context, TestResultReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val intent = Intent("com.termux.RUN_COMMAND").apply {
            setClassName("com.termux", "com.termux.app.RunCommandService")
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/echo")
            putExtra(
                "com.termux.RUN_COMMAND_ARGUMENTS",
                arrayOf("hello")
            )
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pendingIntent)
        }

        try {
            context.startService(intent)
            callback(true, "命令已发送，等待执行结果...")
        } catch (e: Exception) {
            callback(false, "无法启动 Termux 服务：${e.message}\n请确保 Termux 已安装并配置了 allow-external-apps")
        }
    }
}
