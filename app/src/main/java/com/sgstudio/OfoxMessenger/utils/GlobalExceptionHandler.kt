package com.sgstudio.OfoxMessenger.utils

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import com.sgstudio.OfoxMessenger.ErrorHandlerActivity
import java.io.PrintWriter
import java.io.StringWriter

class GlobalExceptionHandler private constructor(
    private val application: Application,
    private val defaultExceptionHandler: Thread.UncaughtExceptionHandler,
    private val activityToBeLaunched: Class<out Activity>
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        try {
            // Получаем полный стек-трейс ошибки
            val stackTrace = StringWriter()
            exception.printStackTrace(PrintWriter(stackTrace))
            val errorLog = stackTrace.toString()
            
            Log.e(TAG, "Uncaught exception in thread ${thread.name}", exception)

            // Создаем Intent для запуска активности с отчетом об ошибке
            val intent = Intent(application, activityToBeLaunched).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or 
                        Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("exception", exception)
                putExtra("thread_name", thread.name)
                putExtra("error_log", errorLog)
            }
            
            // Запускаем активность
            application.startActivity(intent)
            
            // Завершаем текущий поток
            Process.killProcess(Process.myPid())
            System.exit(1)
            
        } catch (e: Exception) {
            // Если что-то пошло не так в нашем обработчике, 
            // используем стандартный обработчик
            Log.e(TAG, "Error in custom exception handler", e)
            defaultExceptionHandler.uncaughtException(thread, exception)
        }
    }

    companion object {
        private const val TAG = "GlobalExceptionHandler"

        fun initialize(application: Application) {
            // Сохраняем текущий обработчик исключений
            val defaultExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
            
            // Устанавливаем наш обработчик
            val handler = GlobalExceptionHandler(
                application,
                defaultExceptionHandler ?: Thread.getDefaultUncaughtExceptionHandler(),
                ErrorHandlerActivity::class.java
            )
            
            Thread.setDefaultUncaughtExceptionHandler(handler)
            Log.i(TAG, "Global exception handler installed")
        }
    }
}
