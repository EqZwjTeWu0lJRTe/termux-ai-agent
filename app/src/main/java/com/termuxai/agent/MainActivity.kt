package com.termuxai.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var settingsButton: Button

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var termuxClient: TermuxClient

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stdout = intent.getStringExtra("stdout") ?: ""
            val stderr = intent.getStringExtra("stderr") ?: ""

            adapter.removeLoadingIndicator()

            val raw = if (stderr.isNotBlank()) "$stdout\n$stderr" else stdout
            val displayText = parseResponse(raw)
            adapter.addMessage(ChatMessage(displayText, isUser = false))
            scrollToBottom()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recyclerView)
        inputEditText = findViewById(R.id.inputEditText)
        sendButton = findViewById(R.id.sendButton)
        settingsButton = findViewById(R.id.settingsButton)

        termuxClient = TermuxClient(this)

        adapter = ChatAdapter(messages)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val welcome = "你好！我是 Termux AI Agent。\n\n请告诉我你想在手机上做什么，我会生成并执行相应的终端命令。\n\n" +
            "例如：\n" +
            "- \"在 Download 文件夹里创建一个 test 文件夹\"\n" +
            "- \"查看当前目录有哪些文件\"\n" +
            "- \"安装一个 Python 包\"\n\n" +
            "> 注意：首次使用请先到设置中配置 API Key。"
        adapter.addMessage(ChatMessage(welcome, isUser = false))

        sendButton.setOnClickListener { sendMessage() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        inputEditText.setOnClickListener { scrollToBottom() }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MyResultReceiver.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(resultReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(resultReceiver)
    }

    private fun sendMessage() {
        val text = inputEditText.text.toString().trim()
        if (text.isEmpty()) return

        inputEditText.text.clear()
        adapter.addMessage(ChatMessage(text, isUser = true))
        adapter.addMessage(ChatMessage("AI 正在思考...", isUser = false, isLoading = true))
        scrollToBottom()

        termuxClient.execute(text)
    }

    private fun parseResponse(raw: String): String {
        return try {
            val json = JSONObject(raw)

            if (json.has("error")) {
                return "⚠️ 配置错误\n\n${json.optString("message", "")}"
            }

            val command = json.optString("command", "")
            val output = json.optString("output", "")
            val summary = json.optString("summary", "")

            buildString {
                if (command.isNotBlank()) {
                    appendLine("```bash")
                    appendLine(command)
                    appendLine("```")
                    appendLine()
                }
                if (output.isNotBlank()) {
                    appendLine("**执行结果：**")
                    appendLine(output)
                    appendLine()
                }
                if (summary.isNotBlank()) {
                    append(summary)
                }
            }
        } catch (e: Exception) {
            if (raw.isNotBlank()) raw else "命令执行完成（无输出）"
        }
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty()) {
            recyclerView.smoothScrollToPosition(messages.size - 1)
        }
    }
}
