package com.termuxai.agent

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var apiKeyEditText: EditText
    private lateinit var scriptPathEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var testButton: Button
    private lateinit var statusText: TextView

    private lateinit var encryptedPrefs: SharedPreferences
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        apiKeyEditText = findViewById(R.id.apiKeyEditText)
        scriptPathEditText = findViewById(R.id.scriptPathEditText)
        saveButton = findViewById(R.id.saveButton)
        testButton = findViewById(R.id.testButton)
        statusText = findViewById(R.id.statusText)

        val masterKey = MasterKey.Builder(this)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        encryptedPrefs = EncryptedSharedPreferences.create(
            this,
            "secure_config",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        loadSettings()
        saveButton.setOnClickListener { saveSettings() }
        testButton.setOnClickListener { testConnection() }
    }

    private fun loadSettings() {
        val apiKey = encryptedPrefs.getString("api_key", "") ?: ""
        apiKeyEditText.setText(apiKey)
        scriptPathEditText.setText("/data/data/com.termux/files/home/agent.py")
    }

    private fun saveSettings() {
        val apiKey = apiKeyEditText.text.toString().trim()

        encryptedPrefs.edit()
            .putString("api_key", apiKey)
            .apply()

        getSharedPreferences("config", Context.MODE_PRIVATE).edit()
            .putString("api_key", apiKey)
            .apply()

        statusText.text = "✅ 设置已保存"
        statusText.setTextColor(0xFF4CAF50.toInt())
    }

    private fun testConnection() {
        statusText.text = "⏳ 正在测试..."
        statusText.setTextColor(0xFFFF9800.toInt())

        scope.launch {
            val ok = TermuxClient(this@SettingsActivity).testConnection()
            statusText.text = if (ok) "✅ 终端执行正常！"
            else "❌ 测试失败"
            statusText.setTextColor(if (ok) Color.rgb(76, 175, 80) else Color.rgb(244, 67, 54))
        }
    }
}
