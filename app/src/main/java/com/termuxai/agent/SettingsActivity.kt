package com.termuxai.agent

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsActivity : AppCompatActivity() {

    private lateinit var apiKeyEditText: EditText
    private lateinit var scriptPathEditText: EditText
    private lateinit var saveButton: Button
    private lateinit var testButton: Button
    private lateinit var statusText: TextView

    private lateinit var encryptedPrefs: SharedPreferences

    private val testPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            doTestConnection()
        } else {
            showTestResult("❌ 权限被拒绝，无法连接 Termux", false)
        }
    }

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

        AgentUpdater(this).pushIfNeeded()
    }

    private fun loadSettings() {
        val apiKey = encryptedPrefs.getString("api_key", "") ?: ""
        val scriptPath = encryptedPrefs.getString("script_path",
            "/data/data/com.termux/files/home/agent.py") ?: "/data/data/com.termux/files/home/agent.py"

        apiKeyEditText.setText(apiKey)
        scriptPathEditText.setText(scriptPath)
    }

    private fun saveSettings() {
        val apiKey = apiKeyEditText.text.toString().trim()
        val scriptPath = scriptPathEditText.text.toString().trim()

        encryptedPrefs.edit()
            .putString("api_key", apiKey)
            .putString("script_path", scriptPath)
            .apply()

        val regularPrefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        regularPrefs.edit()
            .putString("api_key", apiKey)
            .putString("script_path", scriptPath)
            .apply()

        statusText.text = "✅ 设置已保存"
        statusText.setTextColor(0xFF4CAF50.toInt())
    }

    private fun testConnection() {
        if (ContextCompat.checkSelfPermission(this, TermuxClient.PERMISSION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            testPermissionLauncher.launch(TermuxClient.PERMISSION)
            return
        }
        doTestConnection()
    }

    private fun doTestConnection() {
        val client = TermuxClient(this)
        if (client.isTermuxRunning()) {
            statusText.text = "⏳ 正在测试连接..."
            statusText.setTextColor(0xFFFF9800.toInt())
            client.testConnection { ok ->
                runOnUiThread {
                    statusText.text = if (ok) "✅ Termux 连接测试成功！"
                    else "❌ 测试失败"
                    statusText.setTextColor(if (ok) Color.rgb(76, 175, 80) else Color.rgb(244, 67, 54))
                }
            }
        } else {
            statusText.text = "⚠️ 请先打开 Termux，检测到后将自动测试"
            statusText.setTextColor(0xFFFF9800.toInt())
            client.testConnection { ok ->
                runOnUiThread {
                    statusText.text = if (ok) "✅ Termux 连接测试成功！"
                    else "❌ 测试失败"
                    statusText.setTextColor(if (ok) Color.rgb(76, 175, 80) else Color.rgb(244, 67, 54))
                }
            }
        }
    }

    private fun showTestResult(msg: String, success: Boolean) {
        statusText.text = msg
        statusText.setTextColor(if (success) Color.rgb(76, 175, 80) else Color.rgb(244, 67, 54))
    }
}
