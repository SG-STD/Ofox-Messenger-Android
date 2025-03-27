package com.sgstudio.OfoxMessenger.utils

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mindrot.jbcrypt.BCrypt
import kotlin.coroutines.resume

class CustomAuthManager {
    private val database = FirebaseDatabase.getInstance()
    
    suspend fun login(email: String, password: String): Result<String> = suspendCancellableCoroutine { continuation ->
        val usersRef = database.getReference("users")
        
        // Ищем пользователя по email
        usersRef.orderByChild("email").equalTo(email).limitToFirst(1)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        // Нашли пользователя
                        val userId = snapshot.children.first().key ?: ""
                        val userSnapshot = snapshot.children.first()
                        
                        // Получаем хеш пароля
                        val passwordHash = userSnapshot.child("password_hash").getValue(String::class.java)
                        
                        if (passwordHash != null) {
                            // Проверяем пароль с помощью BCrypt
                            val passwordMatches = BCrypt.checkpw(password, passwordHash)
                            
                            if (passwordMatches) {
                                // Пароль верный
                                continuation.resume(Result.success(userId))
                            } else {
                                // Пароль неверный
                                continuation.resume(Result.failure(Exception("Неверный пароль")))
                            }
                        } else {
                            continuation.resume(Result.failure(Exception("Ошибка аутентификации: хеш пароля не найден")))
                        }
                    } else {
                        // Пользователь не найден
                        continuation.resume(Result.failure(Exception("Пользователь с таким email не найден")))
                    }
                }
                
                override fun onCancelled(error: DatabaseError) {
                    continuation.resume(Result.failure(Exception("Ошибка базы данных: ${error.message}")))
                }
            })
    }
}
