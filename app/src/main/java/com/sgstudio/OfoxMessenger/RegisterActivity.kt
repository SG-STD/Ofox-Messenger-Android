package com.sgstudio.OfoxMessenger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
import kotlinx.coroutines.delay
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
    private var isTermsAccepted = false
    private var privacyPolicyText = ""
    private var termsOfServiceText = ""

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

    // Регистрация для запроса разрешений
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openImagePicker()
        } else {
            showSnackbar(getString(R.string.permission_denied))
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

        loadLegalDocuments()

        binding.termsCheckBox.setOnCheckedChangeListener { _, isChecked ->
            isTermsAccepted = isChecked
        }

        setupClickableTerms()
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

    private fun loadLegalDocuments() {
        val database = FirebaseDatabase.getInstance()

        // Загрузка политики конфиденциальности
        database.getReference("legal/privacy_policy")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    privacyPolicyText = snapshot.getValue(String::class.java) ?: getString(R.string.loading)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("RegisterActivity", "Failed to load privacy policy", error.toException())
                    privacyPolicyText = "Error loading privacy policy"
                }
            })

        // Загрузка пользовательского соглашения
        database.getReference("legal/terms_of_service")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    termsOfServiceText = snapshot.getValue(String::class.java) ?: getString(R.string.loading)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("RegisterActivity", "Failed to load terms of service", error.toException())
                    termsOfServiceText = "Error loading terms of service"
                }
            })
    }

    private fun setupClickableTerms() {
        // Создаем SpannableString с кликабельными частями
        val fullText = getString(R.string.terms_agreement)
        val spannableString = SpannableString(Html.fromHtml(fullText, Html.FROM_HTML_MODE_COMPACT))

        // Находим позиции для "Политики конфиденциальности"
        val privacyStart = fullText.indexOf("Политикой конфиденциальности")
        val privacyEnd = privacyStart + "Политикой конфиденциальности".length

        // Находим позиции для "Пользовательским соглашением"
        val termsStart = fullText.indexOf("Пользовательским соглашением")
        val termsEnd = termsStart + "Пользовательским соглашением".length

        // Добавляем кликабельные спаны
        if (privacyStart >= 0) {
            spannableString.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        showPrivacyPolicyDialog()
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = ContextCompat.getColor(this@RegisterActivity, R.color.orange_500)
                        ds.isUnderlineText = true
                    }
                },
                privacyStart, privacyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        if (termsStart >= 0) {
            spannableString.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        showTermsOfServiceDialog()
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.color = ContextCompat.getColor(this@RegisterActivity, R.color.orange_500)
                        ds.isUnderlineText = true
                    }
                },
                termsStart, termsEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        // Устанавливаем текст и движение
        binding.termsTextView.text = spannableString
        binding.termsTextView.movementMethod = LinkMovementMethod.getInstance()

        // Добавляем иконку, указывающую на кликабельность
        binding.termsTextView.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_open_in_new, 0)
        binding.termsTextView.compoundDrawablePadding = 8
    }

    private fun showPrivacyPolicyDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_legal_document, null)
        val titleTextView = dialogView.findViewById<TextView>(R.id.dialogTitleTextView)
        val contentTextView = dialogView.findViewById<TextView>(R.id.dialogContentTextView)

        titleTextView.text = getString(R.string.privacy_policy_title)

        if (privacyPolicyText.isEmpty()) {
            contentTextView.text = getString(R.string.loading)
            // Загружаем текст, если он еще не загружен
            loadLegalDocuments()
        } else {
            contentTextView.text = privacyPolicyText
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun registerUserWithEmailVerification(nickname: String, email: String, password: String) {
        // Проверка подключения к интернету
        if (!NetworkUtils.isNetworkAvailable(this)) {
            showNetworkError(getString(R.string.network_error))
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                showProgress(true)

                // Подготовка данных для запроса на предварительную регистрацию
                val jsonData = JSONObject().apply {
                    put("email", email)
                    put("password", password)
                    put("nickname", nickname)
                    put("action", "pre_register")
                    if (profileImageBase64 != null) {
                        put("profile_picture", profileImageBase64)
                    }
                }

                // Отправка запроса через NetworkHandler
                val result = networkHandler.sendVerificationRequest(jsonData)

                if (result.isSuccess) {
                    val response = result.getOrNull()
                    if (response != null && response.optBoolean("success", false)) {
                        // Успешная предварительная регистрация
                        showProgress(false)

                        // Для тестирования можно получить код из ответа
                        val verificationCode = response.optString("verification_code", "")
                        if (verificationCode.isNotEmpty()) {
                            Log.d("RegisterActivity", "Verification code: $verificationCode")
                        }

                        // Показываем диалог для ввода кода подтверждения
                        showVerificationCodeDialog(email)
                    } else {
                        showProgress(false)
                        showSnackbar(response?.optString("message") ?: getString(R.string.registration_failed))
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

    private fun showVerificationCodeDialog(email: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_verification_code, null)
        val titleTextView = dialogView.findViewById<TextView>(R.id.titleTextView)
        val messageTextView = dialogView.findViewById<TextView>(R.id.messageTextView)
        val codeInputLayout = dialogView.findViewById<TextInputLayout>(R.id.codeInputLayout)
        val codeEditText = dialogView.findViewById<TextInputEditText>(R.id.codeEditText)
        val resendCodeTextView = dialogView.findViewById<TextView>(R.id.resendCodeTextView)

        titleTextView.text = getString(R.string.verification_code_title)
        messageTextView.text = getString(R.string.verification_code_message, email)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .setPositiveButton(R.string.ok, null) // Мы переопределим этот обработчик ниже
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Пользователь отменил верификацию
                finish()
            }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val enteredCode = codeEditText.text.toString().trim()

                if (enteredCode.isEmpty() || enteredCode.length != 6) {
                    codeInputLayout.error = getString(R.string.invalid_code)
                    return@setOnClickListener
                }

                // Проверяем код через NetworkHandler
                verifyCodeAndCompleteRegistration(email, enteredCode, dialog)
            }
        }

        // Обработчик повторной отправки кода
        resendCodeTextView.setOnClickListener {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    showProgress(true)

                    // Подготовка данных для запроса
                    val jsonData = JSONObject().apply {
                        put("email", email)
                        put("action", "resend_code")
                        put("nickname", binding.nicknameEditText.text.toString().trim()) // Используем текущее значение никнейма
                    }

                    // Отправка запроса через NetworkHandler
                    val result = networkHandler.sendVerificationRequest(jsonData)

                    showProgress(false)

                    if (result.isSuccess) {
                        val response = result.getOrNull()
                        if (response != null && response.optBoolean("success", false)) {
                            showSnackbar(getString(R.string.code_sent))

                            // Для тестирования можно получить код из ответа
                            val verificationCode = response.optString("verification_code", "")
                            if (verificationCode.isNotEmpty()) {
                                Log.d("RegisterActivity", "New verification code: $verificationCode")
                            }
                        } else {
                            showSnackbar(response?.optString("message") ?: "Ошибка отправки кода")
                        }
                    } else {
                        val error = result.exceptionOrNull()?.message ?: getString(R.string.network_error)
                        showSnackbar(error)
                    }
                } catch (e: Exception) {
                    showProgress(false)
                    showSnackbar(e.message ?: "Ошибка отправки кода")
                }
            }
        }

        dialog.show()
    }

    private fun verifyCodeAndCompleteRegistration(email: String, code: String, dialog: AlertDialog) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                showProgress(true)

                // Подготовка данных для запроса
                val jsonData = JSONObject().apply {
                    put("email", email)
                    put("code", code)
                    put("action", "verify_code")
                }

                // Отправка запроса через NetworkHandler
                val result = networkHandler.sendVerificationRequest(jsonData)

                if (result.isSuccess) {
                    val response = result.getOrNull()
                    if (response != null && response.optBoolean("success", false)) {
                        // Успешная верификация
                        val userId = response.optString("user_id", "")

                        // Если есть изображение профиля, загружаем его в Firebase Storage
                        if (selectedImageUri != null && userId.isNotEmpty()) {
                            uploadProfileImage(userId)
                        } else {
                            showProgress(false)
                            dialog.dismiss()

                            // Показываем сообщение об успешной регистрации
                            showSnackbar(getString(R.string.verification_success))

                            // Возвращаемся на экран входа через 2 секунды
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(2000)
                                finish()
                            }
                        }
                    } else {
                        showProgress(false)
                        showSnackbar(response?.optString("message") ?: getString(R.string.verification_failed))
                    }
                } else {
                    val error = result.exceptionOrNull()?.message ?: getString(R.string.verification_failed)
                    showProgress(false)
                    showSnackbar(error)
                }
            } catch (e: Exception) {
                showProgress(false)
                showSnackbar(e.message ?: getString(R.string.verification_failed))
                Log.e("RegisterActivity", "Error verifying code", e)
            }
        }
    }

    private fun showTermsOfServiceDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_legal_document, null)
        val titleTextView = dialogView.findViewById<TextView>(R.id.dialogTitleTextView)
        val contentTextView = dialogView.findViewById<TextView>(R.id.dialogContentTextView)

        titleTextView.text = getString(R.string.terms_of_service_title)

        if (termsOfServiceText.isEmpty()) {
            contentTextView.text = getString(R.string.loading)
            // Загружаем текст, если он еще не загружен
            loadLegalDocuments()
        } else {
            contentTextView.text = termsOfServiceText
        }

        AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // Обновите метод validateInput, чтобы проверять согласие с условиями
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

        // Проверка согласия с условиями
        if (!isTermsAccepted) {
            showSnackbar(getString(R.string.terms_not_accepted))
            isValid = false
        }

        return isValid
    }

    private fun setupClickListeners() {
        // Обработчик выбора фото профиля
        binding.selectPhotoTextView.setOnClickListener {
            checkAndRequestPermissions()
        }

        binding.profileImageView.setOnClickListener {
            checkAndRequestPermissions()
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
                    // Вызываем новый метод вместо registerUser
                    registerUserWithEmailVerification(nickname, email, password)
                } else {
                    showNetworkError(getString(R.string.network_error))
                }
            }
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

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED -> {
                    openImagePicker()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.READ_MEDIA_IMAGES) -> {
                    showPermissionRationaleDialog()
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                }
            }
        } else {
            // Android 12 и ниже
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED -> {
                    openImagePicker()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE) -> {
                    showPermissionRationaleDialog()
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_required)
            .setMessage(R.string.permission_rationale)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                } else {
                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
                                delay(2000)
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
                // Показываем прогресс загрузки
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.registerButton.isEnabled = false
                }

                // Создаем ссылку на Firebase Storage
                val storageRef = FirebaseStorage.getInstance().reference
                val imageRef = storageRef.child("profile_images/$userId/${UUID.randomUUID()}.jpg")

                // Загружаем изображение
                val uploadTask = imageRef.putFile(uri).await()

                // Получаем URL загруженного изображения
                val downloadUrl = imageRef.downloadUrl.await().toString()

                // Обновляем профиль пользователя с URL изображения
                val database = FirebaseDatabase.getInstance()
                val userRef = database.getReference("users/$userId")
                userRef.child("profile_picture").setValue(downloadUrl).await()

                withContext(Dispatchers.Main) {
                    showSnackbar(getString(R.string.registration_success))

                    // Возвращаемся на экран входа через 2 секунды
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(2000)
                        finish()
                    }
                }
            } ?: run {
                withContext(Dispatchers.Main) {
                    showProgress(false)
                    showSnackbar(getString(R.string.registration_success))

                    // Возвращаемся на экран входа через 2 секунды
                    CoroutineScope(Dispatchers.Main).launch {
                        delay(2000)
                        finish()
                    }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                showProgress(false)
                showSnackbar(getString(R.string.error_upload_image))
                Log.e("RegisterActivity", "Error uploading image: ${e.message}", e)
            }
        }
    }

    private fun showEmailVerificationDialog(email: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.email_verification_title)
            .setMessage(getString(R.string.email_verification_message, email))
            .setPositiveButton(R.string.ok) { _, _ ->
                // Показываем диалог для ввода кода подтверждения
                showVerificationCodeDialog(email)
            }
            .setCancelable(false)
            .show()
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

    // Загрузка сохраненных настроек темы и языка
    private fun loadTheme() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val themeMode = prefs.getInt("theme_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }

    private fun loadLocale() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val language = prefs.getString("language", "")
        if (language != null && language.isNotEmpty()) {
            setLocale(language)
        }
    }

    private fun setLocale(languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val config = Configuration(resources.configuration)
        config.setLocale(locale)

        resources.updateConfiguration(config, resources.displayMetrics)
    }
}