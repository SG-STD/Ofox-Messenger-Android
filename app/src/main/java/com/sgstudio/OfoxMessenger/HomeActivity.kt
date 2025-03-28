package com.sgstudio.OfoxMessenger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.sgstudio.OfoxMessenger.adapters.MessageAdapter
import com.sgstudio.OfoxMessenger.databinding.ActivityHomeBinding
import com.sgstudio.OfoxMessenger.fragments.NewChatBottomSheetFragment
import com.sgstudio.OfoxMessenger.models.ChatMessage
import com.sgstudio.OfoxMessenger.utils.NetworkUtils
import com.sgstudio.OfoxMessenger.utils.SessionManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var messageAdapter: MessageAdapter
    private val chatMessages = mutableListOf<ChatMessage>()
    private val TAG = "HomeActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Инициализация SessionManager
        sessionManager = SessionManager(this)

        // Проверка авторизации
        if (!sessionManager.isLoggedIn()) {
            // Если пользователь не авторизован, перенаправляем на экран входа
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // Настройка интерфейса
        setupUI()

        // Настройка RecyclerView
        setupRecyclerView()

        // Загрузка данных пользователя
        loadUserData()

        // Загрузка последних сообщений
        loadRecentMessages()

        // Настройка обработчиков событий
        setupClickListeners()
    }

    private fun setupUI() {
        // Показываем индикатор загрузки
        showProgress(true)

        // Настройка индикатора онлайн (по умолчанию скрыт)
        binding.onlineIndicator.visibility = View.GONE
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(chatMessages)
        binding.recentMessagesList.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = messageAdapter
        }
    }

    private fun loadUserData() {
        // Получаем данные пользователя из SharedPreferences
        val prefs = getSharedPreferences("user_session", Context.MODE_PRIVATE)
        val userId = prefs.getString("user_id", "") ?: ""
        val nickname = prefs.getString("nickname", "") ?: ""
        val profilePicture = prefs.getString("profile_picture", "") ?: ""
        val status = prefs.getString("status", "") ?: ""

        // Устанавливаем данные пользователя в UI
        binding.userNameText.text = nickname
        binding.userStatusText.text = status

        // Загружаем аватар пользователя
        if (profilePicture.isNotEmpty()) {
            Glide.with(this)
                .load(profilePicture)
                .placeholder(R.drawable.default_avatar)
                .error(R.drawable.default_avatar)
                .into(binding.profileImageView)
        }

        // Проверяем онлайн-статус пользователя
        checkOnlineStatus(userId)
    }

    private fun checkOnlineStatus(userId: String) {
        val database = FirebaseDatabase.getInstance()
        val userRef = database.getReference("users/$userId/last_seen")

        userRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val lastSeen = snapshot.getValue(Long::class.java) ?: 0
                val currentTime = System.currentTimeMillis()

                // Если пользователь был в сети менее 5 минут назад, считаем его онлайн
                val isOnline = (currentTime - lastSeen) < 5 * 60 * 1000

                binding.onlineIndicator.visibility = if (isOnline) View.VISIBLE else View.GONE
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Failed to check online status", error.toException())
            }
        })
    }

    private fun loadRecentMessages() {
        // Проверка подключения к интернету
        if (!NetworkUtils.isNetworkAvailable(this)) {
            showNetworkError(getString(R.string.network_error))
            return
        }

        val database = FirebaseDatabase.getInstance()
        val prefs = getSharedPreferences("user_session", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", "") ?: ""

        // Получаем ссылку на чаты пользователя
        val chatsRef = database.getReference("chats")

        chatsRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                showProgress(false)
                chatMessages.clear()

                if (!snapshot.exists()) {
                    showEmptyState(true)
                    return
                }

                // Перебираем все чаты
                for (chatSnapshot in snapshot.children) {
                    val chatId = chatSnapshot.key ?: continue

                    // Проверяем, принадлежит ли чат текущему пользователю
                    if (chatId.contains(nickname)) {
                        // Получаем последнее сообщение в чате
                        val messagesSnapshot = chatSnapshot.child("messages")
                        if (messagesSnapshot.exists() && messagesSnapshot.childrenCount > 0) {
                            // Берем последнее сообщение (сортировка по timestamp)
                            val lastMessageSnapshot = messagesSnapshot.children.last()

                            val messageId = lastMessageSnapshot.child("messageId").getValue(String::class.java) ?: ""
                            val sender = lastMessageSnapshot.child("sender").getValue(String::class.java) ?: ""
                            val text = lastMessageSnapshot.child("text").getValue(String::class.java) ?: ""
                            val timestamp = lastMessageSnapshot.child("timestamp").getValue(Long::class.java) ?: 0L
                            val status = lastMessageSnapshot.child("status").getValue(String::class.java) ?: "sent"

                            // Определяем имя собеседника (другого участника чата)
                            val otherUser = chatId.replace(nickname, "").replace("-", "")

                            // Создаем объект сообщения
                            val chatMessage = ChatMessage(
                                messageId = messageId,
                                chatId = chatId,
                                sender = sender,
                                text = text,
                                timestamp = timestamp,
                                status = status,
                                senderName = if (sender == nickname) nickname else otherUser
                            )

                            // Добавляем сообщение в список
                            chatMessages.add(chatMessage)
                        }
                    }
                }

                // Сортируем сообщения по времени (новые сверху)
                chatMessages.sortByDescending { it.timestamp }

                // Обновляем адаптер
                messageAdapter.notifyDataSetChanged()

                // Показываем пустое состояние, если нет сообщений
                showEmptyState(chatMessages.isEmpty())
            }

            override fun onCancelled(error: DatabaseError) {
                showProgress(false)
                Log.e(TAG, "Failed to load recent messages", error.toException())
                showSnackbar("Ошибка при загрузке сообщений")
            }
        })
    }

    // В методе setupClickListeners() обновим обработчик для кнопки нового сообщения
    private fun setupClickListeners() {
        // Обработчик кнопки настроек
        binding.settingsButton.setOnClickListener {
            // TODO: Переход на экран настроек
            showSnackbar(getString(R.string.coming_soon))
        }

        // Обработчик кнопки выхода
        binding.logoutButton.setOnClickListener {
            showLogoutConfirmationDialog()
        }

        // Обработчик кнопки нового сообщения
        binding.newMessageButton.setOnClickListener {
            showNewChatBottomSheet()
        }

        // Обработчик кнопки повтора при ошибке сети
        binding.retryButton.setOnClickListener {
            hideNetworkError()
            loadRecentMessages()
        }
    }

    // Добавим новый метод для показа BottomSheet
    private fun showNewChatBottomSheet() {
        val bottomSheetFragment = NewChatBottomSheetFragment.newInstance()
        bottomSheetFragment.show(supportFragmentManager, NewChatBottomSheetFragment.TAG)
    }

    private fun showLogoutConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.logout)
            .setMessage(R.string.logout_confirmation)
            .setPositiveButton(R.string.yes) { _, _ ->
                logout()
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    private fun logout() {
        // Очищаем данные сессии
        sessionManager.clearSession()

        // Перенаправляем на экран входа
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showProgress(show: Boolean) {
        binding.progressBar.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showEmptyState(show: Boolean) {
        binding.emptyStateLayout.visibility = if (show) View.VISIBLE else View.GONE
        binding.recentMessagesList.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.homeRoot, message, Snackbar.LENGTH_LONG).show()
    }

    private fun showNetworkError(message: String) {
        binding.networkErrorCard.visibility = View.VISIBLE
    }

    private fun hideNetworkError() {
        binding.networkErrorCard.visibility = View.GONE
    }
}