package com.sgstudio.OfoxMessenger

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.sgstudio.OfoxMessenger.databinding.ActivityChatBinding

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private var chatId: String = ""
    private var otherUser: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Получаем параметры из Intent
        chatId = intent.getStringExtra("CHAT_ID") ?: ""
        otherUser = intent.getStringExtra("OTHER_USER") ?: ""

        if (chatId.isEmpty() || otherUser.isEmpty()) {
            Toast.makeText(this, "Ошибка: неверные параметры чата", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Настраиваем заголовок
        binding.titleTextView.text = otherUser

        // Настраиваем кнопку назад
        binding.backButton.setOnClickListener {
            finish()
        }

        // Показываем сообщение о том, что функционал в разработке
        Toast.makeText(this, getString(R.string.coming_soon), Toast.LENGTH_SHORT).show()
    }
}
