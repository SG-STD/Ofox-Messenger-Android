package com.sgstudio.OfoxMessenger

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Находим элементы для анимации
        val logoContainer = findViewById<CardView>(R.id.logoContainer)
        val logoImageView = findViewById<ImageView>(R.id.logoImageView)
        val appNameTextView = findViewById<TextView>(R.id.appNameTextView)
        val sloganTextView = findViewById<TextView>(R.id.sloganTextView)
        val studioTextView = findViewById<TextView>(R.id.studioTextView)

        // Начальное состояние - невидимые элементы
        logoContainer.alpha = 0f
        logoContainer.scaleX = 0.5f
        logoContainer.scaleY = 0.5f
        appNameTextView.alpha = 0f
        appNameTextView.translationY = 50f
        sloganTextView.alpha = 0f
        sloganTextView.translationY = 50f
        studioTextView.alpha = 0f

        // Анимация для логотипа - появление и масштабирование
        val logoFadeIn = ObjectAnimator.ofFloat(logoContainer, View.ALPHA, 0f, 1f)
        logoFadeIn.duration = 500

        val logoScaleX = ObjectAnimator.ofFloat(logoContainer, View.SCALE_X, 0.5f, 1f)
        logoScaleX.duration = 700
        logoScaleX.interpolator = AnticipateOvershootInterpolator()

        val logoScaleY = ObjectAnimator.ofFloat(logoContainer, View.SCALE_Y, 0.5f, 1f)
        logoScaleY.duration = 700
        logoScaleY.interpolator = AnticipateOvershootInterpolator()

        // Анимация для названия приложения
        val appNameFadeIn = ObjectAnimator.ofFloat(appNameTextView, View.ALPHA, 0f, 1f)
        appNameFadeIn.duration = 500
        appNameFadeIn.startDelay = 300

        val appNameTranslateY = ObjectAnimator.ofFloat(appNameTextView, View.TRANSLATION_Y, 50f, 0f)
        appNameTranslateY.duration = 700
        appNameTranslateY.startDelay = 300
        appNameTranslateY.interpolator = AccelerateDecelerateInterpolator()

        // Анимация для слогана
        val sloganFadeIn = ObjectAnimator.ofFloat(sloganTextView, View.ALPHA, 0f, 1f)
        sloganFadeIn.duration = 500
        sloganFadeIn.startDelay = 500

        val sloganTranslateY = ObjectAnimator.ofFloat(sloganTextView, View.TRANSLATION_Y, 50f, 0f)
        sloganTranslateY.duration = 700
        sloganTranslateY.startDelay = 500
        sloganTranslateY.interpolator = AccelerateDecelerateInterpolator()

        // Анимация для информации о студии
        val studioFadeIn = ObjectAnimator.ofFloat(studioTextView, View.ALPHA, 0f, 1f)
        studioFadeIn.duration = 500
        studioFadeIn.startDelay = 1000

        // Запускаем все анимации
        val animatorSet = AnimatorSet()
        animatorSet.playTogether(
            logoFadeIn, logoScaleX, logoScaleY,
            appNameFadeIn, appNameTranslateY,
            sloganFadeIn, sloganTranslateY,
            studioFadeIn
        )
        animatorSet.start()

        // Анимация пульсации логотипа вместо вращения
        startPulseAnimation(logoImageView)

        // Задержка перед переходом на MainActivity
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            finish()
        }, 3500) // 3.5 секунды
    }

    // Функция для создания эффекта пульсации
    private fun startPulseAnimation(view: View) {
        // Первая пульсация
        val scaleXUp1 = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.15f)
        scaleXUp1.duration = 800
        scaleXUp1.interpolator = DecelerateInterpolator()

        val scaleYUp1 = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.15f)
        scaleYUp1.duration = 800
        scaleYUp1.interpolator = DecelerateInterpolator()

        val scaleXDown1 = ObjectAnimator.ofFloat(view, View.SCALE_X, 1.15f, 1f)
        scaleXDown1.duration = 800
        scaleXDown1.interpolator = DecelerateInterpolator()

        val scaleYDown1 = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.15f, 1f)
        scaleYDown1.duration = 800
        scaleYDown1.interpolator = DecelerateInterpolator()

        // Вторая пульсация
        val scaleXUp2 = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.15f)
        scaleXUp2.duration = 800
        scaleXUp2.interpolator = DecelerateInterpolator()

        val scaleYUp2 = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.15f)
        scaleYUp2.duration = 800
        scaleYUp2.interpolator = DecelerateInterpolator()

        val scaleXDown2 = ObjectAnimator.ofFloat(view, View.SCALE_X, 1.15f, 1f)
        scaleXDown2.duration = 800
        scaleXDown2.interpolator = DecelerateInterpolator()

        val scaleYDown2 = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1.15f, 1f)
        scaleYDown2.duration = 800
        scaleYDown2.interpolator = DecelerateInterpolator()

        // Объединяем анимации в последовательность
        val pulseSequence = AnimatorSet()
        pulseSequence.playSequentially(
            AnimatorSet().apply { playTogether(scaleXUp1, scaleYUp1) },
            AnimatorSet().apply { playTogether(scaleXDown1, scaleYDown1) },
            AnimatorSet().apply { playTogether(scaleXUp2, scaleYUp2) },
            AnimatorSet().apply { playTogether(scaleXDown2, scaleYDown2) }
        )

        // Добавляем небольшую задержку перед началом пульсации
        pulseSequence.startDelay = 500
        pulseSequence.start()
    }
}