package com.termuxai.agent

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: Button
    private lateinit var settingsButton: Button

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    private lateinit var termuxClient: TermuxClient
    private var initialized = false
    private var pendingConfirmCommand: String? = null
    private var lastSentText: String? = null

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

        adapter.addMessage(ChatMessage(
            "你好！我是 Termux AI Agent。\n\n请先到设置中配置 DeepSeek API Key，然后发送消息即可开始。\n\n例如：\n- \"查看当前目录有哪些文件\"\n- \"在 Download 文件夹创建一个 test 文件夹\"\n- \"查看手机存储使用情况\"",
            isUser = false
        ))

        sendButton.setOnClickListener { sendMessage() }
        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        inputEditText.setOnClickListener { scrollToBottom() }

        initialized = true
        inputEditText.isEnabled = true
        sendButton.isEnabled = true

        AdbInputReceiver.onMessageReceived = { text -> runOnUiThread { doSend(text) } }
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AdbInputReceiver.pendingMessage?.let {
            AdbInputReceiver.pendingMessage = null
            doSend(it)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val text = when {
            intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
        if (!text.isNullOrBlank()) {
            AdbInputReceiver.pendingMessage = null
            doSend(text)
        }
    }

    private fun doSend(text: String) {
        if (text == lastSentText) return
        lastSentText = text
        inputEditText.setText(text)
        sendMessage()
    }

    private fun sendMessage() {
        val text = inputEditText.text.toString().trim()
        if (text.isEmpty() || !initialized) return

        inputEditText.text.clear()
        adapter.addMessage(ChatMessage(text, isUser = true))
        adapter.addMessage(ChatMessage("AI 正在思考...", isUser = false, isLoading = true))
        scrollToBottom()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val raw = termuxClient.execute(text)
                adapter.removeLoadingIndicator()
                val displayText = parseResponse(raw)
                if (pendingConfirmCommand != null) {
                    adapter.addMessage(ChatMessage(displayText, isUser = false))
                    showConfirmDialog(pendingConfirmCommand!!)
                    pendingConfirmCommand = null
                } else {
                    adapter.addMessage(ChatMessage(displayText, isUser = false))
                }
            } catch (e: Exception) {
                adapter.removeLoadingIndicator()
                adapter.addMessage(ChatMessage("❌ 错误：${e.message}", isUser = false))
                android.util.Log.e("TermuxAI", "sendMessage error", e)
            }
            scrollToBottom()
        }
    }

    private fun parseResponse(json: JSONObject): String {
        if (json.has("error")) {
            return "⚠️ ${json.optString("error", "")}\n\n${json.optString("message", "")}"
        }

        if (json.optBoolean("need_confirm", false)) {
            pendingConfirmCommand = json.optString("command", "")
        }

        val command = json.optString("command", "")
        val output = json.optString("output", "")
        val response = json.optString("response", "")
        val steps = json.optJSONArray("steps")

        if (response.isNotBlank() && steps == null && command.isBlank()) return response

        val sb = StringBuilder()

        if (response.isNotBlank()) {
            sb.append(response).append("\n\n")
        }

        if (command.isNotBlank()) {
            sb.append("$ ").appendLine(command)
            if (output.isNotBlank()) {
                val lines = output.split("\n")
                for (line in lines) {
                    val clean = line.replace("\t", "  ")
                    if (clean.isNotBlank()) {
                        sb.append("  ").appendLine(clean)
                    }
                }
            }
        }

        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val cmd = step.optString("cmd", "")
                val out = step.optString("output", "")
                if (cmd.isNotBlank()) {
                    sb.append("步骤 ${i+1}：")
                    sb.append("$ ").appendLine(cmd)
                    if (out.isNotBlank()) {
                        for (line in out.split("\n")) {
                            val clean = line.replace("\t", "  ")
                            if (clean.isNotBlank()) {
                                sb.append("  ").appendLine(clean)
                            }
                        }
                    }
                }
            }
        }

        return sb.toString().trimEnd()
    }

    private fun showConfirmDialog(command: String) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ 确认执行")
            .setMessage("此操作需要你的确认：\n\n```bash\n$command\n```")
            .setPositiveButton("执行") { _, _ ->
                adapter.addMessage(ChatMessage("✅ 已确认执行", isUser = true))
                adapter.addMessage(ChatMessage("AI 正在思考...", isUser = false, isLoading = true))
                scrollToBottom()
                CoroutineScope(Dispatchers.Main).launch {
                    val raw = termuxClient.execute("【已确认】$command")
                    adapter.removeLoadingIndicator()
                    val text = parseResponse(raw)
                    adapter.addMessage(ChatMessage(text, isUser = false))
                    scrollToBottom()
                }
            }
            .setNegativeButton("取消") { _, _ ->
                adapter.addMessage(ChatMessage("❌ 已取消", isUser = false))
                scrollToBottom()
            }
            .setCancelable(false)
            .show()
    }

    private fun scrollToBottom() {
        if (messages.isNotEmpty()) {
            recyclerView.smoothScrollToPosition(messages.size - 1)
        }
    }
}
