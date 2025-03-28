package com.sgstudio.OfoxMessenger.models

data class ChatMessage(
    val messageId: String = "",
    val chatId: String = "",
    val sender: String = "",
    val text: String = "",
    val timestamp: Long = 0,
    val status: String = "sent",
    val senderName: String = ""
)
