package com.termuxai.agent

import android.content.Context
import android.content.SharedPreferences
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
        private const val API_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val MODEL = "deepseek-chat"
        private const val STATE_PREFS = "agent_state"
        private const val MAX_HISTORY = 10

        private const val SYSTEM_PROMPT = """你是 Android 终端中的 AI 助手，具备状态记忆和多步规划能力。

输出 JSON，支持三种模式：

模式一（闲聊）：{"response": "你的自然语言回答"}

模式二（单步命令）：{"command": "shell 命令", "need_confirm": false}

模式三（多步任务，需要多个命令才能完成时）：{"steps": [
  {"cmd": "第一步的命令", "desc": "步骤说明"},
  {"cmd": "第二步的命令", "desc": "步骤说明"}
]}

注意事项：
- 可以执行任何 Linux/Android shell 命令
- 当前工作目录可能不同，cd 需要单独作为一步
- need_confirm: true 表示需要用户确认后再执行（如删除、格式化等危险操作）
- 如果用户确认命令，输入会以【已确认】开头，此时直接执行命令，不要调用 AI
- 如果需要重置对话上下文，输出 {"clear_context": true}"""
    }

    private val statePrefs: SharedPreferences = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class AgentResult(
        val json: JSONObject,
        val raw: String
    )

    suspend fun execute(userInput: String, apiKey: String): AgentResult = withContext(Dispatchers.IO) {
        val confirmedPrefix = "【已确认】"
        if (userInput.startsWith(confirmedPrefix)) {
            val cmd = userInput.removePrefix(confirmedPrefix).trim()
            val execResult = execCommand(cmd)
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
        val historyJson = statePrefs.getString("history", "[]") ?: "[]"
        val turn = statePrefs.getInt("turn", 0)

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            val history = JSONArray(historyJson)
            for (i in 0 until history.length()) {
                put(history.getJSONObject(i))
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

        val request = okhttp3.Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val body = response.body?.string() ?: "{}"
        val aiResp = JSONObject(body)
        val choice = aiResp.optJSONArray("choices")?.optJSONObject(0)
        val content = choice?.optString("message", "")?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        } ?: JSONObject().apply { put("response", choice?.optJSONObject("message")?.optString("content", body) ?: body) }

        if (content.optBoolean("clear_context", false)) {
            statePrefs.edit().remove("history").putInt("turn", 0).apply()
        }

        val responseText = content.optString("response", "")
        val command = content.optString("command", "")
        val stepsJson = content.optJSONArray("steps")

        if (command.isNotEmpty()) {
            val needConfirm = content.optBoolean("need_confirm", false)
            if (needConfirm) {
                saveToHistory(userInput, "", -1, content.toString())
                return@withContext AgentResult(content, content.toString())
            }
            val (out, code) = execCommand(command, cwd)
            val summary = callSummary(command, out, code, apiKey)
            saveToHistory(userInput, command, code, content.toString())
            val resultJson = JSONObject().apply {
                put("command", command)
                put("output", out)
                put("summary", summary)
                put("exit_code", code)
                put("need_confirm", false)
            }
            updateCwd(command)
            return@withContext AgentResult(resultJson, content.toString())
        }

        if (stepsJson != null) {
            val stepResults = JSONArray()
            var currentCwd = cwd
            for (i in 0 until stepsJson.length()) {
                val step = stepsJson.getJSONObject(i)
                val cmd = step.optString("cmd", "")
                if (cmd.isNotEmpty()) {
                    val (out, code) = execCommand(cmd, currentCwd)
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
                put("summary", content.optString("summary", "任务执行完成"))
            }
            return@withContext AgentResult(resultJson, content.toString())
        }

        if (responseText.isNotEmpty()) {
            saveToHistory(userInput, "", 0, content.toString())
            return@withContext AgentResult(content, content.toString())
        }

        AgentResult(JSONObject().apply { put("response", "已收到") }, content.toString())
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

    private fun callSummary(cmd: String, output: String, code: Int, apiKey: String): String {
        return try {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "用简洁的中文总结命令执行结果。")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "命令：${cmd}\n结果（exit code: ${code}）：\n${output}\n\n请总结")
                })
            }
            val payload = JSONObject().apply {
                put("model", MODEL)
                put("messages", messages)
                put("temperature", 0.1)
                put("max_tokens", 1024)
            }
            val request = okhttp3.Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            val respBody = JSONObject(response.body?.string() ?: "{}")
            respBody.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content", "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun saveToHistory(input: String, command: String, exitCode: Int, raw: String) {
        val turn = statePrefs.getInt("turn", 0) + 1
        val historyJson = statePrefs.getString("history", "[]") ?: "[]"
        val history = JSONArray(historyJson)
        history.put(JSONObject().apply {
            put("role", "user")
            put("content", input)
        })
        if (command.isNotEmpty()) {
            history.put(JSONObject().apply {
                put("role", "assistant")
                put("content", "command: $command (exit: $exitCode)")
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
