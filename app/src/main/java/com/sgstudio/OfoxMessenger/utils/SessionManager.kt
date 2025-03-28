package com.sgstudio.OfoxMessenger.utils

import android.content.Context
import android.content.SharedPreferences

class SessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_session", Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = prefs.edit()

    companion object {
        private const val IS_LOGGED_IN = "is_logged_in"
        private const val USER_ID = "user_id"
        private const val NICKNAME = "nickname"
        private const val PROFILE_PICTURE = "profile_picture"
        private const val STATUS = "status"
        private const val LAST_LOGIN = "last_login"
        private const val AUTH_TOKEN = "auth_token"
    }

    fun setLoggedIn(isLoggedIn: Boolean) {
        editor.putBoolean(IS_LOGGED_IN, isLoggedIn)
        editor.apply()
    }

    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(IS_LOGGED_IN, false)
    }

    // Сохранение данных пользователя
    fun saveUserData(userId: String, nickname: String, profilePicture: String, status: String) {
        editor.putString(USER_ID, userId)
        editor.putString(NICKNAME, nickname)
        editor.putString(PROFILE_PICTURE, profilePicture)
        editor.putString(STATUS, status)
        editor.putLong(LAST_LOGIN, System.currentTimeMillis())
        editor.apply()
    }

    // Сохранение токена авторизации
    fun saveAuthToken(token: String) {
        editor.putString(AUTH_TOKEN, token)
        editor.apply()
    }

    // Получение ID пользователя
    fun getUserId(): String? {
        return prefs.getString(USER_ID, null)
    }

    // Получение никнейма пользователя
    fun getNickname(): String? {
        return prefs.getString(NICKNAME, null)
    }

    // Получение URL аватара пользователя
    fun getProfilePicture(): String? {
        return prefs.getString(PROFILE_PICTURE, null)
    }

    // Получение статуса пользователя
    fun getStatus(): String? {
        return prefs.getString(STATUS, null)
    }

    // Получение времени последнего входа
    fun getLastLogin(): Long {
        return prefs.getLong(LAST_LOGIN, 0)
    }

    // Получение токена авторизации
    fun getAuthToken(): String? {
        return prefs.getString(AUTH_TOKEN, null)
    }

    // Добавьте этот метод для очистки данных сессии
    fun clearSession() {
        editor.clear()
        editor.apply()
    }
}