package com.termuxai.agent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TermuxClient(private val context: Context) {

    companion object {
        const val PREFS_NAME = "config"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val apiKey: String
        get() = prefs.getString("api_key", "") ?: ""

    private val agent by lazy { AiAgent(context) }

    suspend fun isReady(): Boolean = true

    suspend fun initialize(onProgress: ((String) -> Unit)? = null): Boolean {
        onProgress?.invoke("就绪")
        return true
    }

    suspend fun execute(userInput: String): org.json.JSONObject {
        val result = agent.execute(userInput, apiKey)
        return result.json
    }

    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", "echo hello"))
            val stdout = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream)).readText().trim()
            process.waitFor()
            stdout.contains("hello")
        } catch (_: Exception) {
            false
        }
    }
}
