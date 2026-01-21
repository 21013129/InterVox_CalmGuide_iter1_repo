package hu.intervox.calmguide

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.RawRes
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.max
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private enum class Lang { HUN, ENG }

    private lateinit var root: FrameLayout
    private lateinit var logoView: ImageView
    private lateinit var brandText: TextView

    private val handler = Handler(Looper.getMainLooper())

    private var lang: Lang = Lang.HUN

    private var isInteractive: Boolean = false
    private var isClosing: Boolean = false

    private var mediaPlayer: MediaPlayer? = null

    // Touch handling for 2s long-press exit.
    private var downTimeMs: Long = 0L
    private var longPressTriggered: Boolean = false
    private val longPressRunnable = Runnable {
        if (!isInteractive || isClosing) return@Runnable
        longPressTriggered = true
        beginExitFlow()
    }

    // Sequence scheduling.
    private var scheduledNext: Runnable? = null
    private var mainSequenceRunning: Boolean = false

    // Animation state.
    private var cornerTx: Float = 0f
    private var cornerTy: Float = 0f
    private var smallScale: Float = 0.34f

    // --- Tunables (safe defaults) ---
    private val introLogoFadeInMs = 1500L
    private val introLogoCenterHoldMs = 3333L // spec says "~333"; kept as 3.333s, easy to adjust
    private val introMoveToCornerMs = 1000L
    private val introTextFadeInMs = 1500L
    private val introBgSkyToDarkMs = 1500L

    private val greetingAfterDelayMs = 1000L
    private val langSwitchRestartGapMs = 666L

    private val seqGapShortMs = 1333L
    private val seqGapLongMs = 2666L

    private val requiredLongPressMs = 2000L
    private val exitVibrateMs = 250L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen awake.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        root = findViewById(R.id.root)
        logoView = findViewById(R.id.logoView)
        brandText = findViewById(R.id.brandText)

        // Consume all touch input and interpret as tap/long press.
        root.setOnTouchListener { _, event ->
            handleTouch(event)
            true
        }

        // Prepare initial visual state.
        setBackgroundColor(getColorCompat(R.color.intervox_white))
        logoView.alpha = 0f
        logoView.translationX = 0f
        logoView.translationY = 0f
        logoView.scaleX = 1f
        logoView.scaleY = 1f
        brandText.alpha = 0f

        // Compute corner translation once layout is ready.
        root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)

                // Use top-left pivot so scaling doesn't change top-left reference.
                logoView.pivotX = 0f
                logoView.pivotY = 0f

                val margin = dp(12f)
                cornerTx = margin - logoView.left.toFloat()
                cornerTy = margin - logoView.top.toFloat()

                // Place brand text roughly aligned with the logo's final position.
                // We'll adjust it right before fading in.
                positionBrandTextForCorner()

                startIntroAnimation()
                playGreetingAudio()
            }
        })
    }

    override fun onStop() {
        super.onStop()
        // If the app is backgrounded, stop audio and scheduled steps.
        stopAll()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun stopAll() {
        handler.removeCallbacks(longPressRunnable)
        scheduledNext?.let { handler.removeCallbacks(it) }
        scheduledNext = null
        mainSequenceRunning = false
        stopAudio()
    }

    private fun handleTouch(event: MotionEvent) {
        if (isClosing) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTimeMs = System.currentTimeMillis()
                longPressTriggered = false
                if (isInteractive) {
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, requiredLongPressMs)
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)

                if (!isInteractive) return
                if (longPressTriggered) return

                // Short tap: toggle language and restart main sequence.
                toggleLangAndRestart()
            }
        }
    }

    private fun toggleLangAndRestart() {
        lang = if (lang == Lang.HUN) Lang.ENG else Lang.HUN
        restartMainSequence(langSwitchRestartGapMs)
    }

    // --- Intro / Outro visuals ---

    private fun startIntroAnimation() {
        isInteractive = false

        val white = getColorCompat(R.color.intervox_white)
        val sky = getColorCompat(R.color.intervox_sky_blue)
        val dark = getColorCompat(R.color.intervox_dark_blue)

        val bgToSky = colorAnimator(from = white, to = sky, durationMs = introMoveToCornerMs)

        val logoFadeIn = ObjectAnimator.ofFloat(logoView, View.ALPHA, 0f, 1f).apply {
            duration = introLogoFadeInMs
        }

        // Hold: use a "dummy" animator for sequencing.
        val hold = ValueAnimator.ofInt(0, 0).apply {
            duration = introLogoCenterHoldMs
        }

        val moveToCorner = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logoView, View.TRANSLATION_X, 0f, cornerTx),
                ObjectAnimator.ofFloat(logoView, View.TRANSLATION_Y, 0f, cornerTy),
                ObjectAnimator.ofFloat(logoView, View.SCALE_X, 1f, smallScale),
                ObjectAnimator.ofFloat(logoView, View.SCALE_Y, 1f, smallScale),
                bgToSky
            )
            duration = introMoveToCornerMs
        }

        val fadeInText = ObjectAnimator.ofFloat(brandText, View.ALPHA, 0f, 1f).apply {
            duration = introTextFadeInMs
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    positionBrandTextForCorner()
                }
            })
        }

        val bgSkyToDark = colorAnimator(from = sky, to = dark, durationMs = introBgSkyToDarkMs).apply {
            // Start from the middle of the text fade-in.
            startDelay = introTextFadeInMs / 2
        }

        val textAndBg = AnimatorSet().apply {
            playTogether(fadeInText, bgSkyToDark)
        }

        AnimatorSet().apply {
            playSequentially(logoFadeIn, hold, moveToCorner, textAndBg)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Now interactions are allowed.
                    isInteractive = true
                }
            })
            start()
        }
    }

    private fun beginExitFlow() {
        if (isClosing) return
        isClosing = true
        isInteractive = false

        // Stop any ongoing loop and current audio, then haptic feedback.
        scheduledNext?.let { handler.removeCallbacks(it) }
        scheduledNext = null
        mainSequenceRunning = false
        stopAudio()
        vibrate(exitVibrateMs)

        val closingRes = if (lang == Lang.HUN) R.raw.i5hun else R.raw.i5eng

        var audioDone = false
        var animDone = false

        fun maybeFinish() {
            if (audioDone && animDone) terminate()
        }

        val durationMs = playRaw(
            resId = closingRes,
            onCompletion = {
                audioDone = true
                maybeFinish()
            }
        ).let { if (it > 0) it.toLong() else 16000L }

        startExitAnimation(totalDurationMs = durationMs, onEnd = {
            animDone = true
            maybeFinish()
        })
    }

    private fun startExitAnimation(totalDurationMs: Long, onEnd: () -> Unit) {
        val white = getColorCompat(R.color.intervox_white)
        val sky = getColorCompat(R.color.intervox_sky_blue)
        val dark = getColorCompat(R.color.intervox_dark_blue)

        // Base timeline (reverse of intro):
        // 1) Dark -> Sky while text fades out
        // 2) Move logo to center while Sky -> White
        // 3) Hold at center
        // 4) Fade out logo
        val baseD1 = introTextFadeInMs
        val baseD2 = introMoveToCornerMs
        val baseHold = introLogoCenterHoldMs
        val baseD4 = introLogoFadeInMs
        val baseTotal = baseD1 + baseD2 + baseHold + baseD4

        val scale = max(1f, totalDurationMs.toFloat() / baseTotal.toFloat())
        val d1 = (baseD1 * scale).toLong()
        val d2 = (baseD2 * scale).toLong()
        val dHold = (baseHold * scale).toLong()
        val d4 = (baseD4 * scale).toLong()

        val textFadeOut = ObjectAnimator.ofFloat(brandText, View.ALPHA, brandText.alpha, 0f).apply {
            duration = d1
        }

        val bgDarkToSky = colorAnimator(from = dark, to = sky, durationMs = d1)

        val stage1 = AnimatorSet().apply {
            playTogether(textFadeOut, bgDarkToSky)
        }

        val bgSkyToWhite = colorAnimator(from = sky, to = white, durationMs = d2)

        val moveLogoToCenter = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(logoView, View.TRANSLATION_X, logoView.translationX, 0f),
                ObjectAnimator.ofFloat(logoView, View.TRANSLATION_Y, logoView.translationY, 0f),
                ObjectAnimator.ofFloat(logoView, View.SCALE_X, logoView.scaleX, 1f),
                ObjectAnimator.ofFloat(logoView, View.SCALE_Y, logoView.scaleY, 1f),
                bgSkyToWhite
            )
            duration = d2
        }

        val hold = ValueAnimator.ofInt(0, 0).apply {
            duration = dHold
        }

        val logoFadeOut = ObjectAnimator.ofFloat(logoView, View.ALPHA, logoView.alpha, 0f).apply {
            duration = d4
        }

        AnimatorSet().apply {
            playSequentially(stage1, moveLogoToCenter, hold, logoFadeOut)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    setBackgroundColor(white)
                    onEnd()
                }
            })
            start()
        }
    }

    private fun positionBrandTextForCorner() {
        // Place text to the right of the (scaled) logo when the logo is in the corner.
        // We set absolute X/Y since the logo moves via translation.
        brandText.post {
            val margin = dp(12f)
            val gap = dp(10f)

            val logoLeft = logoView.left.toFloat() + cornerTx
            val logoTop = logoView.top.toFloat() + cornerTy
            val logoW = logoView.width.toFloat() * smallScale
            val logoH = logoView.height.toFloat() * smallScale

            val x = logoLeft + logoW + gap
            val y = logoTop + max(0f, (logoH - brandText.height.toFloat()) / 2f)

            brandText.x = x
            brandText.y = y

            // Ensure it stays within the screen with a minimal margin.
            if (brandText.x < margin) brandText.x = margin
            if (brandText.y < margin) brandText.y = margin
        }
    }

    // --- Audio playback & sequencing ---

    private fun playGreetingAudio() {
        playRaw(R.raw.i1all) {
            // After greeting ends, wait 1s and begin main sequence (if not already started).
            handler.postDelayed(
                {
                    if (!isClosing && !mainSequenceRunning) {
                        restartMainSequence(startDelayMs = 0L)
                    }
                },
                greetingAfterDelayMs
            )
        }
    }

    private fun restartMainSequence(startDelayMs: Long) {
        if (isClosing) return

        // Stop current audio, cancel next step, then start from the beginning.
        scheduledNext?.let { handler.removeCallbacks(it) }
        scheduledNext = null
        stopAudio()

        mainSequenceRunning = true

        val runnable = Runnable {
            if (isClosing) return@Runnable
            playSequenceIndex(0)
        }
        scheduledNext = runnable
        handler.postDelayed(runnable, startDelayMs)
    }

    private fun playSequenceIndex(index: Int) {
        if (isClosing || !mainSequenceRunning) return

        val sequence = if (lang == Lang.HUN) {
            intArrayOf(R.raw.i2hun, R.raw.i3hun, R.raw.i4hun)
        } else {
            intArrayOf(R.raw.i2eng, R.raw.i3eng, R.raw.i4eng)
        }

        val resId = sequence[index]
        playRaw(resId) {
            if (isClosing || !mainSequenceRunning) return@playRaw

            val nextIndex = (index + 1) % sequence.size
            val gap = when (index) {
                0 -> seqGapShortMs
                1 -> seqGapShortMs
                else -> seqGapLongMs
            }

            val runnable = Runnable {
                playSequenceIndex(nextIndex)
            }
            scheduledNext = runnable
            handler.postDelayed(runnable, gap)
        }
    }

    private fun playRaw(@RawRes resId: Int, onCompletion: (() -> Unit)? = null): Int {
        stopAudio()

        val mp = try {
            MediaPlayer.create(this, resId)
        } catch (t: Throwable) {
            null
        }

        if (mp == null) {
            onCompletion?.invoke()
            return 0
        }

        mediaPlayer = mp

        mp.setOnCompletionListener {
            // Release ASAP to avoid holding resources.
            stopAudio()
            onCompletion?.invoke()
        }

        try {
            mp.start()
        } catch (t: Throwable) {
            stopAudio()
            onCompletion?.invoke()
        }

        return try {
            mp.duration
        } catch (_: Throwable) {
            0
        }
    }

    private fun stopAudio() {
        val mp = mediaPlayer ?: return
        mediaPlayer = null
        try {
            mp.setOnCompletionListener(null)
            if (mp.isPlaying) mp.stop()
        } catch (_: Throwable) {
            // ignore
        } finally {
            try {
                mp.release()
            } catch (_: Throwable) {
                // ignore
            }
        }
    }

    // --- Utils ---

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Throwable) {
            // ignore
        }
    }

    private fun dp(value: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
    }

    @ColorInt
    private fun getColorCompat(colorRes: Int): Int {
        @Suppress("DEPRECATION")
        return resources.getColor(colorRes)
    }

    private fun setBackgroundColor(@ColorInt color: Int) {
        root.setBackgroundColor(color)
    }

    private fun colorAnimator(@ColorInt from: Int, @ColorInt to: Int, durationMs: Long): ValueAnimator {
        return ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = durationMs
            addUpdateListener { anim ->
                val c = anim.animatedValue as Int
                setBackgroundColor(c)
            }
        }
    }

    private fun terminate() {
        stopAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
        // Ensure the process exits to match "self-close" expectation.
        handler.postDelayed({
            try {
                exitProcess(0)
            } catch (_: Throwable) {
                // ignore
            }
        }, 200L)
    }
}
