package com.sgstudio.OfoxMessenger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.sgstudio.OfoxMessenger.databinding.ActivityErrorHandlerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class ErrorHandlerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityErrorHandlerBinding
    private var errorLog: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityErrorHandlerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Получаем информацию об ошибке из Intent
        val exception = intent.getSerializableExtra("exception") as Throwable?
        val threadName = intent.getStringExtra("thread_name") ?: "Unknown thread"
        errorLog = intent.getStringExtra("error_log") ?: "No error log available"

        // Отображаем информацию об ошибке
        binding.errorMessageTextView.text = exception?.message ?: "Неизвестная ошибка"
        binding.errorDetailsTextView.text = errorLog

        // Настраиваем кнопки
        setupButtons()

        // Отправляем отчет об ошибке в Telegram
        sendErrorReportToTelegram(exception, threadName)
    }

    private fun setupButtons() {
        // Кнопка закрытия
        binding.closeButton.setOnClickListener {
            finishAffinity() // Закрываем все активности приложения
        }

        // Кнопка перезапуска
        binding.restartButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finishAffinity()
        }

        // Кнопка копирования лога
        binding.copyLogButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Error Log", errorLog)
            clipboard.setPrimaryClip(clip)
            
            Snackbar.make(binding.root, "Лог скопирован в буфер обмена", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun sendErrorReportToTelegram(exception: Throwable?, threadName: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Собираем информацию об устройстве
                val deviceInfo = getDeviceInfo()

                // Первое сообщение с информацией об устройстве и ошибке (обычный текст)
                val infoMessage = """
                🚨 Ошибка в приложении OfoxMessenger 🚨
                
                📱 Информация об устройстве:
                $deviceInfo
                
                ⚠️ Ошибка:
                ${exception?.message ?: "Неизвестная ошибка"}
                
                🧵 Поток: $threadName
            """.trimIndent()

                // Отправляем первое сообщение без форматирования
                sendPlainTextMessage(infoMessage)

                // Второе сообщение только со стек-трейсом в формате кода
                val stackTrace = exception?.stackTraceToString()?.take(3000) ?: "Нет данных"

                // Отправляем второе сообщение с форматированием Markdown
                sendCodeBlockMessage(stackTrace)

                withContext(Dispatchers.Main) {
                    Snackbar.make(
                        binding.root,
                        "Отчет об ошибке отправлен разработчикам",
                        Snackbar.LENGTH_LONG
                    ).show()
                }

            } catch (e: Exception) {
                Log.e("ErrorHandler", "Error sending report", e)
            }
        }
    }

    private fun sendPlainTextMessage(message: String) {
        try {
            val botToken = "7966724924:AAGfDjTh8-OssTWAMo6tDcXa22OY3zpNXVk"
            val chatId = "2129429604"
            val apiUrl = "https://api.telegram.org/bot$botToken/sendMessage"

            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            val data = JSONObject().apply {
                put("chat_id", chatId)
                put("text", message)
                // Без parse_mode - обычный текст
            }

            connection.outputStream.use { os ->
                os.write(data.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("ErrorHandler", "Failed to send plain text message: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("ErrorHandler", "Error in sendPlainTextMessage", e)
        }
    }

    private fun sendCodeBlockMessage(stackTrace: String) {
        try {
            val botToken = "7966724924:AAGfDjTh8-OssTWAMo6tDcXa22OY3zpNXVk"
            val chatId = "2129429604"
            val apiUrl = "https://api.telegram.org/bot$botToken/sendMessage"

            // Форматируем сообщение как блок кода
            val formattedMessage = "```\n$stackTrace\n```"

            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            val data = JSONObject().apply {
                put("chat_id", chatId)
                put("text", formattedMessage)
                put("parse_mode", "Markdown") // Используем обычный Markdown, не MarkdownV2
            }

            connection.outputStream.use { os ->
                os.write(data.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("ErrorHandler", "Failed to send code block message: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("ErrorHandler", "Error in sendCodeBlockMessage", e)
        }
    }

    private suspend fun sendTelegramMessage(message: String) {
        try {
            val botToken = "7966724924:AAGfDjTh8-OssTWAMo6tDcXa22OY3zpNXVk"
            val chatId = "2129429604"
            val apiUrl = "https://api.telegram.org/bot$botToken/sendMessage"

            val url = URL(apiUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")

            val data = JSONObject().apply {
                put("chat_id", chatId)
                put("text", message)
                put("parse_mode", "MarkdownV2")
            }

            connection.outputStream.use { os ->
                os.write(data.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("ErrorHandler", "Failed to send error report: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("ErrorHandler", "Error in sendTelegramMessage", e)
        }
    }

    private fun getDeviceInfo(): String {
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionName = packageInfo.versionName
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
        
        return """
            Модель: ${Build.MANUFACTURER} ${Build.MODEL}
            Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
            Версия приложения: $versionName ($versionCode)
        """.trimIndent()
    }
}
