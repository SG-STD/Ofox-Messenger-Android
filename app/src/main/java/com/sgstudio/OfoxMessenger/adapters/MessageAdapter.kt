package com.sgstudio.OfoxMessenger.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.sgstudio.OfoxMessenger.R
import com.sgstudio.OfoxMessenger.models.ChatMessage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MessageAdapter(private val messages: List<ChatMessage>) :
    RecyclerView.Adapter<MessageAdapter.MessageViewHolder>() {

    class MessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarImageView: ShapeableImageView = view.findViewById(R.id.avatarImageView)
        val nameTextView: TextView = view.findViewById(R.id.nameTextView)
        val messageTextView: TextView = view.findViewById(R.id.messageTextView)
        val timeTextView: TextView = view.findViewById(R.id.timeTextView)
        val statusIndicator: View = view.findViewById(R.id.statusIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        val message = messages[position]
        val context = holder.itemView.context

        // Устанавливаем имя отправителя
        holder.nameTextView.text = message.senderName

        // Устанавливаем текст сообщения
        holder.messageTextView.text = message.text

        // Форматируем и устанавливаем время
        holder.timeTextView.text = formatTime(message.timestamp, context)

        // Устанавливаем индикатор статуса сообщения
        when (message.status) {
            "sent" -> {
                holder.statusIndicator.setBackgroundResource(R.drawable.status_sent)
            }
            "delivered" -> {
                holder.statusIndicator.setBackgroundResource(R.drawable.status_delivered)
            }
            "read" -> {
                holder.statusIndicator.setBackgroundResource(R.drawable.status_read)
            }
            else -> {
                holder.statusIndicator.visibility = View.GONE
            }
        }

        // TODO: Загрузка аватара пользователя с помощью Glide
        // Пока используем стандартный аватар
        holder.avatarImageView.setImageResource(R.drawable.default_avatar)

        // Устанавливаем обработчик нажатия
        holder.itemView.setOnClickListener {
            // TODO: Открытие чата с этим пользователем
        }
    }

    override fun getItemCount() = messages.size

    private fun formatTime(timestamp: Long, context: android.content.Context): String {
        val date = Date(timestamp)
        val now = Calendar.getInstance()
        val messageTime = Calendar.getInstance()
        messageTime.time = date

        return when {
            // Сегодня - показываем только время
            now.get(Calendar.DATE) == messageTime.get(Calendar.DATE) &&
                    now.get(Calendar.MONTH) == messageTime.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR) -> {
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            }
            // Вчера
            now.get(Calendar.DATE) - messageTime.get(Calendar.DATE) == 1 &&
                    now.get(Calendar.MONTH) == messageTime.get(Calendar.MONTH) &&
                    now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR) -> {
                context.getString(R.string.yesterday)
            }
            // В этом году - показываем дату без года
            now.get(Calendar.YEAR) == messageTime.get(Calendar.YEAR) -> {
                SimpleDateFormat("d MMM", Locale.getDefault()).format(date)
            }
            // В другие годы - показываем полную дату
            else -> {
                SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(date)
            }
        }
    }
}