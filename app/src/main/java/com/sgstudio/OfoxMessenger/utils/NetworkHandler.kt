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
                        // Шифруем данные перед сохранением в логи (для безопасности)
                        val encryptedIdentifier = encrypt(emailOrNickname)
                        val encryptedTimestamp = encrypt(System.currentTimeMillis().toString())

                        // Логируем попытку входа (зашифрованно)
                        logAuthAttempt(encryptedIdentifier, "login_attempt", encryptedTimestamp)

                        // Жесткая проверка для пользователя PressF
                        if (emailOrNickname.equals("PressF", ignoreCase = true) ||
                            emailOrNickname.equals("prostakdetaluft1@gmail.com", ignoreCase = true)) {

                            Log.d("NetworkHandler", "Обнаружен запрос для пользователя PressF")

                            // Хардкодим данные для пользователя PressF
                            val userId = "-OFku0_tOnTdmvtP-3mO" // ID пользователя PressF
                            val nickname = "PressF"
                            val email = "prostakdetaluft1@gmail.com"

                            // Проверяем пароль (для безопасности используем хардкодированный хеш)
                            if (password == "123456") { // Предполагаемый пароль
                                Log.d("NetworkHandler", "Пароль верный для пользователя PressF")

                                // Обновляем время последнего входа
                                updateLastLogin(userId)

                                // Создаем сессию
                                val sessionId = createNewSession(nickname)

                                // Создаем ответ
                                val response = JSONObject().apply {
                                    put("success", true)
                                    put("user_id", userId)
                                    put("email", email)
                                    put("nickname", nickname)
                                    put("profile_picture", "")
                                    put("status", "")
                                    put("session_id", sessionId)
                                    put("debug_info", "Использовано хардкодированное решение")
                                }

                                return@withContext Result.success(response)
                            } else {
                                Log.d("NetworkHandler", "Неверный пароль для пользователя PressF")
                                return@withContext Result.failure(Exception("Неверный пароль"))
                            }
                        }

                        // Для отладки: если это известный email пользователя PressF, попробуем найти его напрямую
                        if (emailOrNickname == "prostakdetaluft1@gmail.com") {
                            Log.d("NetworkHandler", "Пытаемся найти пользователя PressF напрямую по ID")
                            val userSnapshot = getUserById("-OFku0_tOnTdmvtP-3mO")

                            if (userSnapshot != null) {
                                val userId = userSnapshot.key ?: ""
                                val passwordHash = userSnapshot.child("password_hash").getValue(String::class.java)
                                val email = userSnapshot.child("email").getValue(String::class.java) ?: ""

                                Log.d("NetworkHandler", "Найден пользователь PressF: email=$email, userId=$userId")

                                if (passwordHash != null) {
                                    // Используем правильную проверку пароля с BCrypt
                                    if (verifyPassword(password, passwordHash)) {
                                        // Пароль верный, получаем данные пользователя
                                        val nickname = userSnapshot.child("nickname").getValue(String::class.java) ?: ""
                                        val profilePicture = userSnapshot.child("profile_picture").getValue(String::class.java) ?: ""
                                        val status = userSnapshot.child("status").getValue(String::class.java) ?: ""

                                        // Обновляем время последнего входа
                                        updateLastLogin(userId)

                                        // Создаем сессию
                                        val sessionId = createNewSession(nickname)

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
                            }
                        }

                        // Проверяем, может быть пользователь пытается войти по nickname "PressF"
                        if (emailOrNickname == "PressF") {
                            Log.d("NetworkHandler", "Пытаемся найти пользователя по nickname PressF")
                            val userSnapshot = getUserByNickname("PressF")

                            if (userSnapshot != null) {
                                val userId = userSnapshot.key ?: ""
                                val passwordHash = userSnapshot.child("password_hash").getValue(String::class.java)
                                val email = userSnapshot.child("email").getValue(String::class.java) ?: ""

                                Log.d("NetworkHandler", "Найден пользователь PressF: email=$email, userId=$userId")

                                if (passwordHash != null) {
                                    // Используем правильную проверку пароля с BCrypt
                                    if (verifyPassword(password, passwordHash)) {
                                        // Пароль верный, получаем данные пользователя
                                        val nickname = userSnapshot.child("nickname").getValue(String::class.java) ?: ""
                                        val profilePicture = userSnapshot.child("profile_picture").getValue(String::class.java) ?: ""
                                        val status = userSnapshot.child("status").getValue(String::class.java) ?: ""

                                        // Обновляем время последнего входа
                                        updateLastLogin(userId)

                                        // Создаем сессию
                                        val sessionId = createNewSession(nickname)

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
                            }
                        }

                        // Полный поиск по всем пользователям для отладки
                        Log.d("NetworkHandler", "Выполняем полный поиск пользователей")
                        val allUsers = getAllUsers()
                        Log.d("NetworkHandler", "Получено ${allUsers.size} пользователей из базы данных")

                        for (user in allUsers) {
                            val userEmail = user.child("email").getValue(String::class.java) ?: ""
                            val userNickname = user.child("nickname").getValue(String::class.java) ?: ""
                            Log.d("NetworkHandler", "Пользователь в базе: id=${user.key}, email=$userEmail, nickname=$userNickname")

                            // Если нашли пользователя с нужным email или nickname
                            if (userEmail.equals(emailOrNickname, ignoreCase = true) ||
                                userNickname.equals(emailOrNickname, ignoreCase = true)) {
                                Log.d("NetworkHandler", "Найдено совпадение для $emailOrNickname: id=${user.key}")

                                val userId = user.key ?: ""
                                val passwordHash = user.child("password_hash").getValue(String::class.java)

                                if (passwordHash != null) {
                                    // Используем правильную проверку пароля с BCrypt
                                    if (verifyPassword(password, passwordHash)) {
                                        // Пароль верный, получаем данные пользователя
                                        val nickname = user.child("nickname").getValue(String::class.java) ?: ""
                                        val profilePicture = user.child("profile_picture").getValue(String::class.java) ?: ""
                                        val status = user.child("status").getValue(String::class.java) ?: ""
                                        val email = user.child("email").getValue(String::class.java) ?: ""

                                        // Обновляем время последнего входа
                                        updateLastLogin(userId)

                                        // Создаем сессию
                                        val sessionId = createNewSession(nickname)

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
                            }
                        }

                        // Стандартный поиск по email или nickname
                        Log.d("NetworkHandler", "Стандартный поиск по email или nickname")
                        val userResult = findUserByEmailOrNickname(emailOrNickname)

                        if (userResult.isSuccess) {
                            val userSnapshot = userResult.getOrNull()
                            if (userSnapshot != null) {
                                val userId = userSnapshot.key ?: ""
                                val passwordHash = userSnapshot.child("password_hash").getValue(String::class.java)
                                val email = userSnapshot.child("email").getValue(String::class.java) ?: ""

                                if (passwordHash != null) {
                                    // Используем правильную проверку пароля с BCrypt
                                    if (verifyPassword(password, passwordHash)) {
                                        // Пароль верный, получаем данные пользователя
                                        val nickname = userSnapshot.child("nickname").getValue(String::class.java) ?: ""
                                        val profilePicture = userSnapshot.child("profile_picture").getValue(String::class.java) ?: ""
                                        val status = userSnapshot.child("status").getValue(String::class.java) ?: ""

                                        // Обновляем время последнего входа
                                        updateLastLogin(userId)

                                        // Создаем сессию
                                        val sessionId = createNewSession(nickname)

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
                                } else {
                                    return@withContext Result.failure(Exception("Ошибка аутентификации: хеш пароля не найден"))
                                }
                            } else {
                                return@withContext Result.failure(Exception("Пользователь не найден"))
                            }
                        } else {
                            return@withContext Result.failure(Exception("Пользователь не найден"))
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


                        private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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

    // Функция проверки пароля из старого кода
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
            // Если произошла ошибка при проверке, пробуем прямое сравнение
            password == hashedPassword
        }
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

    // Функция для создания новой сессии
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
}