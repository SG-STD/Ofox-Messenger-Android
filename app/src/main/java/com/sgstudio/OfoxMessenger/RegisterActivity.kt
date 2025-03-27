package com.sgstudio.OfoxMessenger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import com.sgstudio.OfoxMessenger.databinding.ActivityRegisterBinding
import com.sgstudio.OfoxMessenger.utils.NetworkHandler
import com.sgstudio.OfoxMessenger.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private var secretKey: String? = null
    private lateinit var networkHandler: NetworkHandler
    private var isNetworkErrorShown = false
    private var selectedImageUri: Uri? = null
    private var profileImageBase64: String? = null

    // Регистрация для получения результата выбора изображения
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedImageUri = uri
                binding.profileImageView.setImageURI(uri)
                
                // Конвертируем изображение в Base64
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)
                profileImageBase64 = bitmapToBase64(bitmap)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Загрузка сохраненных настроек
        loadLocale()
        loadTheme()

        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
                    // Инициализация NetworkHandler после загрузки ключа
                    networkHandler = NetworkHandler(secretKey)
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
        binding.registerButton.startAnimation(slideUp)
    }

    private fun setupClickListeners() {
        // Обработчик выбора фото профиля
        binding.selectPhotoTextView.setOnClickListener {
            openImagePicker()
        }
        
        binding.profileImageView.setOnClickListener {
            openImagePicker()
        }

        // Обработчик кнопки регистрации
        binding.registerButton.setOnClickListener {
            val nickname = binding.nicknameEditText.text.toString().trim()
            val email = binding.emailEditText.text.toString().trim()
            val password = binding.passwordEditText.text.toString().trim()
            val confirmPassword = binding.confirmPasswordEditText.text.toString().trim()

            if (validateInput(nickname, email, password, confirmPassword)) {
                if (secretKey != null) {
                    showProgress(true)
                    registerUser(nickname, email, password)
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

        // Обработчик перехода на вход
        binding.loginTextView.setOnClickListener {
            finish() // Возврат на экран входа
        }

        // Обработчик кнопки повтора при ошибке сети
        binding.retryButton.setOnClickListener {
            hideNetworkError()
            loadEncryptionKey()
        }
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        // Сжимаем изображение для уменьшения размера
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }

    private fun validateInput(nickname: String, email: String, password: String, confirmPassword: String): Boolean {
        var isValid = true

        // Проверка никнейма
        if (nickname.isEmpty()) {
            binding.nicknameInputLayout.error = getString(R.string.error_empty_nickname)
            isValid = false
        } else if (nickname.length < 3) {
            binding.nicknameInputLayout.error = getString(R.string.error_short_nickname)
            isValid = false
        } else {
            binding.nicknameInputLayout.error = null
        }

        // Проверка email
        if (email.isEmpty()) {
            binding.emailInputLayout.error = getString(R.string.error_empty_email)
            isValid = false
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.emailInputLayout.error = getString(R.string.error_invalid_email)
            isValid = false
        } else {
            binding.emailInputLayout.error = null
        }

        // Проверка пароля
        if (password.isEmpty()) {
            binding.passwordInputLayout.error = getString(R.string.error_empty_password)
            isValid = false
        } else if (password.length < 6) {
            binding.passwordInputLayout.error = getString(R.string.error_short_password)
            isValid = false
        } else {
            binding.passwordInputLayout.error = null
        }

        // Проверка подтверждения пароля
        if (confirmPassword.isEmpty()) {
            binding.confirmPasswordInputLayout.error = getString(R.string.error_empty_confirm_password)
            isValid = false
        } else if (confirmPassword != password) {
            binding.confirmPasswordInputLayout.error = getString(R.string.error_passwords_not_match)
            isValid = false
        } else {
            binding.confirmPasswordInputLayout.error = null
        }

        return isValid
    }

    private fun registerUser(nickname: String, email: String, password: String) {
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
                    put("nickname", nickname)
                    put("action", "register")
                    if (profileImageBase64 != null) {
                        put("profile_picture", profileImageBase64)
                    }
                }

                // Отправка запроса через NetworkHandler
                val result = networkHandler.sendAuthRequest(jsonData)

                if (result.isSuccess) {
                    val response = result.getOrNull()
                    if (response != null && response.optBoolean("success", false)) {
                        // Успешная регистрация
                        val userId = response.getString("user_id")
                        
                        // Если есть изображение профиля, загружаем его в Firebase Storage
                        if (selectedImageUri != null) {
                            uploadProfileImage(userId)
                        } else {
                            // Показываем сообщение об успешной регистрации
                            showSnackbar(getString(R.string.registration_success))
                            
                            // Возвращаемся на экран входа через 2 секунды
                            withContext(Dispatchers.IO) {
                                kotlinx.coroutines.delay(2000)
                            }
                            finish()
                        }
                    } else {
                        showProgress(false)
                        showSnackbar(getString(R.string.registration_failed))
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

    private suspend fun uploadProfileImage(userId: String) {
        try {
            selectedImageUri?.let { uri ->
                val storageRef = FirebaseStorage.getInstance().reference
                val imageRef = storageRef.child("profile_images/$userId/${UUID.randomUUID()}.jpg")
                
                // Загружаем изображение в Firebase Storage
                val uploadTask = imageRef.putFile(uri).await()
                
                // Получаем URL загруженного изображения
                val downloadUrl = imageRef.downloadUrl.await().toString()
                
                // Обновляем профиль пользователя с URL изображения
                val database = FirebaseDatabase.getInstance()
                val userRef = database.getReference("users/$userId")
                userRef.child("profile_picture").setValue(downloadUrl)
                
                showProgress(false)
                showSnackbar(getString(R.string.registration_success))
                
                // Возвращаемся на экран входа через 2 секунды
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.delay(2000)
                }
                finish()
            }
        } catch (e: Exception) {
            showProgress(false)
            showSnackbar(getString(R.string.error_upload_image))
        }
    }

    private fun showProgress(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
        binding.registerButton.isEnabled = !show
        binding.nicknameEditText.isEnabled = !show
        binding.emailEditText.isEnabled = !show
        binding.passwordEditText.isEnabled = !show
        binding.confirmPasswordEditText.isEnabled = !show
        binding.selectPhotoTextView.isEnabled = !show
        binding.profileImageView.isEnabled = !show
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.registerMain, message, Snackbar.LENGTH_LONG).show()
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