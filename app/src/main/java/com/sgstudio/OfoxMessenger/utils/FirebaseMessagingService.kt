package com.sgstudio.OfoxMessenger.utils

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        
        // Сохраняем токен локально
        val prefs = getSharedPreferences("fcm_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
        
        // Если пользователь авторизован, обновляем токен в базе данных
        val auth = FirebaseAuth.getInstance()
        val currentUser = auth.currentUser
        
        if (currentUser != null) {
            val database = FirebaseDatabase.getInstance()
            val userRef = database.getReference("users/${currentUser.uid}")
            userRef.child("fcm_token").setValue(token)
        }
    }
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        // Обработка входящих сообщений
        // TODO: Реализовать показ уведомлений
    }
}
