package com.termuxai.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
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
            statusText.text = "❌ 权限被拒绝，无法连接 Termux"
            statusText.setTextColor(0xFFF44336.toInt())
        }
    }

    private val testReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val stdout = intent.getStringExtra("stdout") ?: ""
            val stderr = intent.getStringExtra("stderr") ?: ""
            val exitCode = intent.getIntExtra("exit_code", -1)

            if (exitCode == 0 && stdout.contains("hello")) {
                statusText.text = "✅ Termux 连接测试成功！"
                statusText.setTextColor(0xFF4CAF50.toInt())
            } else {
                val errMsg = if (stderr.isNotBlank()) stderr else stdout
                statusText.text = "❌ 测试失败：$errMsg (exit code: $exitCode)"
                statusText.setTextColor(0xFFF44336.toInt())
            }
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
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TestResultReceiver.ACTION_TEST_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(testReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(testReceiver)
    }

    private fun loadSettings() {
        val apiKey = encryptedPrefs.getString("api_key", "") ?: ""
        val scriptPath = encryptedPrefs.getString("script_path",
            "~/agent.py") ?: "~/agent.py"

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
        statusText.text = "⏳ 正在测试连接..."
        statusText.setTextColor(0xFFFF9800.toInt())

        val termuxClient = TermuxClient(this)
        termuxClient.testConnection { success, message ->
            if (!success) {
                runOnUiThread {
                    statusText.text = "❌ $message"
                    statusText.setTextColor(0xFFF44336.toInt())
                }
            }
        }
    }
}
