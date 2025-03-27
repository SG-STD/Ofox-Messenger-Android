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
import com.sgstudio.OfoxMessenger.utils.NetworkHandler
import com.sgstudio.OfoxMessenger.utils.NetworkUtils
import com.sgstudio.OfoxMessenger.utils.SessionManager
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
    private lateinit var networkHandler: NetworkHandler
    private lateinit var sessionManager: SessionManager

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

        // Инициализация NetworkHandler после загрузки ключа
        networkHandler = NetworkHandler(secretKey)

        // Инициализация SessionManager
        sessionManager = SessionManager(this)

        // Проверка, авторизован ли пользователь
        if (sessionManager.isLoggedIn()) {
            // TODO: Переход на главный экран, если пользователь уже авторизован
            // startActivity(Intent(this, HomeActivity::class.java))
            // finish()
        }

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

    // Обновите метод loginUser:
    // Обновите метод loginUser:
    private fun loginUser(email: String, password: String) {
        // Проверка подключения к интернету
        if (!NetworkUtils.isNetworkAvailable(this)) {
            showNetworkError(getString(R.string.network_error))
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                showProgress(true)

                // Подготовка данных для запроса
                val jsonData = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                    put("action", "login")
                }

                // Отправка запроса через NetworkHandler
                val result = networkHandler.sendAuthRequest(jsonData)

                if (result.isSuccess) {
                    val response = result.getOrNull()
                    if (response != null && response.optBoolean("success", false)) {
                        // Успешный вход
                        val userId = response.getString("user_id")
                        val nickname = response.optString("nickname", "")
                        val profilePicture = response.optString("profile_picture", "")
                        val status = response.optString("status", "")

                        // Сохраняем данные пользователя
                        saveUserData(userId, nickname, profilePicture, status)

                        // Показываем сообщение об успешном входе
                        showSnackbar("Добро пожаловать, $nickname!")

                        // TODO: Переход на главный экран
                        // startActivity(Intent(this@MainActivity, HomeActivity::class.java))

                        showProgress(false)
                    } else {
                        showProgress(false)
                        showSnackbar("Ошибка входа")
                    }
                } else {
                    val error = result.exceptionOrNull()?.message ?: getString(R.string.network_error)
                    showProgress(false)
                    showSnackbar(error)

                    if (error.contains("сети") || error.contains("network")) {
                        showNetworkError(error)
                    }
                }
            } catch (e: Exception) {
                showProgress(false)
                showSnackbar(e.message ?: getString(R.string.network_error))
            }
        }
    }

    // Добавьте новый метод для получения данных пользователя:
    private fun fetchUserData(userId: String) {
        val database = FirebaseDatabase.getInstance()
        val userRef = database.getReference("users/$userId")

        userRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                showProgress(false)

                if (snapshot.exists()) {
                    // Обновляем время последнего входа
                    updateUserLastLogin(userId)

                    // Получаем данные пользователя
                    val nickname = snapshot.child("nickname").getValue(String::class.java) ?: ""
                    val profilePicture = snapshot.child("profile_picture").getValue(String::class.java) ?: ""
                    val status = snapshot.child("status").getValue(String::class.java) ?: ""

                    // Сохраняем данные пользователя локально
                    saveUserData(userId, nickname, profilePicture, status)

                    // Показываем сообщение об успешном входе
                    showSnackbar("Добро пожаловать, $nickname!")

                    // TODO: Переход на главный экран
                    // Например: startActivity(Intent(this@MainActivity, HomeActivity::class.java))
                } else {
                    showSnackbar("Ошибка: данные пользователя не найдены")
                }
            }

            override fun onCancelled(error: DatabaseError) {
                showProgress(false)
                showSnackbar("Ошибка при получении данных: ${error.message}")
            }
        })
    }

    // Добавьте метод для сохранения данных пользователя:
    private fun saveUserData(userId: String, nickname: String, profilePicture: String, status: String) {
        val prefs = getSharedPreferences("user_session", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("user_id", userId)
            putString("nickname", nickname)
            putString("profile_picture", profilePicture)
            putString("status", status)
            putLong("last_login", System.currentTimeMillis())
            apply()
        }
    }

    private fun updateUserLastLogin(userId: String) {
        val database = FirebaseDatabase.getInstance()
        val userRef = database.getReference("users/$userId")

        val currentTime = System.currentTimeMillis()
        val updates = hashMapOf<String, Any>(
            "last_login" to currentTime.toString(),
            "last_seen" to currentTime
        )

        userRef.updateChildren(updates)

        // Обновляем FCM токен, если он изменился
        updateFcmToken(userId)
    }

    // Добавьте метод для обновления FCM токена:
    private fun updateFcmToken(userId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Здесь можно получить текущий FCM токен и обновить его в базе данных
                // Например, с использованием Firebase Messaging:
                // val token = FirebaseMessaging.getInstance().token.await()

                // Для примера используем заглушку
                val token = "current_fcm_token"

                val database = FirebaseDatabase.getInstance()
                val userRef = database.getReference("users/$userId")

                userRef.child("fcm_token").setValue(token)
            } catch (e: Exception) {
                // Обработка ошибок
            }
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
