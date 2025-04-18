package com.sgstudio.OfoxMessenger.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mindrot.jbcrypt.BCrypt
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NetworkHandler(private val secretKey: String?) {

    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()

    // Ограничение запросов (не более 5 запросов в минуту)
    private val requestTimestamps = mutableListOf<Long>()
    private val requestLimit = 5
    private val requestTimeWindow = 60000L // 1 минута в миллисекундах
    private val customAuthManager = CustomAuthManager()
    private lateinit var context: Context

    suspend fun sendAuthRequest(data: JSONObject): Result<JSONObject> {
        return withContext(Dispatchers.IO) {
            // Проверка лимита запросов
            if (!checkRequestLimit()) {
                return@withContext Result.failure(Exception("Превышен лимит запросов. Пожалуйста, подождите немного."))
            }

            try {
                val action = data.getString("action")
                val emailOrNickname = data.getString("email") // Может быть как email, так и nickname
                val password = data.getString("password")

                Log.d("NetworkHandler", "Запрос аутентификации: action=$action, identifier=$emailOrNickname")

                when (action) {
                    "login" -> {
                        // Прямой поиск пользователя в базе данных
                        Log.d("NetworkHandler", "Прямой поиск пользователя по никнейму")

                        // Сначала ищем по никнейму
                        val userSnapshot = suspendCancellableCoroutine<DataSnapshot?> { continuation ->
                            database.getReference("users")
                                .orderByChild("nickname")
                                .equalTo(emailOrNickname)
                                .get()
                                .addOnSuccessListener { snapshot ->
                                    if (snapshot.exists()) {
                                        Log.d("NetworkHandler", "Пользователь найден по никнейму")
                                        continuation.resume(snapshot.children.first())
                                    } else {
                                        // Если по никнейму не нашли, ищем по email
                                        Log.d("NetworkHandler", "Пользователь по никнейму не найден, ищем по email")
                                        database.getReference("users")
                                            .orderByChild("email")
                                            .equalTo(emailOrNickname)
                                            .get()
                                            .addOnSuccessListener { emailSnapshot ->
                                                if (emailSnapshot.exists()) {
                                                    Log.d("NetworkHandler", "Пользователь найден по email")
                                                    continuation.resume(emailSnapshot.children.first())
                                                } else {
                                                    Log.d("NetworkHandler", "Пользователь не найден ни по никнейму, ни по email")
                                                    continuation.resume(null)
                                                }
                                            }
                                            .addOnFailureListener { error ->
                                                Log.e("NetworkHandler", "Ошибка при поиске по email: ${error.message}")
                                                continuation.resume(null)
                                            }
                                    }
                                }
                                .addOnFailureListener { error ->
                                    Log.e("NetworkHandler", "Ошибка при поиске по никнейму: ${error.message}")
                                    continuation.resume(null)
                                }
                        }

                        if (userSnapshot == null) {
                            return@withContext Result.failure(Exception("Пользователь не найден"))
                        }

                        val userId = userSnapshot.key ?: ""
                        val passwordHash = userSnapshot.child("password_hash").getValue(String::class.java)

                        if (passwordHash == null) {
                            return@withContext Result.failure(Exception("Ошибка аутентификации: хеш пароля не найден"))
                        }

                        // Используем улучшенную проверку пароля
                        if (verifyPassword(password, passwordHash)) {
                            // Пароль верный, получаем данные пользователя
                            val nickname = userSnapshot.child("nickname").getValue(String::class.java) ?: ""
                            val profilePicture = userSnapshot.child("profile_picture").getValue(String::class.java) ?: ""
                            val status = userSnapshot.child("status").getValue(String::class.java) ?: ""
                            val email = userSnapshot.child("email").getValue(String::class.java) ?: ""

                            // Обновляем время последнего входа
                            updateLastLogin(userId)

                            // Создаем сессию
                            val sessionId = createNewSession(nickname)

                            // Кэшируем данные пользователя для офлайн-режима
                            cacheUserData(userSnapshot)

                            // Создаем ответ
                            val response = JSONObject().apply {
                                put("success", true)
                                put("user_id", userId)
                                put("email", email)
                                put("nickname", nickname)
                                put("profile_picture", profilePicture)
                                put("status", status)
                                put("session_id", sessionId)
                            }

                            return@withContext Result.success(response)
                        } else {
                            return@withContext Result.failure(Exception("Неверный пароль"))
                        }
                    }
                    "register" -> {
                        // Проверяем, не существует ли уже пользователь с таким email
                        val userExists = checkUserExists(emailOrNickname)

                        if (userExists) {
                            return@withContext Result.failure(Exception("Пользователь с таким email уже существует"))
                        }

                        // Генерируем уникальный ID для нового пользователя
                        val userId = database.getReference("users").push().key ?:
                        return@withContext Result.failure(Exception("Ошибка создания пользователя"))

                        // Хешируем пароль с BCrypt
                        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())

                        // Создаем данные пользователя
                        val userData = HashMap<String, Any>().apply {
                            put("email", emailOrNickname)
                            put("password_hash", passwordHash)
                            put("nickname", data.optString("nickname", emailOrNickname.substringBefore("@")))
                            put("profile_picture", "")
                            put("status", "")
                            put("registration_date", System.currentTimeMillis().toString())
                            put("last_login", System.currentTimeMillis().toString())
                            put("last_seen", System.currentTimeMillis())
                            put("is_online", false)
                            put("isBanned", false)
                        }

                        // Сохраняем пользователя в базу данных
                        database.getReference("users/$userId").setValue(userData)
                            .addOnSuccessListener {
                                // Успешно создан пользователь
                            }
                            .addOnFailureListener {
                                // Ошибка при создании пользователя
                            }

                        // Создаем ответ
                        val response = JSONObject().apply {
                            put("success", true)
                            put("user_id", userId)
                            put("email", emailOrNickname)
                            put("message", "Регистрация успешно завершена")
                        }

                        return@withContext Result.success(response)
                    }
                    else -> {
                        return@withContext Result.failure(Exception("Неизвестное действие"))
                    }
                }
            } catch (e: Exception) {
                Log.e("NetworkHandler", "Ошибка при обработке запроса: ${e.message}", e)
                return@withContext Result.failure(Exception("Ошибка при обработке запроса: ${e.message}"))
            }
        }
    }

    // Обновленная функция проверки пароля
    private fun verifyPassword(password: String, hashedPassword: String): Boolean {
        return try {
            if (hashedPassword.length < 4) {
                // Если хеш слишком короткий, просто сравниваем напрямую
                password == hashedPassword
            } else {
                val algorithm = hashedPassword.substring(0, 4)
                when (algorithm) {
                    "\$2y\$" -> BCrypt.checkpw(password, hashedPassword.replace("\$2y\$", "\$2a\$"))
                    "\$2a\$" -> BCrypt.checkpw(password, hashedPassword)
                    else -> password == hashedPassword
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkHandler", "Ошибка при проверке пароля: ${e.message}", e)
            // Если произошла ошибка при проверке, пробуем прямое сравнение
            password == hashedPassword
        }
    }

    // Обновленная функция создания сессии
    private suspend fun createNewSession(nickname: String): String = suspendCancellableCoroutine { continuation ->
        val deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        val sessionId = java.util.UUID.randomUUID().toString()

        val session = HashMap<String, Any>().apply {
            put("id", sessionId)
            put("deviceName", deviceName)
            put("deviceType", "ANDROID")
            put("lastActive", com.google.firebase.database.ServerValue.TIMESTAMP)
            put("createdAt", com.google.firebase.database.ServerValue.TIMESTAMP)
        }

        database.getReference("sessions")
            .child(nickname)
            .child(sessionId)
            .setValue(session)
            .addOnSuccessListener {
                continuation.resume(sessionId)
            }
            .addOnFailureListener {
                continuation.resume("") // Возвращаем пустую строку в случае ошибки
            }
    }

    // Обновленная функция кэширования данных пользователя
    private fun cacheUserData(userSnapshot: DataSnapshot) {
        try {
            val userData = HashMap<String, Any>()

            // Извлекаем все необходимые поля из снапшота
            if (userSnapshot.hasChild("nickname")) {
                userData["nickname"] = userSnapshot.child("nickname").getValue(String::class.java) ?: ""
            }
            if (userSnapshot.hasChild("email")) {
                userData["email"] = userSnapshot.child("email").getValue(String::class.java) ?: ""
            }
            if (userSnapshot.hasChild("profile_picture")) {
                userData["profile_picture"] = userSnapshot.child("profile_picture").getValue(String::class.java) ?: ""
            }
            if (userSnapshot.hasChild("status")) {
                userData["status"] = userSnapshot.child("status").getValue(String::class.java) ?: ""
            }

            // Сохраняем данные в SharedPreferences
            context.getSharedPreferences("user_cache", Context.MODE_PRIVATE).edit().apply {
                putString("user_id", userSnapshot.key)
                putString("nickname", userData["nickname"] as? String ?: "")
                putString("email", userData["email"] as? String ?: "")
                putString("profile_picture", userData["profile_picture"] as? String ?: "")
                putString("status", userData["status"] as? String ?: "")
                putLong("cache_timestamp", System.currentTimeMillis())
                apply()
            }
        } catch (e: Exception) {
            Log.e("NetworkHandler", "Ошибка при кэшировании данных пользователя: ${e.message}", e)
        }
    }

    // Добавьте эти вспомогательные функции
    private fun getUserById(userId: String): DataSnapshot? {
        var result: DataSnapshot? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        database.getReference("users").child(userId)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        result = snapshot
                    }
                    latch.countDown()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("NetworkHandler", "Ошибка при получении пользователя по ID: ${error.message}")
                    latch.countDown()
                }
            })

        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e("NetworkHandler", "Timeout при получении пользователя по ID", e)
        }

        return result
    }

    private fun getUserByNickname(nickname: String): DataSnapshot? {
        var result: DataSnapshot? = null
        val latch = java.util.concurrent.CountDownLatch(1)

        database.getReference("users")
            .orderByChild("nickname")
            .equalTo(nickname)
            .limitToFirst(1)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists() && snapshot.childrenCount > 0) {
                        result = snapshot.children.first()
                    }
                    latch.countDown()
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("NetworkHandler", "Ошибка при получении пользователя по nickname: ${error.message}")
                    latch.countDown()
                }
            })

        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e("NetworkHandler", "Timeout при получении пользователя по nickname", e)
        }

        return result
    }

    private fun getAllUsers(): List<DataSnapshot> {
        val result = mutableListOf<DataSnapshot>()
        val latch = java.util.concurrent.CountDownLatch(1)

        database.getReference("users")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        for (userSnapshot in snapshot.children) {
                            result.add(userSnapshot)
                        }
                    }
                    latch.countDown()
                }

                override fun onCancelled(error: DatabaseError) {
                    latch.countDown()
                }
            })

        try {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e("NetworkHandler", "Timeout при получении всех пользователей", e)
        }

        return result
    }

    private suspend fun findUserByEmailOrNickname(emailOrNickname: String): Result<DataSnapshot> = suspendCancellableCoroutine { continuation ->
        val usersRef = database.getReference("users")

        // Добавляем логирование для отладки
        Log.d("NetworkHandler", "Ищем пользователя по: $emailOrNickname")

        // Сначала ищем по email
        usersRef.orderByChild("email").equalTo(emailOrNickname).limitToFirst(1)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    Log.d("NetworkHandler", "Результат поиска по email: ${snapshot.exists()}, количество: ${snapshot.childrenCount}")

                    if (snapshot.exists() && snapshot.childrenCount > 0) {
                        val user = snapshot.children.first()
                        Log.d("NetworkHandler", "Найден пользователь по email: ${user.child("nickname").getValue(String::class.java)}")
                        continuation.resume(Result.success(user))
                    } else {
                        // Если по email не нашли, ищем по nickname
                        Log.d("NetworkHandler", "Пользователь по email не найден, ищем по nickname")

                        usersRef.orderByChild("nickname").equalTo(emailOrNickname).limitToFirst(1)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(nicknameSnapshot: DataSnapshot) {
                                    Log.d("NetworkHandler", "Результат поиска по nickname: ${nicknameSnapshot.exists()}, количество: ${nicknameSnapshot.childrenCount}")

                                    if (nicknameSnapshot.exists() && nicknameSnapshot.childrenCount > 0) {
                                        val user = nicknameSnapshot.children.first()
                                        Log.d("NetworkHandler", "Найден пользователь по nickname: ${user.child("nickname").getValue(String::class.java)}")
                                        continuation.resume(Result.success(user))
                                    } else {
                                        Log.d("NetworkHandler", "Пользователь не найден ни по email, ни по nickname")

                                        // Для отладки: выполним полный поиск по всем пользователям
                                        usersRef.addListenerForSingleValueEvent(object : ValueEventListener {
                                            override fun onDataChange(allUsersSnapshot: DataSnapshot) {
                                                Log.d("NetworkHandler", "Всего пользователей в базе: ${allUsersSnapshot.childrenCount}")

                                                // Выведем всех пользователей для отладки
                                                for (userSnapshot in allUsersSnapshot.children) {
                                                    val userEmail = userSnapshot.child("email").getValue(String::class.java)
                                                    val userNickname = userSnapshot.child("nickname").getValue(String::class.java)
                                                    Log.d("NetworkHandler", "Пользователь в базе: email=$userEmail, nickname=$userNickname")
                                                }

                                                continuation.resume(Result.failure(Exception("Пользователь не найден")))
                                            }

                                            override fun onCancelled(error: DatabaseError) {
                                                Log.e("NetworkHandler", "Ошибка при получении всех пользователей: ${error.message}")
                                                continuation.resume(Result.failure(Exception("Ошибка базы данных: ${error.message}")))
                                            }
                                        })
                                    }
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    Log.e("NetworkHandler", "Ошибка при поиске по nickname: ${error.message}")
                                    continuation.resume(Result.failure(Exception("Ошибка базы данных: ${error.message}")))
                                }
                            })
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("NetworkHandler", "Ошибка при поиске по email: ${error.message}")
                    continuation.resume(Result.failure(Exception("Ошибка базы данных: ${error.message}")))
                }
            })
    }

    private suspend fun checkUserExists(emailOrNickname: String): Boolean = suspendCancellableCoroutine { continuation ->
        val usersRef = database.getReference("users")

        // Проверяем по email
        usersRef.orderByChild("email").equalTo(emailOrNickname).limitToFirst(1)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists() && snapshot.childrenCount > 0) {
                        continuation.resume(true)
                    } else {
                        // Проверяем по nickname
                        usersRef.orderByChild("nickname").equalTo(emailOrNickname).limitToFirst(1)
                            .addListenerForSingleValueEvent(object : ValueEventListener {
                                override fun onDataChange(nicknameSnapshot: DataSnapshot) {
                                    continuation.resume(nicknameSnapshot.exists() && nicknameSnapshot.childrenCount > 0)
                                }

                                override fun onCancelled(error: DatabaseError) {
                                    continuation.resume(false)
                                }
                            })
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    continuation.resume(false)
                }
            })
    }

    private fun updateLastLogin(userId: String) {
        val userRef = database.getReference("users/$userId")
        val currentTime = System.currentTimeMillis()

        val updates = hashMapOf<String, Any>(
            "last_login" to currentTime.toString(),
            "last_seen" to currentTime
        )

        userRef.updateChildren(updates)
    }

    private fun logAuthAttempt(encryptedIdentifier: String, action: String, encryptedTimestamp: String) {
        val logsRef = database.getReference("logs/auth")
        val logEntry = HashMap<String, Any>().apply {
            put("identifier", encryptedIdentifier) // Зашифрованный идентификатор (email или nickname)
            put("action", action)
            put("timestamp", encryptedTimestamp) // Зашифрованная метка времени
        }

        logsRef.push().setValue(logEntry)
    }

    private suspend fun findUserByEmail(email: String): Result<DataSnapshot> = suspendCancellableCoroutine { continuation ->
        val usersRef = database.getReference("users")

        usersRef.orderByChild("email").equalTo(email).limitToFirst(1)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists() && snapshot.childrenCount > 0) {
                        continuation.resume(Result.success(snapshot.children.first()))
                    } else {
                        continuation.resume(Result.failure(Exception("Пользователь с таким email не найден")))
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    continuation.resume(Result.failure(Exception("Ошибка базы данных: ${error.message}")))
                }
            })
    }

    suspend fun sendDataRequest(data: JSONObject, userId: String): Result<JSONObject> {
        return withContext(Dispatchers.IO) {
            // Проверка лимита запросов
            if (!checkRequestLimit()) {
                return@withContext Result.failure(Exception("Превышен лимит запросов. Пожалуйста, подождите немного."))
            }

            try {
                val action = data.getString("action")

                when (action) {
                    "update_profile" -> {
                        // Обновление профиля пользователя
                        val updates = HashMap<String, Any>()

                        if (data.has("nickname")) {
                            updates["nickname"] = data.getString("nickname")
                        }

                        if (data.has("status")) {
                            updates["status"] = data.getString("status")
                        }

                        // Шифруем данные для логов
                        val encryptedAction = encrypt("update_profile")
                        val encryptedTimestamp = encrypt(System.currentTimeMillis().toString())

                        // Логируем действие
                        logUserAction(userId, encryptedAction, encryptedTimestamp)

                        // Обновляем данные в Firebase
                        updateUserData(userId, updates)

                        return@withContext Result.success(JSONObject().apply {
                            put("success", true)
                            put("message", "Профиль обновлен")
                        })
                    }
                    "get_user_data" -> {
                        // Получение данных пользователя
                        val userData = getUserData(userId)

                        return@withContext Result.success(userData)
                    }
                    else -> {
                        return@withContext Result.failure(Exception("Неизвестное действие"))
                    }
                }
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Ошибка при обработке запроса: ${e.message}"))
            }
        }
    }

    private suspend fun signInWithEmailPassword(email: String, password: String): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener {
                    continuation.resume(Result.success(Unit))
                }
                .addOnFailureListener { exception ->
                    // Более детальная обработка ошибок
                    val errorMessage = when (exception.message) {
                        "The email address is badly formatted." ->
                            "Неверный формат email-адреса"
                        "The password is invalid or the user does not have a password." ->
                            "Неверный пароль"
                        "There is no user record corresponding to this identifier. The user may have been deleted." ->
                            "Пользователь с таким email не найден"
                        "The supplied auth credential is incorrect, malformed or has expired." ->
                            "Учетные данные недействительны или устарели. Попробуйте войти заново"
                        else -> "Ошибка входа: ${exception.message}"
                    }
                    continuation.resume(Result.failure(Exception(errorMessage)))
                }
        }

    private suspend fun getUserData(userId: String): JSONObject =
        suspendCancellableCoroutine { continuation ->
            val userRef = database.getReference("users/$userId")

            userRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val userData = JSONObject().apply {
                            put("user_id", userId)

                            // Получаем все поля из снапшота
                            if (snapshot.hasChild("nickname")) {
                                put("nickname", snapshot.child("nickname").getValue(String::class.java) ?: "")
                            }

                            if (snapshot.hasChild("email")) {
                                put("email", snapshot.child("email").getValue(String::class.java) ?: "")
                            }

                            if (snapshot.hasChild("profile_picture")) {
                                put("profile_picture", snapshot.child("profile_picture").getValue(String::class.java) ?: "")
                            }

                            if (snapshot.hasChild("status")) {
                                put("status", snapshot.child("status").getValue(String::class.java) ?: "")
                            }

                            if (snapshot.hasChild("last_seen")) {
                                put("last_seen", snapshot.child("last_seen").getValue(Long::class.java) ?: 0)
                            }

                            if (snapshot.hasChild("registration_date")) {
                                put("registration_date", snapshot.child("registration_date").getValue(String::class.java) ?: "")
                            }
                        }

                        // Обновляем last_seen
                        updateLastSeen(userId)

                        continuation.resume(userData)
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    continuation.resumeWithException(error.toException())
                }
            })
        }

    private fun updateLastSeen(userId: String) {
        val userRef = database.getReference("users/$userId")
        userRef.child("last_seen").setValue(System.currentTimeMillis())
    }

    private suspend fun updateUserData(userId: String, updates: Map<String, Any>): Boolean =
        suspendCancellableCoroutine { continuation ->
            val userRef = database.getReference("users/$userId")

            userRef.updateChildren(updates)
                .addOnSuccessListener {
                    continuation.resume(true)
                }
                .addOnFailureListener { exception ->
                    continuation.resume(false)
                }
        }

    private fun logUserAction(userId: String, encryptedAction: String, encryptedTimestamp: String) {
        val logsRef = database.getReference("logs/user_actions")
        val logEntry = HashMap<String, Any>().apply {
            put("user_id", userId)
            put("action", encryptedAction) // Зашифрованное действие
            put("timestamp", encryptedTimestamp) // Зашифрованная метка времени
        }

        logsRef.push().setValue(logEntry)
    }

    private fun checkRequestLimit(): Boolean {
        val currentTime = System.currentTimeMillis()

        // Удаляем устаревшие метки времени
        requestTimestamps.removeAll { it < currentTime - requestTimeWindow }

        // Проверяем, не превышен ли лимит
        if (requestTimestamps.size >= requestLimit) {
            return false
        }

        // Добавляем новую метку времени
        requestTimestamps.add(currentTime)
        return true
    }

    fun encrypt(text: String): String {
        if (secretKey == null) return text

        try {
            val key = generateKey(secretKey)
            val cipher = Cipher.getInstance("AES")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encryptedBytes = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(encryptedBytes, Base64.DEFAULT)
        } catch (e: Exception) {
            e.printStackTrace()
            return text
        }
    }

    fun decrypt(encryptedText: String): String {
        if (secretKey == null) return encryptedText

        try {
            val key = generateKey(secretKey)
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

    // Добавьте этот метод в класс NetworkHandler

    suspend fun registerUserWithProfileImage(data: JSONObject): Result<JSONObject> {
        return withContext(Dispatchers.IO) {
            // Проверка лимита запросов
            if (!checkRequestLimit()) {
                return@withContext Result.failure(Exception("Превышен лимит запросов. Пожалуйста, подождите немного."))
            }

            try {
                val email = data.getString("email")
                val password = data.getString("password")
                val nickname = data.getString("nickname")
                val profilePicture = data.optString("profile_picture", "")

                // Проверяем, не существует ли уже пользователь с таким email или nickname
                val userExistsByEmail = checkUserExists(email)
                if (userExistsByEmail) {
                    return@withContext Result.failure(Exception("Пользователь с таким email уже существует"))
                }

                val userExistsByNickname = checkUserExistsByNickname(nickname)
                if (userExistsByNickname) {
                    return@withContext Result.failure(Exception("Пользователь с таким никнеймом уже существует"))
                }

                // Генерируем уникальный ID для нового пользователя
                val userId = database.getReference("users").push().key ?:
                return@withContext Result.failure(Exception("Ошибка создания пользователя"))

                // Хешируем пароль с BCrypt
                val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())

                // Создаем данные пользователя
                val userData = HashMap<String, Any>().apply {
                    put("email", email)
                    put("password_hash", passwordHash)
                    put("nickname", nickname)
                    put("profile_picture", profilePicture)
                    put("status", "")
                    put("registration_date", System.currentTimeMillis().toString())
                    put("last_login", System.currentTimeMillis().toString())
                    put("last_seen", System.currentTimeMillis())
                    put("is_online", false)
                    put("isBanned", false)
                }

                // Сохраняем пользователя в базу данных
                database.getReference("users/$userId").setValue(userData)
                    .addOnSuccessListener {
                        // Успешно создан пользователь
                    }
                    .addOnFailureListener {
                        // Ошибка при создании пользователя
                    }

                // Создаем ответ
                val response = JSONObject().apply {
                    put("success", true)
                    put("user_id", userId)
                    put("email", email)
                    put("nickname", nickname)
                    put("message", "Регистрация успешно завершена")
                }

                return@withContext Result.success(response)
            } catch (e: Exception) {
                Log.e("NetworkHandler", "Ошибка при регистрации пользователя: ${e.message}", e)
                return@withContext Result.failure(Exception("Ошибка при регистрации: ${e.message}"))
            }
        }
    }

    // Добавьте этот метод для проверки существования пользователя по никнейму
    private suspend fun checkUserExistsByNickname(nickname: String): Boolean = suspendCancellableCoroutine { continuation ->
        val usersRef = database.getReference("users")

        usersRef.orderByChild("nickname").equalTo(nickname).limitToFirst(1)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    continuation.resume(snapshot.exists() && snapshot.childrenCount > 0)
                }

                override fun onCancelled(error: DatabaseError) {
                    continuation.resume(false)
                }
            })
    }

    suspend fun sendVerificationRequest(data: JSONObject): Result<JSONObject> {
        return withContext(Dispatchers.IO) {
            // Проверка лимита запросов
            if (!checkRequestLimit()) {
                return@withContext Result.failure(Exception("Превышен лимит запросов. Пожалуйста, подождите немного."))
            }

            try {
                val action = data.optString("action", "")

                when (action) {
                    "pre_register" -> {
                        // Предварительная регистрация с отправкой кода подтверждения
                        val email = data.getString("email")
                        val nickname = data.getString("nickname")

                        // Проверяем, не существует ли уже пользователь с таким email
                        val emailExists = checkUserExists(email)
                        if (emailExists) {
                            return@withContext Result.failure(Exception("Пользователь с таким email уже существует"))
                        }

                        // Проверяем, не существует ли пользователь с таким никнеймом
                        val nicknameExists = checkUserExistsByNickname(nickname)
                        if (nicknameExists) {
                            return@withContext Result.failure(Exception("Пользователь с таким никнеймом уже существует"))
                        }

                        // Генерируем 6-значный код подтверждения
                        val verificationCode = (100000..999999).random().toString()

                        // Сохраняем данные во временной записи
                        val database = FirebaseDatabase.getInstance()
                        val pendingUserRef = database.getReference("pending_users").child(email.replace(".", ","))

                        val pendingUserData = HashMap<String, Any>().apply {
                            put("email", email)
                            put("nickname", nickname)
                            put("verification_code", verificationCode)
                            put("created_at", System.currentTimeMillis())
                            put("expires_at", System.currentTimeMillis() + 3600000) // 1 час

                            // Сохраняем пароль и фото профиля, если они есть
                            if (data.has("password")) {
                                val password = data.getString("password")
                                val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt())
                                put("password_hash", passwordHash)
                            } else if (data.has("password_hash")) {
                                put("password_hash", data.getString("password_hash"))
                            }

                            if (data.has("profile_picture")) {
                                put("profile_picture", data.getString("profile_picture"))
                            }
                        }

                        pendingUserRef.setValue(pendingUserData).await()

                        // Отправляем email с кодом подтверждения
                        val emailSent = EmailSender.sendVerificationEmail(email, nickname, verificationCode)

                        if (emailSent) {
                            val result = JSONObject().apply {
                                put("success", true)
                                put("message", "Код подтверждения отправлен")
                                // В реальном приложении не следует возвращать код клиенту
                                // Это только для тестирования
                                put("verification_code", verificationCode)
                            }

                            return@withContext Result.success(result)
                        } else {
                            // Если не удалось отправить email, удаляем временную запись
                            pendingUserRef.removeValue().await()
                            throw Exception("Ошибка отправки кода подтверждения. Пожалуйста, проверьте ваш email.")
                        }
                    }

                    "verify_code" -> {
                        // Проверка кода подтверждения
                        val email = data.getString("email")
                        val code = data.getString("code")

                        // Получаем данные из Firebase
                        val database = FirebaseDatabase.getInstance()
                        val pendingUserRef = database.getReference("pending_users").child(email.replace(".", ","))
                        val pendingUserSnapshot = pendingUserRef.get().await()

                        if (!pendingUserSnapshot.exists()) {
                            return@withContext Result.failure(Exception("Данные регистрации не найдены"))
                        }

                        val storedCode = pendingUserSnapshot.child("verification_code").getValue(String::class.java)
                        val expiresAt = pendingUserSnapshot.child("expires_at").getValue(Long::class.java) ?: 0

                        // Проверяем срок действия кода
                        if (System.currentTimeMillis() > expiresAt) {
                            return@withContext Result.failure(Exception("Срок действия кода истек"))
                        }

                        // Проверяем код
                        if (code != storedCode) {
                            return@withContext Result.failure(Exception("Неверный код подтверждения"))
                        }

                        // Код верный, создаем пользователя
                        val nickname = pendingUserSnapshot.child("nickname").getValue(String::class.java) ?: ""
                        val passwordHash = pendingUserSnapshot.child("password_hash").getValue(String::class.java) ?: ""
                        val profilePicture = pendingUserSnapshot.child("profile_picture").getValue(String::class.java) ?: ""

                        // Генерируем уникальный ID для нового пользователя
                        val newUserRef = database.getReference("users").push()
                        val userId = newUserRef.key ?:
                        return@withContext Result.failure(Exception("Ошибка создания пользователя"))

                        // Создаем данные пользователя
                        val userData = HashMap<String, Any>().apply {
                            put("email", email)
                            put("password_hash", passwordHash)
                            put("nickname", nickname)
                            put("profile_picture", profilePicture)
                            put("status", "")
                            put("registration_date", System.currentTimeMillis().toString())
                            put("last_login", System.currentTimeMillis().toString())
                            put("last_seen", System.currentTimeMillis())
                            put("is_online", false)
                            put("isBanned", false)
                            put("email_verified", true)
                        }

                        // Сохраняем пользователя
                        newUserRef.setValue(userData).await()

                        // Удаляем временную запись
                        pendingUserRef.removeValue().await()

                        // Возвращаем успешный результат
                        val result = JSONObject().apply {
                            put("success", true)
                            put("message", "Регистрация успешно завершена")
                            put("user_id", userId)
                            put("email", email)
                            put("nickname", nickname)
                        }

                        return@withContext Result.success(result)
                    }

                    "resend_code" -> {
                        // Повторная отправка кода подтверждения
                        val email = data.getString("email")
                        val nickname = data.getString("nickname")

                        // Проверяем существование записи
                        val database = FirebaseDatabase.getInstance()
                        val pendingUserRef = database.getReference("pending_users").child(email.replace(".", ","))
                        val pendingUserSnapshot = pendingUserRef.get().await()

                        if (!pendingUserSnapshot.exists()) {
                            return@withContext Result.failure(Exception("Данные регистрации не найдены"))
                        }

                        // Генерируем новый код подтверждения
                        val verificationCode = (100000..999999).random().toString()

                        // Обновляем код и срок его действия
                        pendingUserRef.child("verification_code").setValue(verificationCode).await()
                        pendingUserRef.child("expires_at").setValue(System.currentTimeMillis() + 3600000).await() // 1 час

                        // Отправляем email с новым кодом
                        val emailSent = EmailSender.sendVerificationEmail(email, nickname, verificationCode)

                        if (emailSent) {
                            val result = JSONObject().apply {
                                put("success", true)
                                put("message", "Код подтверждения отправлен повторно")
                                // В реальном приложении не следует возвращать код клиенту
                                // Это только для тестирования
                                put("verification_code", verificationCode)
                            }

                            return@withContext Result.success(result)
                        } else {
                            throw Exception("Ошибка отправки кода подтверждения. Пожалуйста, проверьте ваш email.")
                        }
                    }

                    else -> {
                        return@withContext Result.failure(Exception("Неизвестное действие"))
                    }
                }
            } catch (e: Exception) {
                Log.e("NetworkHandler", "Ошибка при обработке запроса верификации: ${e.message}", e)
                return@withContext Result.failure(Exception("Ошибка при обработке запроса: ${e.message}"))
            }
        }
    }
}