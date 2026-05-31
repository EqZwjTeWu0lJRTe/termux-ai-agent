package com.termuxai.agent

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class AiAgent(context: Context) {

    companion object {
        private const val TAG = "TermuxAI"
        private const val API_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val MODEL = "deepseek-chat"
        private const val STATE_PREFS = "agent_state"
        private const val MAX_HISTORY = 10

        private const val SYSTEM_PROMPT = """你是 Android 终端中的 AI 助手。理解用户意图并执行命令。

## 严格规则：只输出 JSON，不要有任何多余文字

### 执行命令
{"command": "shell 命令", "need_confirm": false, "response": "用中文描述你要做什么"}
- response 可选，用于告诉用户你在执行什么操作

### 多步骤任务
{"steps": [{"cmd": "...", "desc": "..."}], "response": "说明整体任务"}

### 纯聊天/回答问题
{"response": "回答"}

### 重置上下文
{"clear_context": true}

## Android 文件系统
- 用户数据在 /sdcard 或 /storage/emulated/0
- 常见目录：DCIM（照片）、Download（下载）、Documents（文档）

## 找不到东西时
1. 用 find /sdcard -name "关键词" 2>/dev/null 搜索
2. 如实告知用户没找到，通过 response 说明搜索了什么

## 规则
- 优先执行命令
- 只输出 JSON，不加任何额外文字"""
    }

    private val statePrefs: SharedPreferences = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class AgentResult(
        val json: JSONObject,
        val raw: String
    )

    suspend fun execute(userInput: String, apiKey: String): AgentResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== execute() ===")
        Log.d(TAG, "userInput: $userInput")

        try {

        if (userInput.trim().equals("/reset", ignoreCase = true) || userInput.trim().equals("重置", ignoreCase = true)) {
            Log.d(TAG, "用户请求重置上下文")
            statePrefs.edit().remove("history").putInt("turn", 0).putString("cwd", "/storage/emulated/0").apply()
            return@withContext AgentResult(
                JSONObject().apply { put("response", "✅ 上下文已重置，历史记录已清除") },
                ""
            )
        }

        val confirmedPrefix = "【已确认】"
        if (userInput.startsWith(confirmedPrefix)) {
            val cmd = userInput.removePrefix(confirmedPrefix).trim()
            Log.d(TAG, "已确认命令，直接执行: $cmd")
            val execResult = execCommand(cmd)
            Log.d(TAG, "执行结果 (exit=${execResult.second}): ${execResult.first.take(200)}")
            return@withContext AgentResult(
                JSONObject().apply {
                    put("command", cmd)
                    put("output", execResult.first)
                    put("exit_code", execResult.second)
                    put("need_confirm", false)
                },
                ""
            )
        }

        val cwd = statePrefs.getString("cwd", "/storage/emulated/0") ?: "/storage/emulated/0"
        var historyJson = statePrefs.getString("history", "[]") ?: "[]"
        val turn = statePrefs.getInt("turn", 0)

        val history = JSONArray(historyJson)
        if (history.length() > 0) {
            var corrupted = false
            var i = 0
            while (i < history.length()) {
                val msg = history.getJSONObject(i)
                if (msg.optString("role", "") == "assistant") {
                    val content = msg.optString("content", "")
                    if (content.isNotBlank() && !content.trim().startsWith("{")) {
                        corrupted = true
                        break
                    }
                }
                i++
            }
            if (corrupted) {
                Log.w(TAG, "检测到历史格式损坏，自动清除")
                statePrefs.edit().remove("history").putInt("turn", 0).apply()
                historyJson = "[]"
            }
        }
        val cleanHistory = JSONArray(historyJson)
        Log.d(TAG, "cwd=$cwd, turn=$turn, history_size=${cleanHistory.length()}")

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            for (i in 0 until cleanHistory.length()) {
                put(cleanHistory.getJSONObject(i))
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userInput)
            })
        }

        val payload = JSONObject().apply {
            put("model", MODEL)
            put("messages", messages)
            put("temperature", 0.1)
            put("max_tokens", 4096)
        }

        Log.d(TAG, "--- DeepSeek 请求 ---")
        Log.d(TAG, "payload: ${payload.toString().take(500)}...")

        val request = okhttp3.Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: "{}"
        val aiResp = JSONObject(body)
        Log.d(TAG, "--- DeepSeek 响应 ---")
        Log.d(TAG, "raw: ${body.take(1000)}")

        val choice = aiResp.optJSONArray("choices")?.optJSONObject(0)
        val rawContent = choice?.optJSONObject("message")?.optString("content", "") ?: ""
        Log.d(TAG, "message.content: $rawContent")
        val content = parseAiResponse(rawContent)
        Log.d(TAG, "解析后的 JSON: ${content.toString()}")

        if (content.optBoolean("clear_context", false)) {
            Log.d(TAG, "clear_context=true，重置对话")
            statePrefs.edit().remove("history").putInt("turn", 0).apply()
        }

        val responseText = content.optString("response", "")
        val command = content.optString("command", "")
        val stepsJson = content.optJSONArray("steps")

        if (command.isNotEmpty()) {
            val needConfirm = content.optBoolean("need_confirm", false)
            Log.d(TAG, "命令模式: command=$command, needConfirm=$needConfirm")
            if (needConfirm) {
                Log.d(TAG, "需要用户确认，暂停执行")
                saveToHistory(userInput, "", -1, content.toString())
                return@withContext AgentResult(content, content.toString())
            }
            val (out, code) = execCommand(command, cwd)
            Log.d(TAG, "命令执行: exit=$code, output=${out.take(300)}")
            saveToHistory(userInput, command, code, content.toString())
            val resultJson = JSONObject().apply {
                put("command", command)
                put("output", out)
                put("exit_code", code)
                put("need_confirm", false)
                content.optString("response", "").takeIf { it.isNotBlank() }?.let { put("response", it) }
            }
            updateCwd(command)
            return@withContext AgentResult(resultJson, content.toString())
        }

        if (stepsJson != null) {
            Log.d(TAG, "多步模式: steps=${stepsJson.length()}步")
            val stepResults = JSONArray()
            var currentCwd = cwd
            for (i in 0 until stepsJson.length()) {
                val step = stepsJson.getJSONObject(i)
                val cmd = step.optString("cmd", "")
                Log.d(TAG, "步骤${i+1}: $cmd")
                if (cmd.isNotEmpty()) {
                    val (out, code) = execCommand(cmd, currentCwd)
                    Log.d(TAG, "步骤${i+1} 结果: exit=$code, output=${out.take(200)}")
                    val stepResult = JSONObject().apply {
                        put("cmd", cmd)
                        put("output", out)
                        put("exit_code", code)
                    }
                    stepResults.put(stepResult)
                    updateCwd(cmd)
                    currentCwd = statePrefs.getString("cwd", currentCwd) ?: currentCwd
                }
            }
            saveToHistory(userInput, "", if (stepResults.length() > 0) 0 else -1, content.toString())
            val resultJson = JSONObject().apply {
                put("steps", stepResults)
            }
            return@withContext AgentResult(resultJson, content.toString())
        }

        if (responseText.isNotEmpty()) {
            Log.d(TAG, "闲聊模式: response=$responseText")
            saveToHistory(userInput, "", 0, content.toString())
            return@withContext AgentResult(content, content.toString())
        }

        Log.w(TAG, "未识别的 AI 响应，返回默认消息")
        AgentResult(JSONObject().apply { put("response", "已收到") }, content.toString())

        } catch (e: Exception) {
            Log.e(TAG, "execute 异常", e)
            AgentResult(
                JSONObject().apply {
                    put("error", "执行异常")
                    put("message", e.message ?: e.toString())
                },
                ""
            )
        }
    }

    private fun execCommand(cmd: String, cwd: String? = null): Pair<String, Int> {
        return try {
            val runtimeCmd = if (cwd != null) {
                arrayOf("/system/bin/sh", "-c", "cd '$cwd' 2>/dev/null; $cmd")
            } else {
                arrayOf("/system/bin/sh", "-c", cmd)
            }
            val process = Runtime.getRuntime().exec(runtimeCmd)
            val stdout = readStream(process.inputStream)
            val stderr = readStream(process.errorStream)
            val exitCode = process.waitFor()
            val output = if (stdout.isNotBlank()) stdout else stderr
            (if (output.isNotBlank()) output else "（命令执行完毕，无输出）") to exitCode
        } catch (e: Exception) {
            "执行失败：${e.message}" to -1
        }
    }

    private fun readStream(stream: java.io.InputStream): String {
        return BufferedReader(InputStreamReader(stream)).readText().trim()
    }

    private fun parseAiResponse(raw: String): JSONObject {
        try {
            return JSONObject(raw)
        } catch (_: Exception) {}

        val jsonStart = raw.indexOf("{")
        val jsonEnd = raw.lastIndexOf("}")
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            val extracted = raw.substring(jsonStart, jsonEnd + 1)
            try {
                val parsed = JSONObject(extracted)
                Log.d(TAG, "从文本中提取到 JSON")
                return parsed
            } catch (_: Exception) {}
        }

        Log.w(TAG, "AI 返回非 JSON 内容，包装为 response")
        return JSONObject().apply { put("response", raw.trim()) }
    }

    private fun saveToHistory(input: String, command: String, exitCode: Int, raw: String) {
        val turn = statePrefs.getInt("turn", 0) + 1
        val historyJson = statePrefs.getString("history", "[]") ?: "[]"
        val history = JSONArray(historyJson)
        history.put(JSONObject().apply {
            put("role", "user")
            put("content", input)
        })
        if (raw.isNotBlank()) {
            history.put(JSONObject().apply {
                put("role", "assistant")
                put("content", raw)
            })
        }
        while (history.length() > MAX_HISTORY * 2) {
            history.remove(0)
        }
        statePrefs.edit()
            .putString("history", history.toString())
            .putInt("turn", turn)
            .apply()
    }

    private fun updateCwd(cmd: String) {
        val separators = listOf(" && ", " ; ", " | ", " || ")
        val firstCmd = separators.fold(cmd) { acc, sep ->
            if (acc.contains(sep)) acc.substringBefore(sep) else acc
        }.trim()
        if (firstCmd.startsWith("cd ")) {
            val dir = firstCmd.removePrefix("cd ").trim().removeSurrounding("'").removeSurrounding("\"")
            val expanded = if (dir.startsWith("~")) {
                val home = System.getenv("HOME") ?: "/data/data/com.termux/files/home"
                dir.replaceFirst("~", home)
            } else dir
            statePrefs.edit().putString("cwd", expanded).apply()
        }
    }
}
