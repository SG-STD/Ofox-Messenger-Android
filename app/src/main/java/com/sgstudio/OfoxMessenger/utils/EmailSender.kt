package com.sgstudio.OfoxMessenger.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class EmailSender {
    companion object {
        private const val TAG = "EmailSender"
        
        // Настройки SMTP-сервера для greenchat.kz
        private const val SMTP_HOST = "greenchat.kz"
        private const val SMTP_PORT = "465"
        private const val EMAIL_FROM = "OfoxMessenger@greenchat.kz"
        private const val EMAIL_PASSWORD = "Lexa_Greb_2008" // Замените на ваш пароль
        
        suspend fun sendVerificationEmail(
            toEmail: String,
            nickname: String,
            verificationCode: String
        ): Boolean = withContext(Dispatchers.IO) {
            try {
                val props = Properties().apply {
                    put("mail.smtp.host", SMTP_HOST)
                    put("mail.smtp.socketFactory.port", SMTP_PORT)
                    put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                    put("mail.smtp.auth", "true")
                    put("mail.smtp.port", SMTP_PORT)
                }
                
                val session = Session.getInstance(props, object : Authenticator() {
                    override fun getPasswordAuthentication(): PasswordAuthentication {
                        return PasswordAuthentication(EMAIL_FROM, EMAIL_PASSWORD)
                    }
                })
                
                val message = MimeMessage(session).apply {
                    setFrom(InternetAddress(EMAIL_FROM, "Ofox Messenger"))
                    addRecipient(Message.RecipientType.TO, InternetAddress(toEmail))
                    subject = "Код подтверждения для Ofox Messenger"
                    
                    // HTML-содержимое письма
                    val htmlContent = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta charset="UTF-8">
                            <title>Код подтверждения для Ofox Messenger</title>
                            <style>
                                body {
                                    font-family: Arial, sans-serif;
                                    margin: 0;
                                    padding: 0;
                                    background-color: #f5f5f5;
                                }
                                .container {
                                    max-width: 600px;
                                    margin: 0 auto;
                                    padding: 20px;
                                }
                                .header {
                                    background: linear-gradient(135deg, #FF9800, #FF5722);
                                    color: white;
                                    padding: 30px;
                                    text-align: center;
                                    border-radius: 10px 10px 0 0;
                                }
                                .content {
                                    background-color: white;
                                    padding: 30px;
                                    border-radius: 0 0 10px 10px;
                                    box-shadow: 0 2px 10px rgba(0,0,0,0.1);
                                }
                                .code {
                                    font-size: 32px;
                                    font-weight: bold;
                                    letter-spacing: 5px;
                                    text-align: center;
                                    margin: 20px 0;
                                    color: #FF5722;
                                }
                                .footer {
                                    text-align: center;
                                    margin-top: 20px;
                                    color: #757575;
                                    font-size: 12px;
                                }
                            </style>
                        </head>
                        <body>
                            <div class="container">
                                <div class="header">
                                    <h1>Ofox Messenger</h1>
                                </div>
                                <div class="content">
                                    <h2>Здравствуйте, $nickname!</h2>
                                    <p>Благодарим вас за регистрацию в Ofox Messenger. Для завершения регистрации, пожалуйста, введите следующий код подтверждения в приложении:</p>
                                    <div class="code">$verificationCode</div>
                                    <p>Код действителен в течение 1 часа.</p>
                                    <p>Если вы не регистрировались в Ofox Messenger, просто проигнорируйте это письмо.</p>
                                </div>
                                <div class="footer">
                                    <p>© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} Ofox Messenger. Все права защищены.</p>
                                </div>
                            </div>
                        </body>
                        </html>
                    """.trimIndent()
                    
                    // Устанавливаем HTML-содержимое
                    setContent(htmlContent, "text/html; charset=utf-8")
                }
                
                // Отправляем письмо
                Transport.send(message)
                Log.d(TAG, "Email sent successfully to $toEmail")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send email", e)
                return@withContext false
            }
        }
    }
}
