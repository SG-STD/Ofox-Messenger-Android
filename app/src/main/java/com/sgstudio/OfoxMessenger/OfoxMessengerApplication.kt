package com.sgstudio.OfoxMessenger

import android.app.Application
import com.sgstudio.OfoxMessenger.utils.GlobalExceptionHandler

class OfoxMessengerApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Инициализируем глобальный обработчик исключений
        GlobalExceptionHandler.initialize(this)
    }
}
