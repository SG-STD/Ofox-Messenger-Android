package com.sgstudio.OfoxMessenger

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.sgstudio.OfoxMessenger.databinding.ActivityMainBinding
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var auth: FirebaseAuth
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val serverUrl = "https://greechat.kz/serv"
    private var secretKey: String? = null // Ключ будет загружен из Firebase
    private var isNetworkErrorShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Загрузка сохраненных настроек
        loadLocale()
        loadTheme()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация Firebase (автоматически через google-services.json)
        auth = FirebaseAuth.getInstance()

        // Загрузка ключа шифрования из Firebase
        loadEncryptionKey()

        // Настройка анимаций
        setupAnimations()

        // Настройка обработчиков событий
        setupClickListeners()
    }

    private fun loadEncryptionKey() {
        val database = FirebaseDatabase.getInstance()
        val keyRef = database.getReference("config/encryption_key")

        keyRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                secretKey = snapshot.getValue(String::class.java)
                if (secretKey == null) {
                    showNetworkError(getString(R.string.network_error))
                } else {
                    hideNetworkError()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                showNetworkError(getString(R.string.network_error))
            }
        })
    }

    private fun setupAnimations() {
        // Анимация для логотипа
        val fadeIn = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        binding.logoImageView.startAnimation(fadeIn)

        // Анимация для кнопок
        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)
        binding.loginButton.startAnimation(slideUp)
    }

    private fun setupClickListeners() {
        // Обработчик кнопки входа
        binding.loginButton.setOnClickListener {
            val email = binding.emailEditText.text.toString().trim()
            val password = binding.passwordEditText.text.toString().trim()

            if (validateInput(email, password)) {
                if (secretKey != null) {
                    showProgress(true)
                    loginUser(email, password)
                } else {
                    showNetworkError(getString(R.string.network_error))
                }
            }
        }

        // Обработчик переключения темы
        binding.themeToggleButton.setOnClickListener {
            toggleTheme()
        }

        // Обработчик смены языка
        binding.languageButton.setOnClickListener {
            showLanguageDialog()
        }

        // Обработчик перехода на регистрацию
        binding.registerTextView.setOnClickListener {
            // TODO: Переход на экран регистрации
            Snackbar.make(binding.main, getString(R.string.no_account_register), Snackbar.LENGTH_LONG).show()
        }

        // Обработчик кнопки повтора при ошибке сети
        binding.retryButton.setOnClickListener {
            hideNetworkError()
            loadEncryptionKey()
        }
    }

    private fun validateInput(email: String, password: String): Boolean {
        var isValid = true

        if (email.isEmpty()) {
            binding.emailInputLayout.error = "Введите email"
            isValid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailInputLayout.error = "Введите корректный email"
            isValid = false
        } else {
            binding.emailInputLayout.error = null
        }

        if (password.isEmpty()) {
            binding.passwordInputLayout.error = "Введите пароль"
            isValid = false
        } else if (password.length < 6) {
            binding.passwordInputLayout.error = "Пароль должен содержать минимум 6 символов"
            isValid = false
        } else {
            binding.passwordInputLayout.error = null
        }

        return isValid
    }

    private fun loginUser(email: String, password: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Шифруем данные для отправки
                val jsonData = JSONObject().apply {
                    put("email", encrypt(email))
                    put("password", encrypt(password))
                    put("action", encrypt("login"))
                }

                val mediaType = "application/json; charset=utf-8".toMediaType()
                val requestBody = jsonData.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(serverUrl)
                    .post(requestBody)
                    .build()

                try {
                    val response = client.newCall(request).execute()
                    val responseBody = response.body?.string() ?: ""

                    withContext(Dispatchers.Main) {
                        showProgress(false)
                        hideNetworkError()

                        if (response.isSuccessful) {
                            try {
                                val jsonResponse = JSONObject(responseBody)
                                val success = jsonResponse.optBoolean("success", false)

                                if (success) {
                                    // Вход через Firebase для получения токена
                                    firebaseLogin(email, password)
                                } else {
                                    val message = jsonResponse.optString("message", "Ошибка входа")
                                    showSnackbar(message)
                                }
                            } catch (e: Exception) {
                                showSnackbar(getString(R.string.login_failed))
                            }
                        } else {
                            showSnackbar(getString(R.string.network_error))
                        }
                    }
                } catch (e: UnknownHostException) {
                    withContext(Dispatchers.Main) {
                        showProgress(false)
                        showNetworkError(getString(R.string.network_error))
                    }
                } catch (e: IOException) {
                    withContext(Dispatchers.Main) {
                        showProgress(false)
                        showNetworkError(getString(R.string.network_error))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showProgress(false)
                    showSnackbar(getString(R.string.network_error))
                }
            }
        }
    }

    private fun firebaseLogin(email: String, password: String) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    // Успешный вход
                    val user = auth.currentUser
                    updateUserLastLogin(user?.uid)
                    showSnackbar(getString(R.string.login_success))

                    // TODO: Переход на главный экран
                } else {
                    // Ошибка входа
                    showSnackbar(getString(R.string.login_failed))
                }
            }
    }

    private fun updateUserLastLogin(userId: String?) {
        if (userId != null) {
            val database = FirebaseDatabase.getInstance()
            val userRef = database.getReference("users/$userId")

            val currentTime = System.currentTimeMillis().toString()
            userRef.child("last_login").setValue(currentTime)
            userRef.child("last_seen").setValue(currentTime.toLong())
        }
    }

    private fun showProgress(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.loginButton.isEnabled = !show
        binding.emailEditText.isEnabled = !show
        binding.passwordEditText.isEnabled = !show
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.main, message, Snackbar.LENGTH_LONG).show()
    }

    private fun showNetworkError(message: String) {
        if (!isNetworkErrorShown) {
            isNetworkErrorShown = true
            binding.networkErrorCard.visibility = View.VISIBLE
        }
    }

    private fun hideNetworkError() {
        isNetworkErrorShown = false
        binding.networkErrorCard.visibility = View.GONE
    }

    // Функции для работы с темой
    private fun toggleTheme() {
        val currentNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

        if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }

        // Сохраняем выбранную тему
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putInt("theme_mode", AppCompatDelegate.getDefaultNightMode()).apply()

        // Анимация для кнопки темы
        val rotateAnimation = AnimationUtils.loadAnimation(this, R.anim.rotate)
        binding.themeToggleButton.startAnimation(rotateAnimation)
    }

    private fun loadTheme() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }

    // Функции для работы с языком
    private fun showLanguageDialog() {
        val languages = arrayOf(
            getString(R.string.russian),
            getString(R.string.english),
            getString(R.string.kazakh)
        )

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_language))
            .setItems(languages) { _, which ->
                val locale = when (which) {
                    0 -> "ru"
                    1 -> "en"
                    2 -> "kk"
                    else -> "ru"
                }
                setLocale(locale)
                recreate() // Перезапуск активности для применения языка
            }
            .show()
    }

    private fun setLocale(languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(resources.configuration)
        config.setLocale(locale)

        resources.updateConfiguration(config, resources.displayMetrics)

        // Сохраняем выбранный язык
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit().putString("language", languageCode).apply()
    }

    private fun loadLocale() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val language = prefs.getString("language", "")
        if (language != null && language.isNotEmpty()) {
            setLocale(language)
        }
    }

    // Функции шифрования/дешифрования
    private fun encrypt(text: String): String {
        if (secretKey == null) return text

        try {
            val key = generateKey(secretKey!!)
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encryptedBytes = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
            return text
        }
    }

    private fun decrypt(encryptedText: String): String {
        if (secretKey == null) return encryptedText

        try {
            val key = generateKey(secretKey!!)
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decodedBytes = Base64.decode(encryptedText, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            return encryptedText
        }
    }

    private fun generateKey(password: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = password.toByteArray(Charsets.UTF_8)
        digest.update(bytes, 0, bytes.size)
        val key = digest.digest()
        return SecretKeySpec(key, "AES")
    }
}
