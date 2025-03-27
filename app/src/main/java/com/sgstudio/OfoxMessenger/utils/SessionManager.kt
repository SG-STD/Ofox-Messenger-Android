package com.sgstudio.OfoxMessenger.utils

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class SessionManager(private val context: Context) {
    
    private val prefs = context.getSharedPreferences("user_session", Context.MODE_PRIVATE)
    private val auth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance()
    
    fun isLoggedIn(): Boolean {
        return auth.currentUser != null
    }
    
    fun getUserId(): String? {
        return auth.currentUser?.uid
    }
    
    fun getUserNickname(): String {
        return prefs.getString("nickname", "") ?: ""
    }
    
    fun getUserProfilePicture(): String {
        return prefs.getString("profile_picture", "") ?: ""
    }
    
    fun getUserStatus(): String {
        return prefs.getString("status", "") ?: ""
    }
    
    fun updateUserStatus(newStatus: String) {
        val userId = getUserId() ?: return
        
        // Обновляем статус в Firebase
        val userRef = database.getReference("users/$userId")
        userRef.child("status").setValue(newStatus)
        
        // Обновляем локальные данные
        prefs.edit().putString("status", newStatus).apply()
    }

    fun refreshAuthIfNeeded() {
        val user = auth.currentUser ?: return

        // Проверяем, когда последний раз обновлялся токен
        val lastTokenRefresh = prefs.getLong("last_token_refresh", 0)
        val currentTime = System.currentTimeMillis()

        // Если прошло больше 1 часа, обновляем токен
        if (currentTime - lastTokenRefresh > 3600000) {
            user.getIdToken(true)
                .addOnSuccessListener { result ->
                    // Токен успешно обновлен
                    prefs.edit().putLong("last_token_refresh", currentTime).apply()
                }
                .addOnFailureListener { exception ->
                    // Если не удалось обновить токен, выходим из аккаунта
                    if (exception.message?.contains("auth credential is incorrect") == true) {
                        logout()
                    }
                }
        }
    }

    fun logout() {
        // Обновляем статус "офлайн" в Firebase
        val userId = getUserId()
        if (userId != null) {
            val userRef = database.getReference("users/$userId")
            userRef.child("last_seen").setValue(System.currentTimeMillis())
        }
        
        // Выходим из Firebase Auth
        auth.signOut()
        
        // Очищаем локальные данные
        prefs.edit().clear().apply()
    }
}
