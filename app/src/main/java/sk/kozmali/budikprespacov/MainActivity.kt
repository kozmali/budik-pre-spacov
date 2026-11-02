package sk.kozmali.budikprespacov

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.media.audiofx.LoudnessEnhancer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Html
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// --- SLEDOVANIE POHYBU TELEFÓNU PRE VYNECHANIE 5. LÍNIE (AK UŽ POUŽÍVATEĽ KRÁČA / HÝBE SA S TELEFÓNOM) ---
object WakeUpMotionTracker : SensorEventListener {
    private var sensorManager: SensorManager? = null
    private var isMonitoring = false
    private var lastSampleTimeMs = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var hasInitialReading = false

    @Volatile
    private var activeMotionCount = 0
    @Volatile
    private var tiltedOrHeldCount = 0
    @Volatile
    private var recentMotionTimestampMs = 0L
    @Volatile
    private var isCurrentlyFlatStill = true

    fun startMonitoring(context: Context) {
        try {
            stopMonitoring()
            val appCtx = context.applicationContext
            val sm = appCtx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return

            activeMotionCount = 0
            tiltedOrHeldCount = 0
            recentMotionTimestampMs = 0L
            lastSampleTimeMs = 0L
            hasInitialReading = false
            isCurrentlyFlatStill = true
            sensorManager = sm
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
            isMonitoring = true
        } catch (_: Exception) {}
    }

    fun stopMonitoring() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {}
        sensorManager = null
        isMonitoring = false
    }

    fun isUserConfirmedAwakeByMotion(): Boolean {
        if (!isMonitoring) return false
        val now = SystemClock.elapsedRealtime()
        val movedRecently = (now - recentMotionTimestampMs) < 35_000L
        return (activeMotionCount >= 14 && !isCurrentlyFlatStill && movedRecently) ||
                (activeMotionCount >= 22 && tiltedOrHeldCount >= 12)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSampleTimeMs < 350L) return
        lastSampleTimeMs = now

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!hasInitialReading) {
            lastX = x
            lastY = y
            lastZ = z
            hasInitialReading = true
            return
        }

        val delta = abs(x - lastX) + abs(y - lastY) + abs(z - lastZ)
        lastX = x
        lastY = y
        lastZ = z

        val horizontalTilt = sqrt(x * x + y * y)
        val isLyingFlat = abs(z) > 8.4f && horizontalTilt < 2.6f

        if (delta > 0.85f) {
            activeMotionCount++
            recentMotionTimestampMs = now
            isCurrentlyFlatStill = false
        } else if (!isLyingFlat && horizontalTilt >= 3.2f) {
            tiltedOrHeldCount++
            isCurrentlyFlatStill = false
        } else if (isLyingFlat && delta < 0.25f) {
            isCurrentlyFlatStill = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

// --- PREKLAD SPOTIFY ODKAZU NA PRESNÚ ŠTÚDIOVÚ SKLADBU NA POZADÍ (OD 0:00, NEZÁVISLE OD DENNÉHO POČÚVANIA SPOTIFY) ---
data class ResolvedSpotifyTrack(
    val displayTitle: String,
    val youtubeVideoId: String
)

fun normalizeSpotifyWebUrl(input: String): String {
    val clean = input.trim()
    val webRegex = "spotify\\.com/(?:intl-[a-zA-Z-]+/)?(track|album|playlist|episode|show)/([a-zA-Z0-9]+)".toRegex()
    val webMatch = webRegex.find(clean)
    if (webMatch != null) {
        return "https://open.spotify.com/${webMatch.groupValues[1]}/${webMatch.groupValues[2]}"
    }
    val uriRegex = "spotify:(track|album|playlist|episode|show):([a-zA-Z0-9]+)".toRegex()
    val uriMatch = uriRegex.find(clean)
    if (uriMatch != null) {
        return "https://open.spotify.com/${uriMatch.groupValues[1]}/${uriMatch.groupValues[2]}"
    }
    return clean
}

private fun fetchUrlTextWithBrowserAgent(urlStr: String, useDesktopAgent: Boolean = false): String {
    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 5000
        instanceFollowRedirects = true
        setRequestProperty(
            "User-Agent",
            if (useDesktopAgent) {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
            } else {
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            }
        )
        setRequestProperty("Accept-Language", "sk-SK,sk;q=0.9,en-US;q=0.8,en;q=0.7")
        setRequestProperty(
            "Cookie",
            "CONSENT=YES+cb.20230531-04-p0.en+FX+908; SOCS=CAISHAgBEhJnd3NfMjAyMzA4MTVfMF9SQzIaAmVuIAEaBgiAo_CmBg"
        )
    }
    return try {
        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private fun decodeHtmlEntities(raw: String): String {
    return try {
        Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    } catch (_: Exception) {
        raw.trim()
    }
}

private fun extractFirstVideoIdFromHtml(html: String): String? {
    val patterns = listOf(
        Regex(""""videoRenderer"\s*:\s*\{\s*"videoId"\s*:\s*"([a-zA-Z0-9_-]{11})""""),
        Regex(""""videoId"\s*:\s*"([a-zA-Z0-9_-]{11})""""),
        Regex("""\\x22videoId\\x22\s*:\s*\\x22([a-zA-Z0-9_-]{11})\\x22"""),
        Regex("""\\\"videoId\\\"\s*:\s*\\\"([a-zA-Z0-9_-]{11})\\\""""),
        Regex("""/watch\?v=([a-zA-Z0-9_-]{11})""")
    )
    for (regex in patterns) {
        val id = regex.find(html)?.groupValues?.get(1)
        if (!id.isNullOrBlank()) return id
    }
    return null
}

fun resolveSpotifyTrackToBackgroundStream(spotifyRawInput: String): ResolvedSpotifyTrack? {
    val webUrl = normalizeSpotifyWebUrl(spotifyRawInput)
    if (!webUrl.startsWith("http")) return null

    var trackTitle = ""
    var artistName = ""

    // 1. Získanie názvu skladby a interpreta zo stránky Spotify
    try {
        val html = fetchUrlTextWithBrowserAgent(webUrl, useDesktopAgent = false)
        val ogTitle = Regex("""<meta\s+property=["']og:title["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta\s+content=["']([^"']+)["']\s+property=["']og:title["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)

        val ogDesc = Regex("""<meta\s+property=["']og:description["']\s+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?: Regex("""<meta\s+content=["']([^"']+)["']\s+property=["']og:description["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)

        if (!ogTitle.isNullOrBlank()) {
            trackTitle = decodeHtmlEntities(ogTitle)
        }
        if (!ogDesc.isNullOrBlank()) {
            val cleanDesc = decodeHtmlEntities(ogDesc)
                .replace(Regex("""^Listen to .+ on Spotify\.\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^Vypočujte si .+ na Spotify\.\s*""", RegexOption.IGNORE_CASE), "")
            val firstPart = cleanDesc.split("·", "•").firstOrNull()?.trim() ?: ""
            if (firstPart.isNotBlank() && !firstPart.equals("Song", ignoreCase = true)) {
                artistName = firstPart
            }
        }

        if (trackTitle.isBlank()) {
            val rawPageTitle = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1) ?: ""
            val cleaned = decodeHtmlEntities(rawPageTitle)
                .replace(" | Spotify", "")
                .replace("- song and lyrics by ", " - ")
                .replace("- song by ", " - ")
                .trim()
            if (cleaned.isNotBlank() && !cleaned.equals("Spotify", ignoreCase = true)) {
                trackTitle = cleaned
            }
        }
    } catch (_: Exception) {}

    // 2. Záložné oEmbed API, ak by meta tagy neobsahovali názov
    if (trackTitle.isBlank()) {
        try {
            val oembedUrl = "https://open.spotify.com/oembed?url=" + URLEncoder.encode(webUrl, "UTF-8")
            val json = fetchUrlTextWithBrowserAgent(oembedUrl, useDesktopAgent = true)
            val titleMatch = Regex(""""title"\s*:\s*"((?:\\.|[^"\\])*)"""").find(json)?.groupValues?.get(1)
            if (!titleMatch.isNullOrBlank()) {
                trackTitle = decodeHtmlEntities(titleMatch.replace("\\\"", "\""))
            }
        } catch (_: Exception) {}
    }

    if (trackTitle.isBlank()) return null

    val fullDisplay = if (artistName.isNotBlank() && !trackTitle.contains(artistName, ignoreCase = true)) {
        "$artistName – $trackTitle"
    } else {
        trackTitle
    }

    // 3. Vyhľadanie presnej štúdiovej audio stopy (Official Audio) pre prehrávanie od 0:00 na pozadí
    var foundVideoId = ""
    try {
        val searchQuery = "$fullDisplay official audio"
        val ytSearchUrl = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(searchQuery, "UTF-8") + "&hl=en"
        val searchHtml = fetchUrlTextWithBrowserAgent(ytSearchUrl, useDesktopAgent = true)
        foundVideoId = extractFirstVideoIdFromHtml(searchHtml) ?: ""
    } catch (_: Exception) {}

    if (foundVideoId.isBlank()) {
        try {
            val searchQuery = fullDisplay
            val ytMobileUrl = "https://m.youtube.com/results?search_query=" + URLEncoder.encode(searchQuery, "UTF-8")
            val mobileHtml = fetchUrlTextWithBrowserAgent(ytMobileUrl, useDesktopAgent = false)
            foundVideoId = extractFirstVideoIdFromHtml(mobileHtml) ?: ""
        } catch (_: Exception) {}
    }

    return ResolvedSpotifyTrack(displayTitle = fullDisplay, youtubeVideoId = foundVideoId)
}

// --- DEFINÍCIA 14 PREDVOLENÝCH FARIEB A KOMPLEMENTÁRNYCH PÁROV ---
data class PaletteColor(
    val key: String,
    val nameSk: String,
    val nameEn: String,
    val color: Color,
    val recommendedSecondaryKey: String
)

val AppColorPalette = listOf(
    PaletteColor("LIGHT_BLUE", "Svetlomodrá", "Light Blue", Color(0xFF38BDF8), "ORANGE"),
    PaletteColor("BLUE", "Modrá", "Blue", Color(0xFF4F7CFF), "RED"),
    PaletteColor("TURQUOISE", "Tyrkysová", "Turquoise", Color(0xFF14B8A6), "RED"),
    PaletteColor("GREEN", "Zelená", "Green", Color(0xFF22C55E), "RED"),
    PaletteColor("DARK_GREEN", "Tmavozelená", "Dark Green", Color(0xFF15803D), "ORANGE"),
    PaletteColor("YELLOW", "Žltá", "Yellow", Color(0xFFFACC15), "PURPLE"),
    PaletteColor("PURPLE", "Fialová", "Purple", Color(0xFFA855F7), "YELLOW"),
    PaletteColor("PINK", "Ružová", "Pink", Color(0xFFEC4899), "TURQUOISE"),
    PaletteColor("BROWN", "Hnedá", "Brown", Color(0xFF8B5A2B), "LIGHT_BLUE"),
    PaletteColor("RED", "Červená", "Red", Color(0xFFEF4444), "BLUE"),
    PaletteColor("ORANGE", "Oranžová", "Orange", Color(0xFFF97316), "BLUE"),
    PaletteColor("GRAY", "Sivá", "Gray", Color(0xFF64748B), "ORANGE"),
    PaletteColor("WHITE", "Biela", "White", Color(0xFFF8FAFC), "RED"),
    PaletteColor("BLACK", "Čierna", "Black", Color(0xFF111827), "YELLOW")
)

fun getPaletteColor(key: String, defaultKey: String = "BLUE"): PaletteColor {
    return AppColorPalette.find { it.key == key }
        ?: AppColorPalette.first { it.key == defaultKey }
}

fun contentColorForBg(bg: Color): Color {
    return if (bg.luminance() > 0.45f) Color(0xFF0B0F17) else Color(0xFFF8FAFC)
}

fun subtleClockColorForBg(bg: Color): Color {
    return if (bg.luminance() > 0.45f) {
        Color(0xFF0B0F17).copy(alpha = 0.78f)
    } else {
        Color(0xFFE2E8F0).copy(alpha = 0.85f)
    }
}

fun formatTimeNoLeadingZero(hour: Int, min: Int): String {
    return String.format(Locale.getDefault(), "%d:%02d", hour, min)
}

fun resolveInitialLanguage(prefs: android.content.SharedPreferences): String {
    if (prefs.getBoolean("app_lang_manually_set", false)) {
        return prefs.getString("app_lang", "SK") ?: "SK"
    }
    val sysLang = Locale.getDefault().language.lowercase(Locale.ROOT)
    return if (sysLang == "sk" || sysLang == "cs") "SK" else "EN"
}

fun isInternetAvailable(context: Context): Boolean {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
        true
    }
}

fun generateFarRandomJump(
    currentX: Int,
    currentY: Int,
    maxOffsetX: Int,
    maxOffsetY: Int
): Pair<Int, Int> {
    val safeMaxX = maxOffsetX.coerceAtLeast(45)
    val safeMaxY = maxOffsetY.coerceAtLeast(65)
    val minJumpDistance = (hypot(safeMaxX.toDouble(), safeMaxY.toDouble()) * 0.75).coerceAtLeast(110.0)

    var bestCandidate = -currentX to -currentY
    var maxFoundDist = 0.0

    for (attempt in 0 until 25) {
        val targetSignX = if (currentX == 0) (if (Random.nextBoolean()) 1 else -1) else -currentX.coerceIn(-1, 1)
        val targetSignY = if (currentY == 0) (if (Random.nextBoolean()) 1 else -1) else -currentY.coerceIn(-1, 1)

        val candX = if (attempt < 15) {
            targetSignX * Random.nextInt((safeMaxX * 0.35).toInt().coerceAtLeast(20), safeMaxX + 1)
        } else {
            Random.nextInt(-safeMaxX, safeMaxX + 1)
        }
        val candY = if (attempt < 15) {
            targetSignY * Random.nextInt((safeMaxY * 0.35).toInt().coerceAtLeast(30), safeMaxY + 1)
        } else {
            Random.nextInt(-safeMaxY, safeMaxY + 1)
        }

        val dist = hypot((candX - currentX).toDouble(), (candY - currentY).toDouble())
        if (dist > maxFoundDist) {
            maxFoundDist = dist
            bestCandidate = candX to candY
        }
        if (dist >= minJumpDistance) {
            return candX to candY
        }
    }
    return bestCandidate
}

// --- HAPTICKÁ ODOZVA A ODSTUPŇOVANÉ VIBRÁCIE (5 % AŽ 100 %) ---
fun getSystemVibrator(context: Context): Vibrator? {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (_: Exception) {
        null
    }
}

fun performUiHaptic(context: Context, isLightTick: Boolean = false) {
    try {
        val vibrator = getSystemVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effectId = if (isLightTick) VibrationEffect.EFFECT_TICK else VibrationEffect.EFFECT_CLICK
            vibrator.vibrate(VibrationEffect.createPredefined(effectId))
        } else {
            val duration = if (isLightTick) 10L else 20L
            val amplitude = if (isLightTick) 60 else 120
            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
        }
    } catch (_: Exception) {}
}

fun buildScaledVibrationWaveform(strengthPercent: Int, repeatIndex: Int): VibrationEffect {
    val norm = (strengthPercent.coerceIn(5, 100) / 100.0)
    val amp = (norm.pow(2.2) * 253.0 + 2.0).roundToInt().coerceIn(2, 255)

    return when {
        strengthPercent <= 25 -> {
            val onMs = (14 + (strengthPercent - 5).coerceAtLeast(0) * 0.8).toLong()
            VibrationEffect.createWaveform(
                longArrayOf(0, onMs, 110, onMs, 500),
                intArrayOf(0, amp, 0, amp, 0),
                repeatIndex
            )
        }
        strengthPercent <= 55 -> {
            val onMs = (35 + (strengthPercent - 25) * 1.8).toLong()
            VibrationEffect.createWaveform(
                longArrayOf(0, onMs, 95, onMs, 450),
                intArrayOf(0, amp, 0, amp, 0),
                repeatIndex
            )
        }
        strengthPercent <= 80 -> {
            val onMs = (110 + (strengthPercent - 55) * 4.0).toLong()
            VibrationEffect.createWaveform(
                longArrayOf(0, onMs, 85, onMs, 420),
                intArrayOf(0, amp, 0, amp, 0),
                repeatIndex
            )
        }
        else -> {
            val onMs = (240 + (strengthPercent - 80) * 9.0).toLong()
            VibrationEffect.createWaveform(
                longArrayOf(0, onMs, 70, onMs, 350),
                intArrayOf(0, amp, 0, amp, 0),
                repeatIndex
            )
        }
    }
}

fun previewAlarmVibration(context: Context, strengthPercent: Int) {
    try {
        val vibrator = getSystemVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.cancel()
        val effect = buildScaledVibrationWaveform(strengthPercent, repeatIndex = -1)
        vibrator.vibrate(effect)
    } catch (_: Exception) {}
}

fun startContinuousAlarmVibration(context: Context, strengthPercent: Int) {
    try {
        val vibrator = getSystemVibrator(context) ?: return
        if (!vibrator.hasVibrator()) return
        vibrator.cancel()
        val effect = buildScaledVibrationWaveform(strengthPercent, repeatIndex = 0)
        vibrator.vibrate(effect)
    } catch (_: Exception) {}
}

fun stopAlarmVibration(context: Context) {
    try {
        getSystemVibrator(context)?.cancel()
    } catch (_: Exception) {}
}

// --- GENEROVANIE SKUTOČNÝCH BUDÍKOVÝCH ZVUKOV PRE PREDVOLENÝ 2 A 3 ---
fun getOrCreateSynthesizedAlarmUri(context: Context, typeKey: String): Uri {
    val fileName = if (typeKey == "DEFAULT_2") "gentle_morning_melody.wav" else "brisk_digital_alarm.wav"
    val file = File(context.cacheDir, fileName)
    if (file.exists() && file.length() > 1000L) {
        return Uri.fromFile(file)
    }

    try {
        val sampleRate = 22050
        val durationSec = 2.4
        val numSamples = (sampleRate * durationSec).toInt()
        val pcmData = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            if (typeKey == "DEFAULT_2") {
                val noteIndex = ((t / 0.45).toInt()).coerceIn(0, 4)
                val noteStart = noteIndex * 0.45
                val localT = t - noteStart
                val freq = when (noteIndex) {
                    0 -> 523.25
                    1 -> 659.25
                    2 -> 783.99
                    3 -> 1046.50
                    else -> 0.0
                }
                if (freq == 0.0 || localT < 0.0) {
                    pcmData[i] = 0
                } else {
                    val env = exp(-localT * 4.5) * (if (localT < 0.015) localT / 0.015 else 1.0)
                    val wave = 0.75 * sin(2.0 * PI * freq * localT) + 0.25 * sin(2.0 * PI * (freq * 2.0) * localT)
                    pcmData[i] = (wave * env * Short.MAX_VALUE * 0.85).toInt().toShort()
                }
            } else {
                val cycleT = t % 1.2
                val isBeep = (cycleT in 0.0..0.09) || (cycleT in 0.14..0.23) || (cycleT in 0.28..0.37) || (cycleT in 0.42..0.54)
                if (!isBeep) {
                    pcmData[i] = 0
                } else {
                    val freq = 1046.50
                    val wave = 0.8 * sin(2.0 * PI * freq * t) + 0.2 * sin(2.0 * PI * (freq * 3.0) * t)
                    pcmData[i] = (wave * Short.MAX_VALUE * 0.85).toInt().toShort()
                }
            }
        }

        val byteRate = sampleRate * 2
        val dataSize = numSamples * 2
        val totalSize = 36 + dataSize
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(totalSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize)

        FileOutputStream(file).use { fos ->
            fos.write(header.array())
            val pcmBytes = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcmData) {
                pcmBytes.putShort(s)
            }
            fos.write(pcmBytes.array())
        }
        return Uri.fromFile(file)
    } catch (_: Exception) {
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
    }
}

fun isAlarmStillToday(alarmHour: Int, alarmMin: Int): Boolean {
    val now = Calendar.getInstance()
    val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    val alarmMinutes = alarmHour * 60 + alarmMin
    return alarmMinutes > currentMinutes
}

fun getNextSnoozeFormattedTime(snoozeMinutes: Int): String {
    val cal = Calendar.getInstance().apply {
        add(Calendar.MINUTE, snoozeMinutes)
    }
    return formatTimeNoLeadingZero(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
}

fun getMinutesUntilAlarm(alarmHour: Int, alarmMin: Int): Int {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, alarmHour)
        set(Calendar.MINUTE, alarmMin)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        if (!after(now)) {
            add(Calendar.DAY_OF_YEAR, 1)
        }
    }
    val diffMillis = target.timeInMillis - now.timeInMillis
    return ((diffMillis + 59999L) / 60000L).toInt().coerceIn(1, 1439)
}

fun formatAlarmStartsInLabel(totalMinutes: Int, lang: String): String {
    val clamped = totalMinutes.coerceIn(1, 1439)
    val h = clamped / 60
    val m = clamped % 60
    return if (lang == "EN") {
        when {
            h > 0 && m > 0 -> "Alarm will ring in ${h}\u00A0h and ${m}\u00A0min"
            h > 0 -> "Alarm will ring in ${h}\u00A0h"
            else -> "Alarm will ring in ${m}\u00A0min"
        }
    } else {
        when {
            h > 0 && m > 0 -> "Budík začne zvoniť o ${h}\u00A0h a ${m}\u00A0min"
            h > 0 -> "Budík začne zvoniť o ${h}\u00A0h"
            else -> "Budík začne zvoniť o ${m}\u00A0min"
        }
    }
}

fun extractYouTubeVideoId(url: String): String? {
    val patterns = listOf(
        "youtu\\.be/([a-zA-Z0-9_-]{11})",
        "v=([a-zA-Z0-9_-]{11})",
        "shorts/([a-zA-Z0-9_-]{11})",
        "embed/([a-zA-Z0-9_-]{11})"
    )
    for (pattern in patterns) {
        val match = Regex(pattern).find(url)
        if (match != null) return match.groupValues[1]
    }
    return null
}

fun calculateTimeUntilAlarmText(hour: Int, min: Int, lang: String): String {
    val totalMinutes = getMinutesUntilAlarm(hour, min)
    val diffHours = totalMinutes / 60
    val diffMins = totalMinutes % 60
    val formattedTime = formatTimeNoLeadingZero(hour, min)

    return if (lang == "EN") {
        when {
            diffHours > 0 && diffMins > 0 -> "Alarm set for $formattedTime\nRings in ${diffHours}\u00A0h and ${diffMins}\u00A0min"
            diffHours > 0 -> "Alarm set for $formattedTime\nRings in ${diffHours}\u00A0h"
            else -> "Alarm set for $formattedTime\nRings in ${diffMins}\u00A0min"
        }
    } else {
        when {
            diffHours > 0 && diffMins > 0 -> "Budík nastavený na $formattedTime\nZazvoní o ${diffHours}\u00A0hod a ${diffMins}\u00A0min"
            diffHours > 0 -> "Budík nastavený na $formattedTime\nZazvoní o ${diffHours}\u00A0hod"
            else -> "Budík nastavený na $formattedTime\nZazvoní o ${diffMins}\u00A0min"
        }
    }
}

fun formatFailsafeExceededText(exceededMins: Int, lang: String): String {
    val mins = exceededMins.coerceAtLeast(1)
    return if (lang == "EN") {
        "You are ${mins}\u00A0min late!"
    } else {
        val minWord = when (mins) {
            1 -> "minútu"
            2, 3, 4 -> "minúty"
            else -> "minút"
        }
        "Meškáš ${mins}\u00A0$minWord!"
    }
}

private val emergencyBeepLock = Any()
@Volatile
private var activeEmergencyTrack: AudioTrack? = null
@Volatile
private var isEmergencyBeepCancelled = false

fun stopEmergencyBeepImmediately() {
    isEmergencyBeepCancelled = true
    try {
        val track = activeEmergencyTrack
        activeEmergencyTrack = null
        track?.pause()
        track?.flush()
        track?.stop()
        track?.release()
    } catch (_: Exception) {}
}

fun playEmergencyBeepOnce(failsafeVolumePercent: Int, soundType: Int, extraBoostPercent: Int = 0) {
    isEmergencyBeepCancelled = false
    synchronized(emergencyBeepLock) {
        if (isEmergencyBeepCancelled) return
        try {
            activeEmergencyTrack?.pause()
            activeEmergencyTrack?.flush()
            activeEmergencyTrack?.stop()
            activeEmergencyTrack?.release()
        } catch (_: Exception) {}
        activeEmergencyTrack = null

        val effectivePct = (failsafeVolumePercent + extraBoostPercent).coerceIn(10, 100)
        val volScale = (effectivePct / 100.0).pow(1.35).coerceIn(0.06, 1.0)

        var audioTrack: AudioTrack? = null
        var enhancer: LoudnessEnhancer? = null
        try {
            val sampleRate = 22050
            val durationMs = when (soundType) {
                1 -> 440
                2 -> 550
                5 -> 680
                else -> 620
            }
            val numSamples = (sampleRate * durationMs) / 1000
            val buffer = ShortArray(numSamples)

            for (i in 0 until numSamples) {
                val t = i.toDouble() / sampleRate
                var localBeepStart = 0.0
                var localBeepEnd = 0.0
                val freq: Double = when (soundType) {
                    1 -> {
                        when {
                            t < 0.068 -> { localBeepStart = 0.0; localBeepEnd = 0.068; 1319.0 }
                            t < 0.132 -> 0.0
                            t < 0.200 -> { localBeepStart = 0.132; localBeepEnd = 0.200; 1319.0 }
                            t < 0.264 -> 0.0
                            t < 0.332 -> { localBeepStart = 0.264; localBeepEnd = 0.332; 1319.0 }
                            else -> 0.0
                        }
                    }
                    2 -> if (t < 0.52) { localBeepStart = 0.0; localBeepEnd = 0.52; 840.0 } else 0.0
                    3 -> when {
                        t < 0.09 -> { localBeepStart = 0.0; localBeepEnd = 0.09; 1080.0 }
                        t < 0.15 -> 0.0
                        t < 0.24 -> { localBeepStart = 0.15; localBeepEnd = 0.24; 1080.0 }
                        t < 0.30 -> 0.0
                        t < 0.39 -> { localBeepStart = 0.30; localBeepEnd = 0.39; 1080.0 }
                        t < 0.45 -> 0.0
                        t < 0.56 -> { localBeepStart = 0.45; localBeepEnd = 0.56; 1080.0 }
                        else -> 0.0
                    }
                    4 -> when {
                        t < 0.20 -> { localBeepStart = 0.0; localBeepEnd = 0.20; 800.0 }
                        t < 0.30 -> 0.0
                        t < 0.52 -> { localBeepStart = 0.30; localBeepEnd = 0.52; 800.0 }
                        else -> 0.0
                    }
                    else -> when {
                        t < 0.13 -> { localBeepStart = 0.0; localBeepEnd = 0.13; 780.0 }
                        t < 0.16 -> 0.0
                        t < 0.29 -> { localBeepStart = 0.16; localBeepEnd = 0.29; 600.0 }
                        t < 0.34 -> 0.0
                        t < 0.47 -> { localBeepStart = 0.34; localBeepEnd = 0.47; 780.0 }
                        t < 0.50 -> 0.0
                        t < 0.65 -> { localBeepStart = 0.50; localBeepEnd = 0.65; 600.0 }
                        else -> 0.0
                    }
                }

                if (freq == 0.0) {
                    buffer[i] = 0
                } else {
                    val ramp = 0.006
                    val envIn = ((t - localBeepStart) / ramp).coerceIn(0.0, 1.0)
                    val envOut = ((localBeepEnd - t) / ramp).coerceIn(0.0, 1.0)
                    val env = minOf(envIn, envOut)

                    val rawWave = if (soundType == 1) {
                        0.88 * sin(2.0 * PI * freq * t) + 0.12 * sin(2.0 * PI * (freq * 2.0) * t)
                    } else {
                        0.72 * sin(2.0 * PI * freq * t) +
                                0.28 * sin(2.0 * PI * (freq * 2.0) * t) +
                                0.18 * sin(2.0 * PI * (freq * 3.0) * t)
                    }
                    val normalizedWave = (rawWave * env).coerceIn(-1.0, 1.0)
                    buffer[i] = (normalizedWave * volScale * Short.MAX_VALUE).toInt().toShort()
                }
            }

            if (isEmergencyBeepCancelled) return

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(buffer.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            activeEmergencyTrack = audioTrack

            try {
                enhancer = LoudnessEnhancer(audioTrack.audioSessionId)
                val boostMb = ((effectivePct - 35).coerceAtLeast(0) * 22) + (extraBoostPercent.coerceAtLeast(0) * 18)
                enhancer.setTargetGain(boostMb)
                enhancer.enabled = true
            } catch (_: Exception) {}

            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.setVolume(volScale.toFloat())
            audioTrack.play()

            val totalWaitMs = durationMs.toLong() + 30L
            var waitedMs = 0L
            while (waitedMs < totalWaitMs && !isEmergencyBeepCancelled) {
                Thread.sleep(20L)
                waitedMs += 20L
            }
        } catch (_: Exception) {
        } finally {
            try { enhancer?.release() } catch (_: Exception) {}
            try {
                audioTrack?.pause()
                audioTrack?.flush()
                audioTrack?.stop()
                audioTrack?.release()
            } catch (_: Exception) {}
            if (activeEmergencyTrack === audioTrack) {
                activeEmergencyTrack = null
            }
        }
    }
}

fun playGentleWakeCheckPromptBeep() {
    try {
        val tg = ToneGenerator(AudioManager.STREAM_ALARM, 45)
        tg.startTone(ToneGenerator.TONE_PROP_BEEP, 220)
        Thread.sleep(250L)
        tg.release()
    } catch (_: Exception) {}
}

// --- LOKALIZÁCIA S NEZLOMITEĽNÝMI MEDZERAMI (\u00A0) ---
data class AppStrings(
    val appName: String,
    val statusActiveTodayBadge: String,
    val statusActiveTomorrowBadge: String,
    val statusOffBadge: String,
    val permTitle: String,
    val permDesc: String,
    val permBtn1: String,
    val permBtn2: String,
    val permRestrictedHint: String,
    val permOpenAppInfoBtn: String,
    val permRefresh: String,
    val dailyAlarmTitle: String,
    val changeAlarmBtn: String,
    val sleepDurationDialogTitle: String,
    val sleepDurationDialogDesc: String,
    val targetWakeTimeLabel: String,
    val targetWakeTimeSubtext: String,
    val snoozeTitle: String,
    val minUnit: String,
    val volumeTitle: String,
    val volumePreviewHint: String,
    val gradualVolumeTitle: String,
    val gradualVolumeDesc: String,
    val lowAlarmVolumeWarning: String,
    val lowFailsafeVolumeWarning: String,
    val failsafeTitle: String,
    val failsafeShortDesc: String,
    val failsafeInfoDialogTitle: String,
    val failsafeInfoDialogBody: String,
    val changeFailsafeBtn: String,
    val failsafeOffsetSettingMenu: String,
    val failsafeOffsetDialogTitle: String,
    val failsafeOffsetDialogDesc: String,
    val failsafeSoundMenu: String,
    val failsafeSoundDialogTitle: String,
    val failsafeBeepVolumeLabel: String,
    val failsafeBeepOptions: List<Pair<Int, String>>,
    val vibrationMenuLabel: String,
    val vibrationDialogTitle: String,
    val vibrationEnableToggleLabel: String,
    val vibrationStrengthDesc: String,
    val soundTitle: String,
    val stopPreview: String,
    val soundDef1: String,
    val soundDef2: String,
    val soundDef3: String,
    val soundLocal: String,
    val soundSpotify: String,
    val soundYoutube: String,
    val openMusicFolder: String,
    val selectedFile: String,
    val previewLocalBtn: String,
    val previewYoutubeBtn: String,
    val previewSpotifyBtn: String,
    val stopExternalPreviewBtn: String,
    val spotifyShareInstruction: String,
    val youtubeShareInstruction: String,
    val openYoutubeBtn: String,
    val openSpotifyBtn: String,
    val pasteClipboardBtn: String,
    val wakeReasonCardTitle: String,
    val wakeReasonCardDesc: String,
    val wakeReasonFieldLabel: String,
    val wakeReasonDefaultText: String,
    val wakeReasonPresets: List<String>,
    val line2SilenceTitle: String,
    val line2SilenceDesc: String,
    val line2ConfirmWindowBtn: String,
    val line3CatchInstruction: String,
    val line4AreYouAwake: String,
    val line4HoldInstruction: String,
    val line5WakeCheckTitle: String,
    val line5WakeCheckSubtitle: String,
    val line5ConfirmAwakeBtn: String,
    val lockout40MinTitle: String,
    val lockout40MinDesc: String,
    val lockout40MinOpenAppBtn: String,
    val activeNotifTitlePrefix: String,
    val activeNotifBodyPrefix: String,
    val btnSaveAndTurnOn: String,
    val btnAlarmIsActiveToday: String,
    val btnAlarmIsActiveTomorrow: String,
    val btnSaveModifiedChanges: String,
    val btnDeactivateActive: String,
    val btnAlreadyDeactivated: String,
    val cancelAlarmUpper: String,
    val snoozeAlarmUpper: String,
    val snoozeInPrefix: String,
    val itWillBeAlreadyPrefix: String,
    val cancelWordUpper: String,
    val riskSubtext: String,
    val ringsAgainAtPrefix: String,
    val ringsAgainLatePrefix: String,
    val lightModeLabel: String,
    val colorSettingsBtn: String,
    val primaryColorTitle: String,
    val secondaryColorTitle: String,
    val autoComplementaryLabel: String,
    val aboutMenuLabel: String,
    val aboutDialogTitle: String,
    val aboutVersionAndDate: String,
    val aboutDescription: String,
    val aboutAuthorLabel: String,
    val aboutWebBtn: String,
    val aboutSupportBtn: String,
    val aboutFeedbackHint: String,
    val aboutFeedbackBtn: String,
    val closeBtn: String,
    val cancelBtn: String,
    val sameColorWarning: String,
    val alarmDeactivatedToast: String
)

fun getStrings(lang: String, failsafeOffset: Int, vibEnabled: Boolean, vibStrength: Int): AppStrings {
    val vibStatusEn = if (vibEnabled) "${vibStrength}\u00A0%" else "OFF"
    val vibStatusSk = if (vibEnabled) "${vibStrength}\u00A0%" else "Vyp."

    return if (lang == "EN") {
        val shortDescEn = "Protects against oversleeping when snoozing repeatedly. It is the last time limit when you really must get up, otherwise you will be late."
        AppStrings(
            appName = "Alarm for Sleepers",
            statusActiveTodayBadge = "🔔 ON FOR TODAY",
            statusActiveTomorrowBadge = "🔔 ON FOR TOMORROW",
            statusOffBadge = "🔕 OFF",
            permTitle = "Required permissions for automatic ringing",
            permDesc = "To wake up the screen automatically without tapping the app, allow display over other apps.",
            permBtn1 = "1.\u00A0Allow display over other apps",
            permBtn2 = "2.\u00A0Allow exact alarm time",
            permRestrictedHint = "Installed via direct .apk download and the switch is greyed out (Restricted settings)? Open App Info -> tap ⋮ in top right -> Allow restricted settings.",
            permOpenAppInfoBtn = "⚙ Open App Info (unlock restricted settings)",
            permRefresh = "Already allowed – Refresh check",
            dailyAlarmTitle = "Daily wake-up time",
            changeAlarmBtn = "Change wake-up time",
            sleepDurationDialogTitle = "Time until alarm (Sleep length)",
            sleepDurationDialogDesc = "Set how long from now the alarm should ring (from 1\u00A0min to 23\u00A0h 59\u00A0min):",
            targetWakeTimeLabel = "Wake-up time:",
            targetWakeTimeSubtext = "when it rings",
            snoozeTitle = "Snooze length (Sleep)",
            minUnit = "min",
            volumeTitle = "Alarm volume",
            volumePreviewHint = "Adjusting volume plays a short preview of your selected alarm sound.",
            gradualVolumeTitle = "Gradual volume increase",
            gradualVolumeDesc = "Alarm rises from 0\u00A0% and failsafe beep from 10\u00A0% up to your set limit (+5\u00A0% on each snooze/dismiss).",
            lowAlarmVolumeWarning = "⚠️ Warning: Alarm volume is very low – you might not hear it while sleeping!",
            lowFailsafeVolumeWarning = "⚠️ Warning: Failsafe beep volume is low – tap here to increase it!",
            failsafeTitle = "Time failsafe (last wake-up limit)",
            failsafeShortDesc = shortDescEn,
            failsafeInfoDialogTitle = "How Time Failsafe & 5 Rescue Lines work",
            failsafeInfoDialogBody = "$shortDescEn\n\n" +
                    "⏱ AUTO OFFSET\nAutomatically shifts by +${failsafeOffset}\u00A0min after your main alarm.\n\n" +
                    "📈 SMART VOLUME & VIBRATIONS\n• Normal mode: every snooze/dismiss increases alarm volume by +5\u00A0% and vibrations by +10\u00A0%.\n• Failsafe mode: independently increases beep volume by +5\u00A0% and vibrations by +10\u00A0%.\n• Only 1 snooze is allowed after the failsafe time.\n\n" +
                    "🛡️ 5 RESCUE LINES\n• 1st line: Fake Dismiss (silently snoozes for 1\u00A0min).\n\n• 2nd line: Flashing fullscreen wake-up reason text + 45\u00A0s silent countdown with a 10\u00A0s confirmation button.\n\n• 3rd line: Dodging Dismiss circle (catch it 3\u00A0times).\n\n• 4th line: Hold Dismiss for 4\u00A0seconds.\n\n• 5th line: Wake-up check 2\u00A0minutes after dismissal (automatically skipped if continuous phone movement is detected).\n\n" +
                    "🔒 40-MINUTE LOCKOUT\nIf not turned off within 40\u00A0minutes of the first ring, dismissal is locked and must be deactivated inside the app.",
            changeFailsafeBtn = "Change failsafe time",
            failsafeOffsetSettingMenu = "Auto failsafe offset (+${failsafeOffset}\u00A0min)",
            failsafeOffsetDialogTitle = "Auto failsafe offset",
            failsafeOffsetDialogDesc = "How many minutes after the main wake-up time should the failsafe be automatically set:",
            failsafeSoundMenu = "Failsafe beep sound",
            failsafeSoundDialogTitle = "Select failsafe beep sound\n(tap for preview)",
            failsafeBeepVolumeLabel = "Failsafe beep volume",
            failsafeBeepOptions = listOf(
                1 to "1.\u00A0High piercing beep",
                2 to "2.\u00A0Single longer beep",
                3 to "3.\u00A0Quadruple beep",
                4 to "4.\u00A0Double beep",
                5 to "5.\u00A0Rhythmic tu-ri-tu-ri"
            ),
            vibrationMenuLabel = "Vibrations ($vibStatusEn)",
            vibrationDialogTitle = "Alarm vibrations & strength",
            vibrationEnableToggleLabel = "Vibrate with sound",
            vibrationStrengthDesc = "Set vibration intensity in\u00A0%\n(adjusting plays a vibration preview):",
            soundTitle = "Alarm sound",
            stopPreview = "Stop preview ⏹",
            soundDef1 = "Default 1 (System alarm)",
            soundDef2 = "Default 2 (Gentle morning melody)",
            soundDef3 = "Default 3 (Brisk digital alarm)",
            soundLocal = "Local music (Music folder)",
            soundSpotify = "Spotify (Pick song in Spotify app)",
            soundYoutube = "YouTube video (Pick video in YouTube app)",
            openMusicFolder = "Open Music folder and pick MP3",
            selectedFile = "Selected:",
            previewLocalBtn = "▶ Preview selected local music",
            previewYoutubeBtn = "▶ Preview YouTube video sound",
            previewSpotifyBtn = "▶ Preview selected Spotify song",
            stopExternalPreviewBtn = "⏹ Stop preview",
            spotifyShareInstruction = "💡 How to add a song from Spotify:\n1. Tap below on \"Open Spotify app and pick song\".\n2. On the selected song, tap Share -> More and select Alarm for Sleepers from the list (or tap Copy link and paste it here).",
            youtubeShareInstruction = "💡 How to add a video from YouTube:\n1. Tap below on button \"Open YouTube app\".\n2. Under the selected video, tap Share -> More and select Alarm for Sleepers from the list (or tap Copy link and paste it here).",
            openYoutubeBtn = "Open YouTube app and pick video",
            openSpotifyBtn = "Open Spotify app and pick song",
            pasteClipboardBtn = "Paste copied link from clipboard",
            wakeReasonCardTitle = "Wake-up reason text",
            wakeReasonCardDesc = "Flashes in huge letters across the full screen after failed attempts to wake you up. Write your specific reason why you must get up.",
            wakeReasonFieldLabel = "Your wake-up message",
            wakeReasonDefaultText = "DON'T TURN OFF THE ALARM, YOU MUST GET UP!",
            wakeReasonPresets = listOf(
                "DON'T TURN OFF THE ALARM, YOU MUST GET UP!",
                "YOU HAVE WORK TODAY, GET UP!",
                "TIME FOR SCHOOL, WAKE UP!",
                "GET UP, OR YOU WILL BE LATE!"
            ),
            line2SilenceTitle = "Prove you are awake.\nWait for the countdown:",
            line2SilenceDesc = "At the end of the countdown, a confirmation button will appear for 10\u00A0seconds to confirm that you are really awake.",
            line2ConfirmWindowBtn = "I CONFIRM\nI'M AWAKE",
            line3CatchInstruction = "3rd RESCUE LINE: CATCH THE BUTTON 3\u00A0TIMES!",
            line4AreYouAwake = "Are you really awake?",
            line4HoldInstruction = "HOLD FOR 4\u00A0SECONDS\nTO DISMISS",
            line5WakeCheckTitle = "Are you really awake?\nTap to confirm.",
            line5WakeCheckSubtitle = "If you don't confirm before the countdown ends, the alarm will start ringing at full volume again.",
            line5ConfirmAwakeBtn = "YES, I'M AWAKE",
            lockout40MinTitle = "RINGING FOR OVER 40\u00A0MINUTES!",
            lockout40MinDesc = "To prevent unconscious dismissal, the dismiss button is locked. Open the app and physically switch the alarm to Deactivate.",
            lockout40MinOpenAppBtn = "OPEN APP TO DEACTIVATE ALARM",
            activeNotifTitlePrefix = "Alarm set for",
            activeNotifBodyPrefix = "Time failsafe:",
            btnSaveAndTurnOn = "🔔 TURN ON & SAVE DAILY ALARM",
            btnAlarmIsActiveToday = "✅ ALARM IS ACTIVE FOR TODAY",
            btnAlarmIsActiveTomorrow = "✅ ALARM IS ACTIVE FOR TOMORROW",
            btnSaveModifiedChanges = "💾 SAVE CHANGES TO ALARM",
            btnDeactivateActive = "🔕 DEACTIVATE ALARM",
            btnAlreadyDeactivated = "✓ ALARM IS DEACTIVATED (WON'T RING)",
            cancelAlarmUpper = "DISMISS ALARM",
            snoozeAlarmUpper = "SNOOZE ALARM",
            snoozeInPrefix = "in",
            itWillBeAlreadyPrefix = "WILL BE",
            cancelWordUpper = "DISMISS",
            riskSubtext = "WARNING!",
            ringsAgainAtPrefix = "Will ring again at",
            ringsAgainLatePrefix = "Will ring late at",
            lightModeLabel = "Light mode",
            colorSettingsBtn = "Customize colors",
            primaryColorTitle = "Primary color (Snooze & main buttons)",
            secondaryColorTitle = "Secondary color (Dismiss & Failsafe)",
            autoComplementaryLabel = "Auto-pair contrasting secondary color",
            aboutMenuLabel = "About the app",
            aboutDialogTitle = "About Alarm for Sleepers",
            aboutVersionAndDate = "Version 1.0.0 • Released: October 2026",
            aboutDescription = "This app was created with one clear purpose – to reliably wake up even heavy sleepers. While ringing, the screen is split into a large Snooze area (73\u00A0%) and a smaller Dismiss area (27\u00A0%) so you don't accidentally turn the alarm off while half-asleep.\n\nIf you keep snoozing and pass your Time Failsafe limit, an emergency beep starts and a 5-line rescue system protects you from turning the alarm off in half-sleep.",
            aboutAuthorLabel = "Author, Idea maker, Design & Testing:\nDominik Kozmáli\n\nProgramming & Build:\nGemini 4 Argon",
            aboutWebBtn = "🌐 More free content\nkozmali.sk",
            aboutSupportBtn = "🍵Support & thank the author\nkozmali.sk/podpora",
            aboutFeedbackHint = "Missing a feature in the app? Send me your feedback – I'll gladly read it and can add new improvements:",
            aboutFeedbackBtn = "💬 Send feedback\nkozmali.sk/spatna-vazba",
            closeBtn = "Done",
            cancelBtn = "Cancel",
            sameColorWarning = "Primary and secondary colors must be different so buttons stay distinguishable!",
            alarmDeactivatedToast = "Alarm deactivated – it will not ring"
        )
    } else {
        val shortDescSk = "Slúži ako ochrana pred zaspaním pri opakovanom odkladaní budíka. Je to posledný hraničný čas, kedy už naozaj musíš vstať, inak zmeškáš."
        AppStrings(
            appName = "Budík pre spáčov",
            statusActiveTodayBadge = "🔔 ZAPNUTÝ NA DNES",
            statusActiveTomorrowBadge = "🔔 ZAPNUTÝ NA ZAJTRA",
            statusOffBadge = "🔕 VYPNUTÝ",
            permTitle = "Vyžadované povolenia pre automatické zvonenie",
            permDesc = "Aby sa budík sám rozsvietil bez klikania na aplikáciu, povoľ mu zobrazenie nad ostatnými aplikáciami.",
            permBtn1 = "1.\u00A0Povoliť zobrazovanie nad aplikáciami",
            permBtn2 = "2.\u00A0Povoliť presný čas budíka",
            permRestrictedHint = "Ak si apku nainštaloval z .apk súboru z webu a prepínač je zašednutý (Obmedzené nastavenia): otvor Informácie o aplikácii -> ťukni vpravo hore na ⋮ -> Povoliť obmedzené nastavenia.",
            permOpenAppInfoBtn = "⚙ Otvoriť Informácie o aplikácii (ak je prepínač zašednutý)",
            permRefresh = "Už som povolil – Obnoviť kontrolu",
            dailyAlarmTitle = "Čas každodenného budenia",
            changeAlarmBtn = "Zmeniť čas budenia",
            sleepDurationDialogTitle = "Doba do zazvonenia (Dĺžka spánku)",
            sleepDurationDialogDesc = "Nastav, po akom čase odteraz má budík zazvoniť\n(môžeš nastaviť od 1\u00A0min po 23\u00A0h a 59\u00A0min):",
            targetWakeTimeLabel = "Čas budíku:",
            targetWakeTimeSubtext = "kedy bude zvoniť",
            snoozeTitle = "Dĺžka odloženia budíka (Uspanie)",
            minUnit = "min",
            volumeTitle = "Hlasitosť budíka",
            volumePreviewHint = "Pri zmene hlasitosti zaznie hneď krátka ukážka vybraného zvuku budenia.",
            gradualVolumeTitle = "Postupné zvyšovanie hlasitosti",
            gradualVolumeDesc = "Budík stúpa od 0\u00A0% a pípanie poistky od 10\u00A0% až do nastavenej hlasitosti (+5\u00A0% pri každom ďalšom odložení/zrušení).",
            lowAlarmVolumeWarning = "⚠️ Pozor: Hlasitosť budíka je príliš nízka – v spánku ho nemusíš počuť!",
            lowFailsafeVolumeWarning = "⚠️ Pozor: Hlasitosť pípania poistky je nízka – nemusíš ju počuť! (Ťukni pre zmenu)",
            failsafeTitle = "Poistka času (posledný limit vstávania)",
            failsafeShortDesc = shortDescSk,
            failsafeInfoDialogTitle = "Ako funguje Poistka času a 5 línií záchrany",
            failsafeInfoDialogBody = "$shortDescSk\n\n" +
                    "⏱ AUTOMATICKÝ POSUN\nAutomaticky sa prednastaví o +${failsafeOffset}\u00A0min po čase hlavného budenia.\n\n" +
                    "📈 STUPŇOVANIE HLASITOSTI A VIBRÁCIÍ\n• Normálny režim (hudba): každé odloženie/zrušenie zvýši hlasitosť budíka o +5\u00A0% a vibrácie o +10\u00A0%.\n• Režim poistky (pípanie): nezávisle zvýši hlasitosť pípania o +5\u00A0% a vibrácie o +10\u00A0%.\n• Po prekročení poistky je povolené už iba jedno odloženie.\n\n" +
                    "🛡️ 5 LÍNIÍ ZÁCHRANY\n• 1.\u00A0línia (Falošné zrušenie): Ťuknutie na Zrušiť budík len potichu odloží o 1\u00A0minútu.\n\n• 2.\u00A0línia (Blikajúci text + 45\u00A0s odpočet): Cez obrazovku bliká tvoj dôvod vstávania. Po ťuknutí na Zrušiť beží 45\u00A0s ticha a na konci sa na 10\u00A0sekúnd objaví potvrdzovacie tlačidlo.\n\n• 3.\u00A0línia (Uhýbajúce tlačidlo): Tlačidlo Zrušiť musíš chytiť 3-krát.\n\n• 4.\u00A0línia (Podržanie na 4\u00A0s): Tlačidlo Zrušiť musíš podržať 4\u00A0sekundy.\n\n• 5.\u00A0línia (Kontrola o 2\u00A0min): O 2\u00A0minúty po zrušení sa budík opýta, či si hore (ak senzor zaznamená pohyb telefónu, otázku už nespustí).\n\n" +
                    "🔒 40-MINÚTOVÁ BLOKÁCIA\nAk budík nevypneš do 40\u00A0minút od prvého zvonenia, zrušenie sa zablokuje a vypneš ho až priamo v aplikácii.",
            changeFailsafeBtn = "Zmeniť čas poistky",
            failsafeOffsetSettingMenu = "Automatický posun poistky (+${failsafeOffset}\u00A0min)",
            failsafeOffsetDialogTitle = "Automatický posun poistky",
            failsafeOffsetDialogDesc = "O koľko minút po hlavnom budíku sa má automaticky prednastaviť poistka času:",
            failsafeSoundMenu = "Zvuk pípania poistky",
            failsafeSoundDialogTitle = "Vyber zvuk pípania poistky\n(ťukni pre ukážku)",
            failsafeBeepVolumeLabel = "Hlasitosť pípania poistky",
            failsafeBeepOptions = listOf(
                1 to "1.\u00A0Vysoký prenikavý",
                2 to "2.\u00A0Jednotónové dlhšie pípanie",
                3 to "3.\u00A0Štvorité pípanie",
                4 to "4.\u00A0Dvojité pípanie",
                5 to "5.\u00A0Rytmické tu-ri-tu-ri"
            ),
            vibrationMenuLabel = "Vibrácie ($vibStatusSk)",
            vibrationDialogTitle = "Vibrácie budíka a poistky",
            vibrationEnableToggleLabel = "Vibrovať podľa zvuku",
            vibrationStrengthDesc = "Zvoľ silu vibrácií v\u00A0percentách\n(pri zmene ihneď zacítiš ukážku):",
            soundTitle = "Zvuk budenia",
            stopPreview = "Zastaviť ukážku ⏹",
            soundDef1 = "Predvolený 1 (Systémový budík)",
            soundDef2 = "Predvolený 2 (Jemná stúpajúca melódia)",
            soundDef3 = "Predvolený 3 (Rázny digitálny budík)",
            soundLocal = "Lokálna hudba (Priečinok Music)",
            soundSpotify = "Spotify (Vybrať v aplikácii Spotify)",
            soundYoutube = "YouTube video (Vybrať v aplikácii YouTube)",
            openMusicFolder = "Otvoriť priečinok Music a vybrať MP3",
            selectedFile = "Vybrané:",
            previewLocalBtn = "▶ Prehrať ukážku vybranej hudby",
            previewYoutubeBtn = "▶ Prehrať ukážku YouTube videa",
            previewSpotifyBtn = "▶ Prehrať ukážku vybranej Spotify skladby",
            stopExternalPreviewBtn = "⏹ Zastaviť ukážku",
            spotifyShareInstruction = "💡 Ako pridať skladbu zo Spotify:\n1. Ťukni nižšie na tlačidlo \"Otvoriť aplikáciu Spotify\".\n2. Pri vybranej pesničke ťukni na Zdieľať -> Viac a v zozname vyber aplikáciu Budík pre spáčov (prípadne daj Kopírovať odkaz a vlož ho sem).",
            youtubeShareInstruction = "💡 Ako pridať video z YouTube:\n1. Ťukni nižšie na \"Otvoriť aplikáciu YouTube\".\n2. Pod vybraným videom ťukni na Zdieľať -> Viac a v zozname vyber aplikáciu Budík pre spáčov (prípadne daj Kopírovať odkaz a vlož ho sem).",
            openYoutubeBtn = "Otvoriť aplikáciu YouTube a vybrať video",
            openSpotifyBtn = "Otvoriť aplikáciu Spotify a vybrať skladbu",
            pasteClipboardBtn = "Vložiť skopírovaný odkaz zo schránky",
            wakeReasonCardTitle = "Text dôvodu vstávania",
            wakeReasonCardDesc = "Zobrazí sa ako veľký blikajúci nápis cez celú obrazovku, po neúspešných pokusoch zobudiť ťa. Napíš si konkrétny dôvod, prečo musíš vstať.",
            wakeReasonFieldLabel = "Tvoja výstražná správa pri prebúdzaní",
            wakeReasonDefaultText = "NEVYPÍNAJ BUDÍK, MUSÍŠ VSTÁVAŤ!",
            wakeReasonPresets = listOf(
                "NEVYPÍNAJ BUDÍK, MUSÍŠ VSTÁVAŤ!",
                "DNES IDEŠ DO PRÁCE!",
                "IDEŠ DO ŠKOLY, VSTÁVAJ!",
                "VSTÁVAJ, INAK ZMEŠKÁŠ!"
            ),
            line2SilenceTitle = "Dokáž, že si už hore.\nPočkaj na koniec odpočtu:",
            line2SilenceDesc = "Na konci odpočtu sa na 10\u00A0sekúnd zobrazí potvrdzujúce tlačidlo, že si už naozaj hore.",
            line2ConfirmWindowBtn = "POTVRDZUJEM\nSOM HORE",
            line3CatchInstruction = "3. LÍNIA ZÁCHRANY: CHYŤ TLAČIDLO 3-KRÁT!",
            line4AreYouAwake = "Naozaj si hore?",
            line4HoldInstruction = "PODRŽ 4\u00A0SEKUNDY\nPRE ZRUŠENIE",
            line5WakeCheckTitle = "Si už naozaj hore?\nŤukni pre potvrdenie.",
            line5WakeCheckSubtitle = "Ak do konca odpočtu nepotvrdíš bdelosť, budík začne znova naplno zvoniť.",
            line5ConfirmAwakeBtn = "ÁNO, SOM HORE",
            lockout40MinTitle = "BUDÍK ZVONÍ UŽ VIAC AKO 40\u00A0MINÚT!",
            lockout40MinDesc = "Aby si budík nevypol v spánku, zrušenie je zablokované. Pre vypnutie otvor aplikáciu a fyzicky prepni tlačidlo na Deaktivovať budík.",
            lockout40MinOpenAppBtn = "OTVORIŤ APLIKÁCIU A DEAKTIVOVAŤ",
            activeNotifTitlePrefix = "Budík je nastavený na",
            activeNotifBodyPrefix = "Poistka času:",
            btnSaveAndTurnOn = "🔔 ZAPNÚŤ A ULOŽIŤ DENNÝ BUDÍK",
            btnAlarmIsActiveToday = "✅ BUDÍK JE AKTÍVNY NA DNES",
            btnAlarmIsActiveTomorrow = "✅ BUDÍK JE AKTÍVNY NA ZAJTRA",
            btnSaveModifiedChanges = "💾 ULOŽIŤ ZMENY V BUDÍKU",
            btnDeactivateActive = "🔕 DEAKTIVOVAŤ BUDÍK",
            btnAlreadyDeactivated = "✓ BUDÍK JE DEAKTIVOVANÝ (NEZAZVONÍ)",
            cancelAlarmUpper = "ZRUŠIŤ BUDÍK",
            snoozeAlarmUpper = "ODLOŽIŤ BUDÍK",
            snoozeInPrefix = "o",
            itWillBeAlreadyPrefix = "BUDE",
            cancelWordUpper = "ZRUŠIŤ",
            riskSubtext = "POZOR!",
            ringsAgainAtPrefix = "Znova zazvoní o",
            ringsAgainLatePrefix = "Znova zazvoní už neskoro o",
            lightModeLabel = "Svetlý režim",
            colorSettingsBtn = "Zmeniť farby",
            primaryColorTitle = "Primárna farba (Odložiť a hlavné prvky)",
            secondaryColorTitle = "Sekundárna farba (Zrušiť a poistka)",
            autoComplementaryLabel = "Automaticky priradiť kontrastnú sekundárnu farbu",
            aboutMenuLabel = "O aplikácii",
            aboutDialogTitle = "O aplikácii Budík pre spáčov",
            aboutVersionAndDate = "Verzia 1.0.0 • Dátum vydania: Október 2026",
            aboutDescription = "Aplikácia vznikla s cieľom spoľahlivo zobudiť aj tých najväčších spáčov. Počas zvonenia rozdeľuje obrazovku na veľké tlačidlo Odložiť (73\u00A0%) a menšie tlačidlo Zrušiť (27\u00A0%), aby človek v rozospatom stave budík omylom nevypol.\n\nAk však budík odkladáš príliš dlho a prekročíš nastavenú Poistku času, spustí sa výrazné pípanie a 5-úrovňový systém poslednej záchrany, aby bolo jasné, že už naozaj musíš vstať, inak zmeškáš alebo zaspíš.",
            aboutAuthorLabel = "Autor, Idea maker, Dizajn a Testing:\nDominik Kozmáli\n\nProgramovanie a Build:\nGemini 4 Argon",
            aboutWebBtn = "🌐 Ďalší obsah zadarmo\nkozmali.sk",
            aboutSupportBtn = "🍵Podpora a poďakovanie za apku\nkozmali.sk/podpora",
            aboutFeedbackHint = "Chýba ti v apke niečo? Napíš mi spätnú väzbu – rád si ju prečítam a môžem to do aplikácie pridať:",
            aboutFeedbackBtn = "💬 Pridať spätnú väzbu\nkozmali.sk/spatna-vazba",
            closeBtn = "Hotovo",
            cancelBtn = "Zrušiť",
            sameColorWarning = "Primárna a sekundárna farba musia byť odlišné, aby tlačidlá nesplynuli!",
            alarmDeactivatedToast = "Budík bol deaktivovaný"
        )
    }
}

class OpenMusicDocumentContract : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        val musicUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic")
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, musicUri)
        return intent
    }
}

fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    thumbColor: Color,
    trackColor: Color,
    width: Dp = 6.dp
): Modifier = drawWithContent {
    drawContent()
    val viewportHeight = this.size.height
    val totalContentHeight = scrollState.maxValue.toFloat() + viewportHeight
    if (totalContentHeight > viewportHeight && scrollState.maxValue > 0) {
        val thumbHeight = (viewportHeight * (viewportHeight / totalContentHeight)).coerceAtLeast(48.dp.toPx())
        val thumbOffsetY = (scrollState.value.toFloat() / scrollState.maxValue.toFloat()) * (viewportHeight - thumbHeight)
        val barWidthPx = width.toPx()
        val xPos = this.size.width - barWidthPx - 4.dp.toPx()

        drawRoundRect(
            color = trackColor,
            topLeft = Offset(xPos, 0f),
            size = Size(barWidthPx, viewportHeight),
            cornerRadius = CornerRadius(barWidthPx / 2, barWidthPx / 2)
        )
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(xPos, thumbOffsetY),
            size = Size(barWidthPx, thumbHeight),
            cornerRadius = CornerRadius(barWidthPx / 2, barWidthPx / 2)
        )
    }
}

@Composable
fun SmallInfoIconButton(
    primaryColor: Color,
    elevatedColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(elevatedColor)
            .border(1.dp, primaryColor.copy(alpha = 0.6f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "i",
            color = primaryColor,
            fontSize = 12.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.offset(y = (-0.0).dp) // <-- záporné číslo posúva "i" vyššie
        )
    }
}

@Composable
fun CircularDurationSlider(
    durationMinutes: Int,
    primaryColor: Color,
    trackColor: Color,
    textPrimary: Color,
    textSecondary: Color,
    targetWakeUpTitle: String,
    targetWakeUpTimeText: String,
    targetWakeUpSubtext: String,
    onMinutesChange: (Int) -> Unit
) {
    val currentOnChange by rememberUpdatedState(onMinutesChange)

    fun updateFromTouch(touchOffset: Offset, sizePx: Float) {
        val center = sizePx / 2f
        val dx = touchOffset.x - center
        val dy = touchOffset.y - center
        var angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
        if (angleDeg < 0f) angleDeg += 360f
        val mins = (1 + ((angleDeg / 360f) * 1438f).roundToInt()).coerceIn(1, 1439)
        currentOnChange(mins)
    }

    Box(
        modifier = Modifier
            .size(196.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    updateFromTouch(offset, size.width.toFloat())
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    updateFromTouch(change.position, size.width.toFloat())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            val strokeWidthPx = 16.dp.toPx()
            val diameter = size.minDimension - strokeWidthPx
            val topLeftOffset = Offset(strokeWidthPx / 2f, strokeWidthPx / 2f)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeftOffset,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            val sweepAngle = ((durationMinutes.coerceIn(1, 1439) - 1) / 1438f) * 360f
            drawArc(
                color = primaryColor,
                startAngle = -90f,
                sweepAngle = sweepAngle.coerceAtLeast(3f),
                useCenter = false,
                topLeft = topLeftOffset,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            val angleRad = Math.toRadians((sweepAngle - 90f).toDouble())
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val thumbCenter = Offset(
                x = center.x + (radius * cos(angleRad)).toFloat(),
                y = center.y + (radius * sin(angleRad)).toFloat()
            )
            drawCircle(
                color = Color.White,
                radius = 12.dp.toPx(),
                center = thumbCenter
            )
            drawCircle(
                color = primaryColor,
                radius = 8.dp.toPx(),
                center = thumbCenter
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = targetWakeUpTitle,
                color = textSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = targetWakeUpTimeText,
                color = textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = targetWakeUpSubtext,
                color = textSecondary.copy(alpha = 0.7f),
                fontSize = 11.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlueThemedTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    titleText: String,
    confirmText: String,
    cancelText: String,
    accentColor: Color,
    cardColor: Color,
    elevatedColor: Color,
    textPrimary: Color,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    val context = LocalContext.current
    val pickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )

    var firstLoad by remember { mutableStateOf(true) }
    LaunchedEffect(pickerState.hour, pickerState.minute) {
        if (firstLoad) {
            firstLoad = false
        } else {
            performUiHaptic(context, isLightTick = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardColor,
        titleContentColor = textPrimary,
        title = {
            Text(
                text = titleText,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = textPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(
                    state = pickerState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = elevatedColor,
                        clockDialSelectedContentColor = contentColorForBg(accentColor),
                        clockDialUnselectedContentColor = textPrimary,
                        selectorColor = accentColor,
                        containerColor = cardColor,
                        periodSelectorBorderColor = accentColor,
                        periodSelectorSelectedContainerColor = accentColor,
                        periodSelectorUnselectedContainerColor = elevatedColor,
                        periodSelectorSelectedContentColor = contentColorForBg(accentColor),
                        periodSelectorUnselectedContentColor = textPrimary,
                        timeSelectorSelectedContainerColor = accentColor,
                        timeSelectorUnselectedContainerColor = elevatedColor,
                        timeSelectorSelectedContentColor = contentColorForBg(accentColor),
                        timeSelectorUnselectedContentColor = textPrimary
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                performUiHaptic(context)
                onDismiss()
            }) {
                Text(cancelText, color = textPrimary, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    performUiHaptic(context)
                    onConfirm(pickerState.hour, pickerState.minute)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = contentColorForBg(accentColor)
                )
            ) {
                Text(confirmText, fontWeight = FontWeight.Bold)
            }
        }
    )
}

class MainActivity : ComponentActivity() {
    private var sharedLinkState = mutableStateOf<Pair<String, String>?>(null)

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        handleIncomingShareIntent(intent)

        setContent {
            AlarmSetupScreen(sharedLinkState.value) {
                sharedLinkState.value = null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            val urlRegex = "(https?://\\S+)".toRegex()
            val extractedUrl = urlRegex.find(sharedText)?.value ?: sharedText

            if (extractedUrl.contains("spotify.com", ignoreCase = true) || extractedUrl.startsWith("spotify:")) {
                sharedLinkState.value = "SPOTIFY" to extractedUrl
            } else if (extractedUrl.contains("youtube.com", ignoreCase = true) || extractedUrl.contains("youtu.be", ignoreCase = true)) {
                sharedLinkState.value = "YOUTUBE" to extractedUrl
            }
        }
    }
}
// --- KONIEC 1. ČASTI (pod tento riadok vlož 2. časť) ---

// --- ZAČIATOK 2. ČASTI ---
@Composable
fun AlarmSetupScreen(
    incomingSharedMedia: Pair<String, String>?,
    onSharedMediaConsumed: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember { context.getSharedPreferences("budik_pre_spacov_prefs", Context.MODE_PRIVATE) }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // 1. ČAS BUDÍKA, DĹŽKA ODLOŽENIA, POSUN POISTKY A ČI JE BUDÍK ZAPNUTÝ
    val initialOffset = remember {
        if (!prefs.getBoolean("default_times_730_active_init", false)) {
            prefs.edit()
                .putBoolean("default_times_730_active_init", true)
                .putBoolean("default_times_730_initialized", true)
                .putBoolean("offset_v2_initialized", true)
                .putInt("alarm_hour", 7)               // <-- PREDVOLENÁ HODINA BUDÍKA (7)
                .putInt("alarm_min", 45)               // <-- PREDVOLENÁ MINÚTA BUDÍKA (45) -> spolu 7:45
                .putInt("snooze_min", 5)               // <-- PREDVOLENÁ DĹŽKA ODLOŽENIA V MINÚTACH (5 min)
                .putInt("failsafe_offset_min_v2", 15)  // <-- PREDVOLENÝ POSUN POISTKY (+15 min)
                .putInt("failsafe_hour", 8)            // <-- HODINA POISTKY (7:45 + 15 min = 8:00)
                .putInt("failsafe_min", 0)             // <-- MINÚTA POISTKY (0)
                .putInt("failsafe_stage", 1)
                .putInt("alarm_escalation_boost", 0)
                .putInt("failsafe_escalation_boost", 0)
                .putInt("vib_escalation_boost", 0)
                .putInt("failsafe_snooze_count", 0)
                .putLong("first_ring_timestamp", 0L)
                .putBoolean("is_enabled", true)        // <-- ČI JE BUDÍK PREDVOLENE ZAPNUTÝ (true = zapnutý, false = vypnutý)
                .apply()
            15
        } else {
            prefs.getInt("failsafe_offset_min_v2", 15)
        }
    }

    // 2. PREDVOLENÁ HLASITOSŤ BUDÍKA (teraz 47 %)
    val initialAlarmVolume = remember {
        if (!prefs.getBoolean("alarm_vol_v3_init", false)) {
            prefs.edit()
                .putBoolean("alarm_vol_v3_init", true)
                .putInt("alarm_volume_v3", 47) // <-- PREDVOLENÁ HLASITOSŤ BUDÍKA V % (47)
                .apply()
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxV = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                val targetStep = ((47 / 100f) * maxV).roundToInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_ALARM, targetStep, 0)
            } catch (_: Exception) {}
            47
        } else {
            prefs.getInt("alarm_volume_v3", 47)
        }
    }

    // 3. PREDVOLENÁ HLASITOSŤ PÍPANIA POISTKY (teraz 62 %)
    val initialFailsafeVolume = remember {
        if (!prefs.getBoolean("failsafe_vol_v3_init", false)) {
            prefs.edit()
                .putBoolean("failsafe_vol_v3_init", true)
                .putInt("failsafe_volume_v3", 62) // <-- PREDVOLENÁ HLASITOSŤ PÍPANIA POISTKY V % (62)
                .apply()
            62
        } else {
            prefs.getInt("failsafe_volume_v3", 62)
        }
    }

    // 4. VIBRÁCIE (ZAPNUTÉ/VYPNUTÉ + SILA V %)
    val initialVibStrength = remember {
        if (!prefs.getBoolean("vib_v2_init", false)) {
            prefs.edit()
                .putBoolean("vib_v2_init", true)
                .putBoolean("vibration_enabled", false) // <-- VIBRÁCIE ZAPNUTÉ/VYPNUTÉ (true = ON, false = OFF)
                .putInt("vibration_strength_v2", 25)    // <-- PREDVOLENÁ SILA VIBRÁCIÍ V % (25)
                .apply()
            25
        } else {
            prefs.getInt("vibration_strength_v2", 25)
        }
    }

    var alarmHour by remember { mutableIntStateOf(prefs.getInt("alarm_hour", 7)) }
    var alarmMin by remember { mutableIntStateOf(prefs.getInt("alarm_min", 45)) }
    var failsafeHour by remember { mutableIntStateOf(prefs.getInt("failsafe_hour", 8)) }
    var failsafeMin by remember { mutableIntStateOf(prefs.getInt("failsafe_min", 0)) }
    var failsafeOffsetMin by remember { mutableIntStateOf(initialOffset) }
    var failsafeSoundType by remember { mutableIntStateOf(prefs.getInt("failsafe_sound_type_v2", 5)) }
    var failsafeVolume by remember { mutableIntStateOf(initialFailsafeVolume) }
    var snoozeMinutes by remember { mutableIntStateOf(prefs.getInt("snooze_min", 5)) }
    var alarmVolume by remember { mutableIntStateOf(initialAlarmVolume) }
    var gradualVolumeEnabled by remember { mutableStateOf(prefs.getBoolean("gradual_volume_enabled", true)) }
    var isEnabled by remember { mutableStateOf(prefs.getBoolean("is_enabled", true)) }

    var vibrationEnabled by remember { mutableStateOf(prefs.getBoolean("vibration_enabled", false)) }
    var vibrationStrength by remember { mutableIntStateOf(initialVibStrength) }

    var hasUnsavedChanges by remember { mutableStateOf(false) }

    var soundSource by remember { mutableStateOf(prefs.getString("sound_source", "DEFAULT_1") ?: "DEFAULT_1") }

    // Samostatne uložené odkazy pre Lokálnu hudbu, Spotify a YouTube
    val legacyCustomUri = remember { prefs.getString("custom_uri", "") ?: "" }
    var savedLocalUri by remember {
        mutableStateOf(
            prefs.getString("saved_local_uri", null)
                ?: if (soundSource == "LOCAL") legacyCustomUri else ""
        )
    }
    var savedSpotifyUrl by remember {
        mutableStateOf(
            prefs.getString("saved_spotify_url", null)
                ?: if (soundSource == "SPOTIFY") legacyCustomUri else ""
        )
    }
    var savedYoutubeUrl by remember {
        mutableStateOf(
            prefs.getString("saved_youtube_url", null)
                ?: if (soundSource == "YOUTUBE") legacyCustomUri else ""
        )
    }

    // Uložené údaje o vybranej Spotify skladbe pre prehrávanie na pozadí od 0:00 (bez otvárania Spotify aplikácie)
    var resolvedSpotifyTitle by remember { mutableStateOf(prefs.getString("resolved_spotify_title", "") ?: "") }
    var resolvedSpotifyYtId by remember { mutableStateOf(prefs.getString("resolved_spotify_yt_id", "") ?: "") }
    var isResolvingSpotify by remember { mutableStateOf(false) }

    var isLightMode by remember { mutableStateOf(prefs.getBoolean("light_mode", false)) }
    var appLanguage by remember { mutableStateOf(resolveInitialLanguage(prefs)) }
    var primaryColorKey by remember { mutableStateOf(prefs.getString("primary_color", "BLUE") ?: "BLUE") }
    var secondaryColorKey by remember { mutableStateOf(prefs.getString("secondary_color", "RED") ?: "RED") }
    var autoComplementary by remember { mutableStateOf(prefs.getBoolean("auto_comp", true)) }

    val strings = getStrings(appLanguage, failsafeOffsetMin, vibrationEnabled, vibrationStrength)

    var wakeUpReasonText by remember {
        mutableStateOf(
            prefs.getString("wake_up_reason_text", strings.wakeReasonDefaultText)
                ?: strings.wakeReasonDefaultText
        )
    }

    var showTopMenu by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showOffsetDialog by remember { mutableStateOf(false) }
    var showSleepDurationDialog by remember { mutableStateOf(false) }
    var showFailsafeSoundDialog by remember { mutableStateOf(false) }
    var showFailsafeInfoDialog by remember { mutableStateOf(false) }
    var showVibrationDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    // Stav pre minimalistické vyskakovacie okná ( i ) pri jednotlivých položkách
    var quickInfoDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

    var showMainTimePicker by remember { mutableStateOf(false) }
    var showFailsafeTimePicker by remember { mutableStateOf(false) }

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasExactAlarmPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
            } else true
        )
    }

    var lastSyncedSystemAlarmStep by remember {
        mutableIntStateOf(
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.getStreamVolume(AudioManager.STREAM_ALARM)
            } catch (_: Exception) { -1 }
        )
    }

    var timeCheckTick by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_TIMEZONE_CHANGED -> {
                        timeCheckTick++
                    }
                    "android.media.VOLUME_CHANGED_ACTION" -> {
                        try {
                            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                            val maxStep = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                            val currentStep = am.getStreamVolume(AudioManager.STREAM_ALARM)
                            if (maxStep > 0 && currentStep != lastSyncedSystemAlarmStep) {
                                lastSyncedSystemAlarmStep = currentStep
                                val syncedPercent = ((currentStep.toFloat() / maxStep) * 100f).roundToInt().coerceIn(10, 100)
                                alarmVolume = syncedPercent
                                prefs.edit().putInt("alarm_volume_v3", syncedPercent).apply()
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction("android.media.VOLUME_CHANGED_ACTION")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    // Automatické rozpoznanie názvu skladby a príprava štúdiového audio streamu na pozadí pri zmene Spotify odkazu
    LaunchedEffect(savedSpotifyUrl) {
        val cleanUrl = savedSpotifyUrl.trim()
        if (cleanUrl.isEmpty()) {
            resolvedSpotifyTitle = ""
            resolvedSpotifyYtId = ""
            prefs.edit()
                .remove("resolved_spotify_title")
                .remove("resolved_spotify_yt_id")
                .remove("resolved_spotify_source_url")
                .apply()
            return@LaunchedEffect
        }
        val alreadyResolvedUrl = prefs.getString("resolved_spotify_source_url", "") ?: ""
        if (alreadyResolvedUrl == cleanUrl && resolvedSpotifyYtId.isNotEmpty() && resolvedSpotifyTitle.isNotEmpty()) {
            return@LaunchedEffect
        }
        isResolvingSpotify = true
        val resolved = withContext(Dispatchers.IO) {
            resolveSpotifyTrackToBackgroundStream(cleanUrl)
        }
        isResolvingSpotify = false
        if (resolved != null) {
            resolvedSpotifyTitle = resolved.displayTitle
            resolvedSpotifyYtId = resolved.youtubeVideoId
            prefs.edit()
                .putString("resolved_spotify_title", resolved.displayTitle)
                .putString("resolved_spotify_yt_id", resolved.youtubeVideoId)
                .putString("resolved_spotify_source_url", cleanUrl)
                .apply()
        }
    }

    val activeCustomUriForCurrentSource = when (soundSource) {
        "LOCAL" -> savedLocalUri
        "SPOTIFY" -> savedSpotifyUrl
        "YOUTUBE" -> savedYoutubeUrl
        else -> ""
    }

    // AUTOMATICKÉ UKLADANIE PRI KAŽDEJ ZMENE
    LaunchedEffect(
        alarmHour, alarmMin, failsafeHour, failsafeMin,
        failsafeOffsetMin, failsafeSoundType, failsafeVolume,
        snoozeMinutes, alarmVolume, gradualVolumeEnabled,
        vibrationEnabled, vibrationStrength, soundSource,
        savedLocalUri, savedSpotifyUrl, savedYoutubeUrl,
        wakeUpReasonText, isEnabled, appLanguage
    ) {
        prefs.edit()
            .putInt("alarm_hour", alarmHour)
            .putInt("alarm_min", alarmMin)
            .putInt("failsafe_hour", failsafeHour)
            .putInt("failsafe_min", failsafeMin)
            .putInt("failsafe_offset_min_v2", failsafeOffsetMin)
            .putInt("failsafe_sound_type_v2", failsafeSoundType)
            .putInt("failsafe_volume_v3", failsafeVolume)
            .putInt("snooze_min", snoozeMinutes)
            .putInt("alarm_volume_v3", alarmVolume)
            .putBoolean("gradual_volume_enabled", gradualVolumeEnabled)
            .putBoolean("vibration_enabled", vibrationEnabled)
            .putInt("vibration_strength_v2", vibrationStrength)
            .putString("sound_source", soundSource)
            .putString("saved_local_uri", savedLocalUri)
            .putString("saved_spotify_url", savedSpotifyUrl)
            .putString("saved_youtube_url", savedYoutubeUrl)
            .putString("custom_uri", activeCustomUriForCurrentSource)
            .putString("wake_up_reason_text", wakeUpReasonText.ifBlank { strings.wakeReasonDefaultText })
            .putBoolean("is_enabled", isEnabled)
            .apply()

        if (isEnabled) {
            AlarmScheduler.scheduleDailyAlarm(context, alarmHour, alarmMin)
            AlarmScheduler.updateScheduledStatusNotification(
                context = context,
                isEnabled = true,
                alarmHour = alarmHour,
                alarmMin = alarmMin,
                failsafeHour = failsafeHour,
                failsafeMin = failsafeMin,
                strings = strings
            )
        } else {
            AlarmScheduler.cancelAlarm(context)
            AlarmScheduler.updateScheduledStatusNotification(
                context = context,
                isEnabled = false,
                alarmHour = alarmHour,
                alarmMin = alarmMin,
                failsafeHour = failsafeHour,
                failsafeMin = failsafeMin,
                strings = strings
            )
        }
    }

    val isForToday = remember(alarmHour, alarmMin, timeCheckTick) {
        isAlarmStillToday(alarmHour, alarmMin)
    }

    val minutesUntilAlarm = remember(alarmHour, alarmMin, timeCheckTick) {
        getMinutesUntilAlarm(alarmHour, alarmMin)
    }

    val primaryPalette = getPaletteColor(primaryColorKey, "BLUE")
    val secondaryPalette = getPaletteColor(secondaryColorKey, "RED")
    val primaryColor = primaryPalette.color
    val secondaryColor = secondaryPalette.color

    val bgColor = if (isLightMode) Color(0xFFF1F5F9) else Color(0xFF0B0F17)
    val cardColor = if (isLightMode) Color(0xFFFFFFFF) else Color(0xFF151C28)
    val elevatedColor = if (isLightMode) Color(0xFFE2E8F0) else Color(0xFF1F293A)
    val textPrimary = if (isLightMode) Color(0xFF0F172A) else Color(0xFFF1F5F9)
    val textSecondary = if (isLightMode) Color(0xFF475569) else Color(0xFF94A3B8)
    val activeGreen = Color(0xFF22C55E)

    val colorScheme = if (isLightMode) {
        lightColorScheme(
            primary = primaryColor,
            onPrimary = contentColorForBg(primaryColor),
            secondary = secondaryColor,
            background = bgColor,
            surface = cardColor,
            surfaceVariant = elevatedColor,
            onBackground = textPrimary,
            onSurface = textPrimary
        )
    } else {
        darkColorScheme(
            primary = primaryColor,
            onPrimary = contentColorForBg(primaryColor),
            secondary = secondaryColor,
            background = bgColor,
            surface = cardColor,
            surfaceVariant = elevatedColor,
            onBackground = textPrimary,
            onSurface = textPrimary
        )
    }

    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var activePreviewKey by remember { mutableStateOf("") }
    var isPlayingPreview by remember { mutableStateOf(false) }
    var isPlayingYoutubePreview by remember { mutableStateOf(false) }
    var isPlayingSpotifyPreview by remember { mutableStateOf(false) }
    var volumePreviewJob by remember { mutableStateOf<Job?>(null) }
    var gradualPreviewJob by remember { mutableStateOf<Job?>(null) }

    fun stopPreview() {
        volumePreviewJob?.cancel()
        gradualPreviewJob?.cancel()
        try {
            previewPlayer?.stop()
            previewPlayer?.release()
        } catch (_: Exception) {}
        previewPlayer = null
        activePreviewKey = ""
        isPlayingPreview = false
        isPlayingYoutubePreview = false
        isPlayingSpotifyPreview = false
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    timeCheckTick++
                    hasOverlayPermission = Settings.canDrawOverlays(context)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        hasExactAlarmPermission = (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
                    }
                    if (isEnabled && hasExactAlarmPermission) {
                        AlarmScheduler.scheduleDailyAlarm(context, alarmHour, alarmMin)
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    stopPreview()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopPreview()
        }
    }

    fun applySystemAlarmStreamVolume(volPercent: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val targetVol = ((volPercent / 100f) * maxVol).roundToInt().coerceAtLeast(1)
            lastSyncedSystemAlarmStep = targetVol
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVol, 0)
        } catch (_: Exception) {}
    }

    fun applyMediaStreamVolumeForExternalPreview(volPercent: Int) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetMusic = ((volPercent / 100f) * maxMusic).roundToInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)
        } catch (_: Exception) {}
    }

    fun playSoundPreview(
        typeKey: String,
        autoStopAfterMs: Long? = null,
        useGradualRamp: Boolean = gradualVolumeEnabled
    ) {
        applySystemAlarmStreamVolume(alarmVolume)

        val currentKeyUri = if (typeKey == "LOCAL") savedLocalUri else ""
        if (!useGradualRamp && isPlayingPreview && previewPlayer != null && activePreviewKey == "$typeKey|$currentKeyUri") {
            try {
                previewPlayer?.setVolume(1.0f, 1.0f)
                if (autoStopAfterMs != null) {
                    volumePreviewJob?.cancel()
                    volumePreviewJob = coroutineScope.launch {
                        delay(autoStopAfterMs)
                        stopPreview()
                    }
                }
                return
            } catch (_: Exception) {}
        }

        stopPreview()
        applySystemAlarmStreamVolume(alarmVolume)

        val uri = when (typeKey) {
            "DEFAULT_1" -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            "DEFAULT_2" -> getOrCreateSynthesizedAlarmUri(context, "DEFAULT_2")
            "DEFAULT_3" -> getOrCreateSynthesizedAlarmUri(context, "DEFAULT_3")
            "LOCAL" -> if (savedLocalUri.isNotEmpty()) Uri.parse(savedLocalUri) else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        }

        try {
            val initialVol = if (useGradualRamp) 0.02f else 1.0f
            previewPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setVolume(initialVol, initialVol)
                isLooping = (autoStopAfterMs != null || useGradualRamp)
                prepare()
                start()
                activePreviewKey = "$typeKey|$currentKeyUri"
                isPlayingPreview = true
                setOnCompletionListener {
                    isPlayingPreview = false
                    activePreviewKey = ""
                }
            }

            if (useGradualRamp) {
                gradualPreviewJob = coroutineScope.launch {
                    val steps = 20
                    for (i in 1..steps) {
                        delay(220L)
                        val scale = (i.toFloat() / steps.toFloat()).coerceIn(0.02f, 1.0f)
                        try {
                            previewPlayer?.setVolume(scale, scale)
                        } catch (_: Exception) {}
                    }
                }
            }

            val effectiveAutoStop = autoStopAfterMs ?: if (useGradualRamp) 6000L else null
            if (effectiveAutoStop != null) {
                volumePreviewJob = coroutineScope.launch {
                    delay(effectiveAutoStop)
                    stopPreview()
                }
            }
        } catch (_: Exception) {}
    }

    fun startYoutubePreviewInSetup(
        urlToPlay: String = savedYoutubeUrl,
        durationMs: Long = 15000L,
        useGradualRamp: Boolean = gradualVolumeEnabled
    ) {
        if (urlToPlay.isEmpty()) return
        if (!useGradualRamp && isPlayingYoutubePreview) {
            applyMediaStreamVolumeForExternalPreview(alarmVolume)
            volumePreviewJob?.cancel()
            volumePreviewJob = coroutineScope.launch {
                delay(durationMs)
                stopPreview()
            }
            return
        }
        stopPreview()
        isPlayingYoutubePreview = true
        isPlayingPreview = true
        if (useGradualRamp) {
            applyMediaStreamVolumeForExternalPreview(10)
            gradualPreviewJob = coroutineScope.launch {
                val steps = 15
                for (i in 1..steps) {
                    delay(300L)
                    val pct = (10 + ((alarmVolume - 10) * (i.toFloat() / steps.toFloat()))).roundToInt().coerceIn(10, alarmVolume)
                    applyMediaStreamVolumeForExternalPreview(pct)
                }
            }
        } else {
            applyMediaStreamVolumeForExternalPreview(alarmVolume)
        }
        volumePreviewJob = coroutineScope.launch {
            delay(durationMs)
            stopPreview()
        }
    }

    // Ukážka Spotify hrá priamo na pozadí od 0:00 (ak ID ešte nie je načítané, okamžite ho dohľadá)
    fun startSpotifyPreviewInSetup(
        urlToPlay: String = savedSpotifyUrl,
        durationMs: Long = 15000L,
        useGradualRamp: Boolean = gradualVolumeEnabled
    ) {
        val cleanUrl = urlToPlay.trim()
        if (cleanUrl.isEmpty()) return
        if (!useGradualRamp && isPlayingSpotifyPreview) {
            applyMediaStreamVolumeForExternalPreview(alarmVolume)
            volumePreviewJob?.cancel()
            volumePreviewJob = coroutineScope.launch {
                delay(durationMs)
                stopPreview()
            }
            return
        }
        stopPreview()
        isPlayingSpotifyPreview = true
        isPlayingPreview = true

        if (resolvedSpotifyYtId.isEmpty() || prefs.getString("resolved_spotify_source_url", "") != cleanUrl) {
            coroutineScope.launch {
                isResolvingSpotify = true
                val resolved = withContext(Dispatchers.IO) {
                    resolveSpotifyTrackToBackgroundStream(cleanUrl)
                }
                isResolvingSpotify = false
                if (resolved != null) {
                    resolvedSpotifyTitle = resolved.displayTitle
                    resolvedSpotifyYtId = resolved.youtubeVideoId
                    prefs.edit()
                        .putString("resolved_spotify_title", resolved.displayTitle)
                        .putString("resolved_spotify_yt_id", resolved.youtubeVideoId)
                        .putString("resolved_spotify_source_url", cleanUrl)
                        .apply()
                }
            }
        }

        if (useGradualRamp) {
            applyMediaStreamVolumeForExternalPreview(10)
            gradualPreviewJob = coroutineScope.launch {
                val steps = 15
                for (i in 1..steps) {
                    delay(300L)
                    val pct = (10 + ((alarmVolume - 10) * (i.toFloat() / steps.toFloat()))).roundToInt().coerceIn(10, alarmVolume)
                    applyMediaStreamVolumeForExternalPreview(pct)
                }
            }
        } else {
            applyMediaStreamVolumeForExternalPreview(alarmVolume)
        }
        volumePreviewJob = coroutineScope.launch {
            delay(durationMs)
            stopPreview()
        }
    }

    fun updateVolumeAndPreview(newVol: Int) {
        val clamped = newVol.coerceIn(10, 100)
        alarmVolume = clamped
        hasUnsavedChanges = true
        applySystemAlarmStreamVolume(clamped)

        when (soundSource) {
            "LOCAL" -> {
                if (savedLocalUri.isNotEmpty()) {
                    playSoundPreview("LOCAL", autoStopAfterMs = 3500L, useGradualRamp = false)
                } else {
                    playSoundPreview("DEFAULT_1", autoStopAfterMs = 2500L, useGradualRamp = false)
                }
            }
            "YOUTUBE" -> {
                if (savedYoutubeUrl.isNotEmpty()) {
                    startYoutubePreviewInSetup(savedYoutubeUrl, durationMs = 7000L, useGradualRamp = false)
                } else {
                    playSoundPreview("DEFAULT_1", autoStopAfterMs = 2500L, useGradualRamp = false)
                }
            }
            "SPOTIFY" -> {
                if (savedSpotifyUrl.isNotEmpty()) {
                    startSpotifyPreviewInSetup(savedSpotifyUrl, durationMs = 7000L, useGradualRamp = false)
                } else {
                    playSoundPreview("DEFAULT_1", autoStopAfterMs = 2500L, useGradualRamp = false)
                }
            }
            else -> {
                playSoundPreview(soundSource, autoStopAfterMs = 2500L, useGradualRamp = false)
            }
        }
    }

    fun updateFailsafeBeepVolumeAndPreview(newVol: Int) {
        val clamped = newVol.coerceIn(10, 100)
        failsafeVolume = clamped
        hasUnsavedChanges = true
        coroutineScope.launch(Dispatchers.IO) {
            playEmergencyBeepOnce(clamped, failsafeSoundType)
        }
    }

    fun updateVibrationStrengthAndPreview(newStrength: Int) {
        val clamped = newStrength.coerceIn(5, 100)
        vibrationStrength = clamped
        hasUnsavedChanges = true
        if (vibrationEnabled) {
            previewAlarmVibration(context, clamped)
        }
    }

    fun recalculateFailsafeFromOffset(baseHour: Int, baseMin: Int, offsetMins: Int) {
        val totalFailsafeMins = (baseHour * 60 + baseMin + offsetMins) % (24 * 60)
        failsafeHour = totalFailsafeMins / 60
        failsafeMin = totalFailsafeMins % 60
    }

    fun syncOffsetFromFailsafeTime(newFailsafeHour: Int, newFailsafeMin: Int) {
        val alarmTotal = alarmHour * 60 + alarmMin
        val failsafeTotal = newFailsafeHour * 60 + newFailsafeMin
        val diff = ((failsafeTotal - alarmTotal) + 24 * 60) % (24 * 60)
        val updatedOffset = if (diff == 0) 24 * 60 else diff
        failsafeOffsetMin = updatedOffset
    }

    fun setAlarmFromDurationMinutes(durationMins: Int) {
        val clamped = durationMins.coerceIn(1, 1439)
        val now = Calendar.getInstance()
        now.add(Calendar.MINUTE, clamped)
        alarmHour = now.get(Calendar.HOUR_OF_DAY)
        alarmMin = now.get(Calendar.MINUTE)
        recalculateFailsafeFromOffset(alarmHour, alarmMin, failsafeOffsetMin)
        hasUnsavedChanges = true
    }

    fun shiftMainAlarmByMinutes(deltaMinutes: Int) {
        performUiHaptic(context)
        val totalMinutes = (alarmHour * 60 + alarmMin + deltaMinutes + 24 * 60) % (24 * 60)
        alarmHour = totalMinutes / 60
        alarmMin = totalMinutes % 60
        recalculateFailsafeFromOffset(alarmHour, alarmMin, failsafeOffsetMin)
        hasUnsavedChanges = true
    }

    fun shiftFailsafeByMinutes(deltaMinutes: Int) {
        performUiHaptic(context)
        val totalMinutes = (failsafeHour * 60 + failsafeMin + deltaMinutes + 24 * 60) % (24 * 60)
        failsafeHour = totalMinutes / 60
        failsafeMin = totalMinutes % 60
        syncOffsetFromFailsafeTime(failsafeHour, failsafeMin)
        hasUnsavedChanges = true
    }

    fun saveAndEnableAlarmNow() {
        performUiHaptic(context)
        stopPreview()
        WakeUpMotionTracker.stopMonitoring()
        isEnabled = true
        hasUnsavedChanges = false
        applySystemAlarmStreamVolume(alarmVolume)
        prefs.edit()
            .putInt("alarm_hour", alarmHour)
            .putInt("alarm_min", alarmMin)
            .putInt("failsafe_hour", failsafeHour)
            .putInt("failsafe_min", failsafeMin)
            .putInt("failsafe_offset_min_v2", failsafeOffsetMin)
            .putInt("failsafe_sound_type_v2", failsafeSoundType)
            .putInt("failsafe_volume_v3", failsafeVolume)
            .putInt("snooze_min", snoozeMinutes)
            .putInt("alarm_volume_v3", alarmVolume)
            .putBoolean("gradual_volume_enabled", gradualVolumeEnabled)
            .putBoolean("vibration_enabled", vibrationEnabled)
            .putInt("vibration_strength_v2", vibrationStrength)
            .putString("sound_source", soundSource)
            .putString("saved_local_uri", savedLocalUri)
            .putString("saved_spotify_url", savedSpotifyUrl)
            .putString("saved_youtube_url", savedYoutubeUrl)
            .putString("custom_uri", activeCustomUriForCurrentSource)
            .putString("wake_up_reason_text", wakeUpReasonText.ifBlank { strings.wakeReasonDefaultText })
            .putInt("failsafe_stage", 1)
            .putInt("alarm_escalation_boost", 0)
            .putInt("failsafe_escalation_boost", 0)
            .putInt("vib_escalation_boost", 0)
            .putInt("failsafe_snooze_count", 0)
            .putLong("first_ring_timestamp", 0L)
            .putBoolean("is_enabled", true)
            .apply()

        AlarmScheduler.scheduleDailyAlarm(context, alarmHour, alarmMin)
        AlarmScheduler.updateScheduledStatusNotification(
            context = context,
            isEnabled = true,
            alarmHour = alarmHour,
            alarmMin = alarmMin,
            failsafeHour = failsafeHour,
            failsafeMin = failsafeMin,
            strings = strings
        )
        val infoMessage = calculateTimeUntilAlarmText(alarmHour, alarmMin, appLanguage)
        Toast.makeText(context, infoMessage, Toast.LENGTH_LONG).show()
    }

    fun deactivateAlarmNow() {
        performUiHaptic(context)
        stopPreview()
        WakeUpMotionTracker.stopMonitoring()
        isEnabled = false
        hasUnsavedChanges = false
        prefs.edit()
            .putBoolean("is_enabled", false)
            .putInt("failsafe_stage", 1)
            .putInt("alarm_escalation_boost", 0)
            .putInt("failsafe_escalation_boost", 0)
            .putInt("vib_escalation_boost", 0)
            .putInt("failsafe_snooze_count", 0)
            .putLong("first_ring_timestamp", 0L)
            .apply()
        AlarmScheduler.cancelAlarm(context)
        AlarmScheduler.updateScheduledStatusNotification(
            context = context,
            isEnabled = false,
            alarmHour = alarmHour,
            alarmMin = alarmMin,
            failsafeHour = failsafeHour,
            failsafeMin = failsafeMin,
            strings = strings
        )
        context.sendBroadcast(Intent("sk.kozmali.budikprespacov.ACTION_FORCE_STOP_ALARM").setPackage(context.packageName))
        Toast.makeText(context, strings.alarmDeactivatedToast, Toast.LENGTH_SHORT).show()
    }

    fun openExternalWebUrl(url: String) {
        performUiHaptic(context)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    LaunchedEffect(incomingSharedMedia) {
        incomingSharedMedia?.let { (source, url) ->
            soundSource = source
            if (source == "YOUTUBE") {
                savedYoutubeUrl = url
                hasUnsavedChanges = true
                onSharedMediaConsumed()
                startYoutubePreviewInSetup(url)
            } else if (source == "SPOTIFY") {
                savedSpotifyUrl = url
                hasUnsavedChanges = true
                onSharedMediaConsumed()
                startSpotifyPreviewInSetup(url)
            } else {
                onSharedMediaConsumed()
            }
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(OpenMusicDocumentContract()) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            savedLocalUri = it.toString()
            soundSource = "LOCAL"
            hasUnsavedChanges = true
            playSoundPreview("LOCAL", autoStopAfterMs = 8000L)
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        if (isPlayingYoutubePreview && savedYoutubeUrl.isNotEmpty()) {
            Box(modifier = Modifier.size(1.dp).alpha(0.01f)) {
                YouTubePlayerWebView(
                    rawUrl = savedYoutubeUrl,
                    onPlaybackStarted = {},
                    onPlaybackError = {
                        isPlayingYoutubePreview = false
                        isPlayingPreview = false
                    }
                )
            }
        }

        // Prehrávač ukážky pre Spotify priamo na pozadí od 0:00 (bez spúšťania externej aplikácie Spotify)
        val spotifyPreviewStreamUrl = when {
            resolvedSpotifyYtId.isNotEmpty() -> "https://m.youtube.com/watch?v=$resolvedSpotifyYtId"
            resolvedSpotifyTitle.isNotEmpty() -> "https://m.youtube.com/results?search_query=" + URLEncoder.encode("$resolvedSpotifyTitle official audio", "UTF-8")
            else -> ""
        }
        if (isPlayingSpotifyPreview && spotifyPreviewStreamUrl.isNotEmpty()) {
            Box(modifier = Modifier.size(1.dp).alpha(0.01f)) {
                YouTubePlayerWebView(
                    rawUrl = spotifyPreviewStreamUrl,
                    onPlaybackStarted = {},
                    onPlaybackError = {
                        isPlayingSpotifyPreview = false
                        isPlayingPreview = false
                        playSoundPreview("DEFAULT_1", autoStopAfterMs = 2500L, useGradualRamp = false)
                    }
                )
            }
        }

        // Vyskakovacie okno pre minimalistické vysvetlivky ( i )
        quickInfoDialog?.let { (infoTitle, infoBody) ->
            AlertDialog(
                onDismissRequest = { quickInfoDialog = null },
                containerColor = cardColor,
                titleContentColor = textPrimary,
                textContentColor = textPrimary,
                title = {
                    Text(infoTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                text = {
                    Text(
                        text = infoBody,
                        fontSize = 14.sp,
                        color = textSecondary,
                        lineHeight = 20.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            performUiHaptic(context)
                            quickInfoDialog = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = contentColorForBg(primaryColor)
                        )
                    ) {
                        Text(strings.closeBtn, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // DIALÓG PRE NASTAVENIE DOBY DO ZAZVONENIA / DĹŽKY SPÁNKU
        if (showSleepDurationDialog) {
            var selectedDurationMins by remember {
                mutableIntStateOf(getMinutesUntilAlarm(alarmHour, alarmMin).coerceIn(1, 1439))
            }
            val durHours = selectedDurationMins / 60
            val durMins = selectedDurationMins % 60

            AlertDialog(
                onDismissRequest = { showSleepDurationDialog = false },
                containerColor = cardColor,
                titleContentColor = textPrimary,
                textContentColor = textPrimary,
                title = {
                    Text(
                        text = strings.sleepDurationDialogTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = strings.sleepDurationDialogDesc,
                            fontSize = 12.sp,
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    val next = if (selectedDurationMins <= 1) 1439 else selectedDurationMins - 1
                                    selectedDurationMins = next
                                    setAlarmFromDurationMinutes(next)
                                    performUiHaptic(context)
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("-", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }

                            Text(
                                text = "${durHours}\u00A0h ${durMins}\u00A0min",
                                fontSize = 26.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = primaryColor,
                                modifier = Modifier.padding(horizontal = 14.dp),
                                textAlign = TextAlign.Center
                            )

                            FilledTonalButton(
                                onClick = {
                                    val next = if (selectedDurationMins >= 1439) 1 else selectedDurationMins + 1
                                    selectedDurationMins = next
                                    setAlarmFromDurationMinutes(next)
                                    performUiHaptic(context)
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        CircularDurationSlider(
                            durationMinutes = selectedDurationMins,
                            primaryColor = primaryColor,
                            trackColor = elevatedColor,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            targetWakeUpTitle = strings.targetWakeTimeLabel,
                            targetWakeUpTimeText = formatTimeNoLeadingZero(alarmHour, alarmMin),
                            targetWakeUpSubtext = strings.targetWakeTimeSubtext,
                            onMinutesChange = { newMins ->
                                if (newMins != selectedDurationMins) {
                                    selectedDurationMins = newMins
                                    setAlarmFromDurationMinutes(newMins)
                                    performUiHaptic(context, isLightTick = true)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = selectedDurationMins.toFloat(),
                            onValueChange = { newVal ->
                                val nextMins = newVal.roundToInt().coerceIn(1, 1439)
                                if (nextMins != selectedDurationMins) {
                                    selectedDurationMins = nextMins
                                    setAlarmFromDurationMinutes(nextMins)
                                    performUiHaptic(context, isLightTick = true)
                                }
                            },
                            valueRange = 1f..1439f,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = primaryColor,
                                activeTrackColor = primaryColor,
                                inactiveTrackColor = elevatedColor
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            performUiHaptic(context)
                            showSleepDurationDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = contentColorForBg(primaryColor)
                        )
                    ) {
                        Text(strings.closeBtn, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // DIALÓG "O APLIKÁCII"
        if (showAboutDialog) {
            AlertDialog(
                onDismissRequest = { showAboutDialog = false },
                containerColor = cardColor,
                titleContentColor = textPrimary,
                textContentColor = textPrimary,
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = strings.aboutDialogTitle,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 19.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(elevatedColor)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = strings.aboutVersionAndDate,
                                color = textPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = strings.aboutDescription,
                            fontSize = 13.sp,
                            color = textSecondary,
                            lineHeight = 19.sp,
                            textAlign = TextAlign.Start
                        )

                        Spacer(modifier = Modifier.height(14.dp))
                        HorizontalDivider(color = elevatedColor)
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = strings.aboutAuthorLabel,
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = textPrimary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = { openExternalWebUrl("https://kozmali.sk") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryColor,
                                contentColor = contentColorForBg(primaryColor)
                            )
                        ) {
                            Text(
                                text = strings.aboutWebBtn,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = { openExternalWebUrl("https://kozmali.sk/podpora") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = secondaryColor,
                                contentColor = contentColorForBg(secondaryColor)
                            )
                        ) {
                            Text(
                                text = strings.aboutSupportBtn,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = strings.aboutFeedbackHint,
                            fontSize = 12.sp,
                            color = textSecondary,
                            lineHeight = 17.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { openExternalWebUrl("https://kozmali.sk/spatna-vazba/") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.5.dp, primaryColor)
                        ) {
                            Text(
                                text = strings.aboutFeedbackBtn,
                                color = primaryColor,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            performUiHaptic(context)
                            showAboutDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = contentColorForBg(primaryColor)
                        )
                    ) {
                        Text("OK", fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // MODRÝ HODINOVÝ KRUH PRE HLAVNÝ ČAS BUDENIA
        if (showMainTimePicker) {
            BlueThemedTimePickerDialog(
                initialHour = alarmHour,
                initialMinute = alarmMin,
                titleText = strings.dailyAlarmTitle,
                confirmText = strings.closeBtn,
                cancelText = strings.cancelBtn,
                accentColor = primaryColor,
                cardColor = cardColor,
                elevatedColor = elevatedColor,
                textPrimary = textPrimary,
                onDismiss = { showMainTimePicker = false },
                onConfirm = { h, m ->
                    alarmHour = h
                    alarmMin = m
                    recalculateFailsafeFromOffset(h, m, failsafeOffsetMin)
                    hasUnsavedChanges = true
                    showMainTimePicker = false
                }
            )
        }

        // MODRÝ HODINOVÝ KRUH PRE ČAS POISTKY
        if (showFailsafeTimePicker) {
            BlueThemedTimePickerDialog(
                initialHour = failsafeHour,
                initialMinute = failsafeMin,
                titleText = strings.failsafeTitle,
                confirmText = strings.closeBtn,
                cancelText = strings.cancelBtn,
                accentColor = primaryColor,
                cardColor = cardColor,
                elevatedColor = elevatedColor,
                textPrimary = textPrimary,
                onDismiss = { showFailsafeTimePicker = false },
                onConfirm = { h, m ->
                    failsafeHour = h
                    failsafeMin = m
                    syncOffsetFromFailsafeTime(h, m)
                    hasUnsavedChanges = true
                    showFailsafeTimePicker = false
                }
            )
        }

        // DIALÓG NASTAVENIA VIBRÁCIÍ BUDÍKA
        if (showVibrationDialog) {
            AlertDialog(
                onDismissRequest = {
                    stopAlarmVibration(context)
                    showVibrationDialog = false
                },
                containerColor = cardColor,
                titleContentColor = textPrimary,
                textContentColor = textPrimary,
                title = {
                    Text(strings.vibrationDialogTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(elevatedColor)
                                .clickable {
                                    performUiHaptic(context)
                                    vibrationEnabled = !vibrationEnabled
                                    hasUnsavedChanges = true
                                    if (vibrationEnabled) previewAlarmVibration(context, vibrationStrength)
                                    else stopAlarmVibration(context)
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = strings.vibrationEnableToggleLabel,
                                color = textPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = vibrationEnabled,
                                onCheckedChange = {
                                    performUiHaptic(context)
                                    vibrationEnabled = it
                                    hasUnsavedChanges = true
                                    if (it) previewAlarmVibration(context, vibrationStrength)
                                    else stopAlarmVibration(context)
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = strings.vibrationStrengthDesc,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = if (vibrationEnabled) {
                                "${vibrationStrength}\u00A0%"
                            } else {
                                if (appLanguage == "EN") "0\u00A0% (OFF)" else "0\u00A0% (Vyp.)"
                            },
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (vibrationEnabled) primaryColor else textSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    vibrationEnabled = true
                                    updateVibrationStrengthAndPreview(vibrationStrength - 1)
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(42.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("-", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }

                            Slider(
                                value = vibrationStrength.toFloat(),
                                enabled = vibrationEnabled,
                                onValueChange = { newVal ->
                                    val stepped = newVal.roundToInt().coerceIn(5, 100)
                                    if (stepped != vibrationStrength) {
                                        vibrationStrength = stepped
                                        performUiHaptic(context, isLightTick = true)
                                    }
                                },
                                onValueChangeFinished = {
                                    updateVibrationStrengthAndPreview(vibrationStrength)
                                },
                                valueRange = 5f..100f,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = primaryColor,
                                    activeTrackColor = primaryColor,
                                    inactiveTrackColor = elevatedColor
                                )
                            )

                            FilledTonalButton(
                                onClick = {
                                    vibrationEnabled = true
                                    updateVibrationStrengthAndPreview(vibrationStrength + 1)
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(42.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            performUiHaptic(context)
                            stopAlarmVibration(context)
                            showVibrationDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = contentColorForBg(primaryColor)
                        )
                    ) {
                        Text(strings.closeBtn, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // DIALÓG VYSVETLENIA POISTKY ČASU (scrollovateľný s odsekmi a 18.dp odstupom od posuvníka)
        if (showFailsafeInfoDialog) {
            val infoScrollState = rememberScrollState()
            AlertDialog(
                onDismissRequest = { showFailsafeInfoDialog = false },
                containerColor = cardColor,
                titleContentColor = textPrimary,
                textContentColor = textPrimary,
                title = {
                    Text(strings.failsafeInfoDialogTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 370.dp)
                            .verticalScrollbar(
                                scrollState = infoScrollState,
                                thumbColor = primaryColor.copy(alpha = 0.85f),
                                trackColor = textSecondary.copy(alpha = 0.16f)
                            )
                            .verticalScroll(infoScrollState)
                            .padding(end = 18.dp)
                    ) {
                        Text(
                            text = strings.failsafeInfoDialogBody,
                            fontSize = 14.sp,
                            color = textSecondary,
                            lineHeight = 21.sp
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            performUiHaptic(context)
                            showFailsafeInfoDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = contentColorForBg(primaryColor)
                        )
                    ) {
                        Text(strings.closeBtn, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // DIALÓG NA VÝBER 5 ZVUKOV PÍPANIA POISTKY + NEZÁVISLÝ SLIDER HLASITOSTI PÍPANIA
        if (showFailsafeSoundDialog) {
            AlertDialog(
                onDismissRequest = { showFailsafeSoundDialog = false },
                containerColor = cardColor,
                titleContentColor = textPrimary,
                textContentColor = textPrimary,
                title = {
                    Text(
                        text = strings.failsafeSoundDialogTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        lineHeight = 23.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        strings.failsafeBeepOptions.forEach { (typeId, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        performUiHaptic(context)
                                        failsafeSoundType = typeId
                                        hasUnsavedChanges = true
                                        coroutineScope.launch(Dispatchers.IO) {
                                            playEmergencyBeepOnce(failsafeVolume, typeId)
                                        }
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (failsafeSoundType == typeId),
                                    onClick = null
                                )
                                Text(
                                    text = label,
                                    color = textPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = if (failsafeSoundType == typeId) FontWeight.Bold else FontWeight.Medium,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = elevatedColor)
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "${strings.failsafeBeepVolumeLabel}: ${failsafeVolume}\u00A0%",
                            color = textPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    performUiHaptic(context)
                                    updateFailsafeBeepVolumeAndPreview(failsafeVolume - 1)
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(40.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("-", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }

                            Slider(
                                value = failsafeVolume.toFloat(),
                                onValueChange = { newVal ->
                                    val stepped = newVal.roundToInt().coerceIn(10, 100)
                                    if (stepped != failsafeVolume) {
                                        failsafeVolume = stepped
                                        performUiHaptic(context, isLightTick = true)
                                    }
                                },
                                onValueChangeFinished = {
                                    updateFailsafeBeepVolumeAndPreview(failsafeVolume)
                                },
                                valueRange = 10f..100f,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = primaryColor,
                                    activeTrackColor = primaryColor,
                                    inactiveTrackColor = elevatedColor
                                )
                            )

                            FilledTonalButton(
                                onClick = {
                                    performUiHaptic(context)
                                    updateFailsafeBeepVolumeAndPreview(failsafeVolume + 1)
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(40.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                        }

                        if (failsafeVolume < 40) { // <-- TU MENÍŠ LIMIT PRE HLAVNÚ KARTU POISTKY
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = strings.lowFailsafeVolumeWarning,
                                color = secondaryColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            performUiHaptic(context)
                            showFailsafeSoundDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = contentColorForBg(primaryColor)
                        )
                    ) {
                        Text(strings.closeBtn, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // DIALÓG NA NASTAVENIE AUTOMATICKÉHO POSUNU POISTKY
        if (showOffsetDialog) {
            AlertDialog(
                onDismissRequest = { showOffsetDialog = false },
                containerColor = cardColor,
                titleContentColor = textPrimary,
                textContentColor = textPrimary,
                title = {
                    Text(strings.failsafeOffsetDialogTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            strings.failsafeOffsetDialogDesc,
                            fontSize = 13.sp,
                            color = textSecondary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    performUiHaptic(context)
                                    failsafeOffsetMin = (failsafeOffsetMin - 1).coerceIn(1, 180)
                                    recalculateFailsafeFromOffset(alarmHour, alarmMin, failsafeOffsetMin)
                                    hasUnsavedChanges = true
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("-", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }

                            Text(
                                text = "+${failsafeOffsetMin}\u00A0${strings.minUnit}",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = primaryColor,
                                modifier = Modifier.padding(horizontal = 18.dp)
                            )

                            FilledTonalButton(
                                onClick = {
                                    performUiHaptic(context)
                                    failsafeOffsetMin = (failsafeOffsetMin + 1).coerceIn(1, 180)
                                    recalculateFailsafeFromOffset(alarmHour, alarmMin, failsafeOffsetMin)
                                    hasUnsavedChanges = true
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Slider(
                            value = failsafeOffsetMin.valueForSlider(),
                            onValueChange = {
                                val nextVal = it.roundToInt().coerceIn(1, 120)
                                if (nextVal != failsafeOffsetMin) {
                                    failsafeOffsetMin = nextVal
                                    recalculateFailsafeFromOffset(alarmHour, alarmMin, failsafeOffsetMin)
                                    hasUnsavedChanges = true
                                    performUiHaptic(context, isLightTick = true)
                                }
                            },
                            valueRange = 1f..120f,
                            modifier = Modifier.padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = primaryColor,
                                activeTrackColor = primaryColor,
                                inactiveTrackColor = elevatedColor
                            )
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            performUiHaptic(context)
                            showOffsetDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = contentColorForBg(primaryColor)
                        )
                    ) {
                        Text(strings.closeBtn, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // DIALÓG NA VÝBER 14 FARIEB
        if (showColorDialog) {
            AlertDialog(
                onDismissRequest = { showColorDialog = false },
                containerColor = cardColor,
                titleContentColor = textPrimary,
                textContentColor = textPrimary,
                title = {
                    Text(strings.colorSettingsBtn, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    performUiHaptic(context)
                                    autoComplementary = !autoComplementary
                                    prefs.edit().putBoolean("auto_comp", autoComplementary).apply()
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Checkbox(
                                checked = autoComplementary,
                                onCheckedChange = {
                                    performUiHaptic(context)
                                    autoComplementary = it
                                    prefs.edit().putBoolean("auto_comp", it).apply()
                                }
                            )
                            Text(strings.autoComplementaryLabel, fontSize = 13.sp, color = textPrimary)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(strings.primaryColorTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = textSecondary)
                        Spacer(modifier = Modifier.height(8.dp))

                        ColorGridSelector(
                            selectedKey = primaryColorKey,
                            language = appLanguage,
                            onSelect = { chosen ->
                                performUiHaptic(context)
                                primaryColorKey = chosen.key
                                prefs.edit().putString("primary_color", chosen.key).apply()
                                if (autoComplementary || chosen.key == secondaryColorKey) {
                                    secondaryColorKey = chosen.recommendedSecondaryKey
                                    prefs.edit().putString("secondary_color", chosen.recommendedSecondaryKey).apply()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(strings.secondaryColorTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = textSecondary)
                        Spacer(modifier = Modifier.height(8.dp))

                        ColorGridSelector(
                            selectedKey = secondaryColorKey,
                            language = appLanguage,
                            onSelect = { chosen ->
                                performUiHaptic(context)
                                if (chosen.key == primaryColorKey) {
                                    Toast.makeText(context, strings.sameColorWarning, Toast.LENGTH_SHORT).show()
                                } else {
                                    autoComplementary = false
                                    secondaryColorKey = chosen.key
                                    prefs.edit()
                                        .putBoolean("auto_comp", false)
                                        .putString("secondary_color", chosen.key)
                                        .apply()
                                }
                            }
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            performUiHaptic(context)
                            showColorDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = primaryColor,
                            contentColor = contentColorForBg(primaryColor)
                        )
                    ) {
                        Text(strings.closeBtn, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScrollbar(
                        scrollState = scrollState,
                        thumbColor = primaryColor.copy(alpha = 0.85f),
                        trackColor = textSecondary.copy(alpha = 0.16f)
                    )
                    .verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // HLAVIČKA S PREPÍNAČOM ZAPNUTIA A MENU 3 BODKY
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = strings.appName,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = textPrimary
                        )

                        Box {
                            FilledTonalButton(
                                onClick = {
                                    performUiHaptic(context)
                                    showTopMenu = true
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text(
                                    text = "⋮",
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = textPrimary,
                                    modifier = Modifier.offset(y = (-1.7).dp)
                                )
                            }

                            DropdownMenu(
                                expanded = showTopMenu,
                                onDismissRequest = { showTopMenu = false },
                                modifier = Modifier.background(cardColor)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(strings.lightModeLabel, color = textPrimary, fontWeight = FontWeight.SemiBold)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Switch(
                                                checked = isLightMode,
                                                onCheckedChange = {
                                                    performUiHaptic(context)
                                                    isLightMode = it
                                                    prefs.edit().putBoolean("light_mode", it).apply()
                                                }
                                            )
                                        }
                                    },
                                    onClick = {
                                        performUiHaptic(context)
                                        isLightMode = !isLightMode
                                        prefs.edit().putBoolean("light_mode", isLightMode).apply()
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (appLanguage == "SK") "Jazyk: Slovenčina (Prepni na EN)" else "Language: English (Switch to SK)",
                                            color = textPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    },
                                    onClick = {
                                        performUiHaptic(context)
                                        val oldDefault = strings.wakeReasonDefaultText
                                        appLanguage = if (appLanguage == "SK") "EN" else "SK"
                                        val newStrings = getStrings(appLanguage, failsafeOffsetMin, vibrationEnabled, vibrationStrength)
                                        if (wakeUpReasonText == oldDefault) {
                                            wakeUpReasonText = newStrings.wakeReasonDefaultText
                                        }
                                        prefs.edit()
                                            .putString("app_lang", appLanguage)
                                            .putBoolean("app_lang_manually_set", true)
                                            .apply()
                                        showTopMenu = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(strings.vibrationMenuLabel, color = textPrimary, fontWeight = FontWeight.SemiBold)
                                    },
                                    onClick = {
                                        performUiHaptic(context)
                                        showTopMenu = false
                                        showVibrationDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(strings.failsafeOffsetSettingMenu, color = textPrimary, fontWeight = FontWeight.SemiBold)
                                    },
                                    onClick = {
                                        performUiHaptic(context)
                                        showTopMenu = false
                                        showOffsetDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(strings.failsafeSoundMenu, color = textPrimary, fontWeight = FontWeight.SemiBold)
                                    },
                                    onClick = {
                                        performUiHaptic(context)
                                        showTopMenu = false
                                        showFailsafeSoundDialog = true
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(strings.colorSettingsBtn, color = textPrimary, fontWeight = FontWeight.SemiBold)
                                    },
                                    onClick = {
                                        performUiHaptic(context)
                                        showTopMenu = false
                                        showColorDialog = true
                                    }
                                )

                                HorizontalDivider(color = elevatedColor)

                                DropdownMenuItem(
                                    text = {
                                        Text(strings.aboutMenuLabel, color = textPrimary, fontWeight = FontWeight.SemiBold)
                                    },
                                    onClick = {
                                        performUiHaptic(context)
                                        showTopMenu = false
                                        showAboutDialog = true
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val activeBadgePrefix = if (isForToday) strings.statusActiveTodayBadge else strings.statusActiveTomorrowBadge

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                if (isEnabled) activeGreen.copy(alpha = 0.16f)
                                else secondaryColor.copy(alpha = 0.14f)
                            )
                            .border(
                                width = 1.5.dp,
                                color = if (isEnabled) activeGreen else secondaryColor.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .clickable {
                                if (isEnabled) deactivateAlarmNow() else saveAndEnableAlarmNow()
                            }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isEnabled) activeGreen else secondaryColor)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = if (isEnabled) {
                                    "$activeBadgePrefix • ${formatTimeNoLeadingZero(alarmHour, alarmMin)}"
                                } else {
                                    strings.statusOffBadge
                                },
                                color = textPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }

                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                if (checked) saveAndEnableAlarmNow() else deactivateAlarmNow()
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 0. KARTA POVOLENÍ
                if (!hasOverlayPermission || !hasExactAlarmPermission) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(2.dp, secondaryColor, RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(containerColor = cardColor)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                strings.permTitle,
                                color = secondaryColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                strings.permDesc,
                                color = textPrimary,
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            if (!hasOverlayPermission) {
                                Button(
                                    onClick = {
                                        performUiHaptic(context)
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = secondaryColor,
                                        contentColor = contentColorForBg(secondaryColor)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(strings.permBtn1, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = strings.permRestrictedHint,
                                        color = textSecondary,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = {
                                            performUiHaptic(context)
                                            val intent = Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                            context.startActivity(intent)
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        border = BorderStroke(1.dp, primaryColor)
                                    ) {
                                        Text(
                                            text = strings.permOpenAppInfoBtn,
                                            color = primaryColor,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }

                            if (!hasExactAlarmPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        performUiHaptic(context)
                                        val intent = Intent(
                                            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = primaryColor,
                                        contentColor = contentColorForBg(primaryColor)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(strings.permBtn2, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                                }
                            }

                            TextButton(onClick = {
                                performUiHaptic(context)
                                hasOverlayPermission = Settings.canDrawOverlays(context)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    hasExactAlarmPermission = (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
                                }
                            }) {
                                Text(strings.permRefresh, color = textSecondary)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // 1. NASTAVENIE HLAVNÉHO ČASU BUDENIA
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (isEnabled) 2.dp else 0.dp,
                            color = if (isEnabled) primaryColor.copy(alpha = 0.6f) else Color.Transparent,
                            shape = RoundedCornerShape(24.dp)
                        ),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = strings.dailyAlarmTitle,
                            color = textSecondary,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilledTonalButton(
                                onClick = { shiftMainAlarmByMinutes(-1) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(46.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("-", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        performUiHaptic(context)
                                        showMainTimePicker = true
                                    }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = formatTimeNoLeadingZero(alarmHour, alarmMin),
                                    fontSize = 54.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = if (isEnabled) textPrimary else textSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }

                            FilledTonalButton(
                                onClick = { shiftMainAlarmByMinutes(1) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(46.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("+", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = {
                                performUiHaptic(context)
                                showMainTimePicker = true
                            },
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, textPrimary.copy(alpha = 0.25f)),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = primaryColor,
                                contentColor = contentColorForBg(primaryColor)
                            )
                        ) {
                            Text(
                                strings.changeAlarmBtn,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        TextButton(
                            onClick = {
                                performUiHaptic(context)
                                showSleepDurationDialog = true
                            }
                        ) {
                            Text(
                                text = "⏳ ${formatAlarmStartsInLabel(minutesUntilAlarm, appLanguage)}",
                                color = primaryColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 2. DĹŽKA ODLOŽENIA BUDÍKA (Uspanie)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(strings.snoozeTitle, color = textSecondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    performUiHaptic(context)
                                    if (snoozeMinutes > 1) {
                                        snoozeMinutes--
                                        hasUnsavedChanges = true
                                    }
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("-", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                            Text(
                                text = "${snoozeMinutes}\u00A0${strings.minUnit}",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = textPrimary,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )
                            FilledTonalButton(
                                onClick = {
                                    performUiHaptic(context)
                                    if (snoozeMinutes < 60) {
                                        snoozeMinutes++
                                        hasUnsavedChanges = true
                                    }
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(3, 5, 10, 15).forEach { preset ->
                                val isSelected = (snoozeMinutes == preset)
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) primaryColor else primaryColor.copy(alpha = 0.16f))
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) primaryColor else primaryColor.copy(alpha = 0.55f),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            performUiHaptic(context)
                                            snoozeMinutes = preset
                                            hasUnsavedChanges = true
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${preset}\u00A0${strings.minUnit}",
                                        color = if (isSelected) contentColorForBg(primaryColor) else textPrimary,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. NASTAVENIE HLASITOSTI BUDÍKA + PREPÍNAČ POSTUPNÉHO ZVYŠOVANIA HLASITOSTI (s vysvetlivkami v ikonke "i")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${strings.volumeTitle}: ${alarmVolume}\u00A0%",
                                color = textPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SmallInfoIconButton(
                                primaryColor = primaryColor,
                                elevatedColor = elevatedColor,
                                onClick = {
                                    performUiHaptic(context)
                                    quickInfoDialog = strings.volumeTitle to strings.volumePreviewHint
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = {
                                    performUiHaptic(context)
                                    updateVolumeAndPreview(alarmVolume - 1)
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(42.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("-", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }

                            Slider(
                                value = alarmVolume.toFloat(),
                                onValueChange = { newVal ->
                                    val stepped = newVal.roundToInt().coerceIn(10, 100)
                                    if (stepped != alarmVolume) {
                                        alarmVolume = stepped
                                        performUiHaptic(context, isLightTick = true)
                                        applySystemAlarmStreamVolume(stepped)
                                    }
                                },
                                onValueChangeFinished = {
                                    updateVolumeAndPreview(alarmVolume)
                                },
                                valueRange = 10f..100f,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = primaryColor,
                                    activeTrackColor = primaryColor,
                                    inactiveTrackColor = elevatedColor
                                )
                            )

                            FilledTonalButton(
                                onClick = {
                                    performUiHaptic(context)
                                    updateVolumeAndPreview(alarmVolume + 1)
                                },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(42.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                        }

                        if (alarmVolume < 25) { // <-- TU MENÍŠ ČÍSLO 30 UPOZORNENIA HLAVNÉHO BUDÍKA (napr. na 40, ak chceš varovať skôr)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = strings.lowAlarmVolumeWarning,
                                color = secondaryColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Prepínač (Toggle Switch) pre postupné zvyšovanie hlasitosti s vysvetlivkou v ikonke ( i )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(elevatedColor)
                                .clickable {
                                    performUiHaptic(context)
                                    val newState = !gradualVolumeEnabled
                                    gradualVolumeEnabled = newState
                                    hasUnsavedChanges = true
                                    if (newState) {
                                        when (soundSource) {
                                            "YOUTUBE" -> if (savedYoutubeUrl.isNotEmpty()) startYoutubePreviewInSetup(savedYoutubeUrl, durationMs = 6000L, useGradualRamp = true) else playSoundPreview("DEFAULT_1", autoStopAfterMs = 5500L, useGradualRamp = true)
                                            "SPOTIFY" -> if (savedSpotifyUrl.isNotEmpty()) startSpotifyPreviewInSetup(savedSpotifyUrl, durationMs = 6000L, useGradualRamp = true) else playSoundPreview("DEFAULT_1", autoStopAfterMs = 5500L, useGradualRamp = true)
                                            else -> playSoundPreview(soundSource, autoStopAfterMs = 5500L, useGradualRamp = true)
                                        }
                                    } else {
                                        stopPreview()
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).padding(end = 8.dp)
                            ) {
                                Text(
                                    text = strings.gradualVolumeTitle,
                                    color = textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                SmallInfoIconButton(
                                    primaryColor = primaryColor,
                                    elevatedColor = cardColor,
                                    onClick = {
                                        performUiHaptic(context)
                                        quickInfoDialog = strings.gradualVolumeTitle to strings.gradualVolumeDesc
                                    }
                                )
                            }
                            Switch(
                                checked = gradualVolumeEnabled,
                                onCheckedChange = { newState ->
                                    performUiHaptic(context)
                                    gradualVolumeEnabled = newState
                                    hasUnsavedChanges = true
                                    if (newState) {
                                        when (soundSource) {
                                            "YOUTUBE" -> if (savedYoutubeUrl.isNotEmpty()) startYoutubePreviewInSetup(savedYoutubeUrl, durationMs = 6000L, useGradualRamp = true) else playSoundPreview("DEFAULT_1", autoStopAfterMs = 5500L, useGradualRamp = true)
                                            "SPOTIFY" -> if (savedSpotifyUrl.isNotEmpty()) startSpotifyPreviewInSetup(savedSpotifyUrl, durationMs = 6000L, useGradualRamp = true) else playSoundPreview("DEFAULT_1", autoStopAfterMs = 5500L, useGradualRamp = true)
                                            else -> playSoundPreview(soundSource, autoStopAfterMs = 5500L, useGradualRamp = true)
                                        }
                                    } else {
                                        stopPreview()
                                    }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 4. SMART POISTKA ČASU (vysvetlivka presunutá na úvod existujúceho i-čka)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = strings.failsafeTitle,
                                color = secondaryColor,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SmallInfoIconButton(
                                primaryColor = primaryColor,
                                elevatedColor = elevatedColor,
                                onClick = {
                                    performUiHaptic(context)
                                    showFailsafeInfoDialog = true
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FilledTonalButton(
                                onClick = { shiftFailsafeByMinutes(-1) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("-", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }

                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 12.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable {
                                        performUiHaptic(context)
                                        showFailsafeTimePicker = true
                                    }
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = formatTimeNoLeadingZero(failsafeHour, failsafeMin),
                                    fontSize = 48.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = secondaryColor,
                                    textAlign = TextAlign.Center
                                )
                            }

                            FilledTonalButton(
                                onClick = { shiftFailsafeByMinutes(1) },
                                shape = CircleShape,
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.size(44.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text("+", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                performUiHaptic(context)
                                showFailsafeTimePicker = true
                            },
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, textPrimary.copy(alpha = 0.25f)),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = secondaryColor,
                                contentColor = contentColorForBg(secondaryColor)
                            )
                        ) {
                            Text(
                                strings.changeFailsafeBtn,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    performUiHaptic(context)
                                    showOffsetDialog = true
                                }
                            ) {
                                Text(
                                    text = if (appLanguage == "EN") "⏱️ Offset (+${failsafeOffsetMin}\u00A0min)" else "⏱️ Posun (+${failsafeOffsetMin}\u00A0min)",
                                    color = primaryColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            TextButton(
                                onClick = {
                                    performUiHaptic(context)
                                    showFailsafeSoundDialog = true
                                }
                            ) {
                                Text(
                                    text = if (appLanguage == "EN") "🚨 Beep #${failsafeSoundType} (${failsafeVolume}\u00A0%)" else "🚨 Pípanie č.\u00A0${failsafeSoundType} (${failsafeVolume}\u00A0%)",
                                    color = primaryColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        if (failsafeVolume < 40) { // <-- TU MENÍŠ LIMIT PRE DIALÓG POISTKY
                            Text(
                                text = strings.lowFailsafeVolumeWarning,
                                color = secondaryColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                textDecoration = TextDecoration.Underline,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clickable {
                                        performUiHaptic(context)
                                        showFailsafeSoundDialog = true
                                    }
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 5. VÝBER ZVUKU BUDENIA
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(strings.soundTitle, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textPrimary)
                            if (isPlayingPreview || isPlayingYoutubePreview || isPlayingSpotifyPreview) {
                                TextButton(onClick = {
                                    performUiHaptic(context)
                                    stopPreview()
                                }) {
                                    Text(strings.stopPreview, color = primaryColor, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))

                        val options = listOf(
                            "DEFAULT_1" to strings.soundDef1,
                            "DEFAULT_2" to strings.soundDef2,
                            "DEFAULT_3" to strings.soundDef3,
                            "LOCAL" to strings.soundLocal,
                            "SPOTIFY" to strings.soundSpotify,
                            "YOUTUBE" to strings.soundYoutube
                        )

                        options.forEach { (key, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        performUiHaptic(context)
                                        soundSource = key
                                        hasUnsavedChanges = true
                                        when (key) {
                                            "DEFAULT_1", "DEFAULT_2", "DEFAULT_3" -> playSoundPreview(key)
                                            "LOCAL" -> {
                                                stopPreview()
                                                if (savedLocalUri.isNotEmpty()) {
                                                    playSoundPreview("LOCAL", autoStopAfterMs = 8000L)
                                                } else {
                                                    audioPicker.launch(arrayOf("audio/*"))
                                                }
                                            }
                                            "YOUTUBE" -> {
                                                if (savedYoutubeUrl.isNotEmpty() && extractYouTubeVideoId(savedYoutubeUrl) != null) {
                                                    startYoutubePreviewInSetup(savedYoutubeUrl)
                                                } else {
                                                    stopPreview()
                                                }
                                            }
                                            "SPOTIFY" -> {
                                                if (savedSpotifyUrl.isNotEmpty()) {
                                                    startSpotifyPreviewInSetup(savedSpotifyUrl)
                                                } else {
                                                    stopPreview()
                                                }
                                            }
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (soundSource == key),
                                    onClick = null
                                )
                                Text(label, modifier = Modifier.padding(start = 10.dp), fontSize = 14.sp, color = textPrimary)
                            }
                        }

                        if (soundSource == "LOCAL") {
                            Spacer(modifier = Modifier.height(8.dp))
                            FilledTonalButton(
                                onClick = {
                                    performUiHaptic(context)
                                    audioPicker.launch(arrayOf("audio/*"))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = elevatedColor)
                            ) {
                                Text(strings.openMusicFolder, color = textPrimary, fontWeight = FontWeight.SemiBold)
                            }
                            if (savedLocalUri.isNotEmpty()) {
                                Text(
                                    text = "${strings.selectedFile} ${Uri.parse(savedLocalUri).lastPathSegment ?: savedLocalUri}",
                                    fontSize = 12.sp,
                                    color = textSecondary,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        performUiHaptic(context)
                                        if (isPlayingPreview) stopPreview() else playSoundPreview("LOCAL")
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    border = BorderStroke(1.dp, primaryColor)
                                ) {
                                    Text(
                                        text = if (isPlayingPreview) strings.stopExternalPreviewBtn else strings.previewLocalBtn,
                                        color = primaryColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        if (soundSource == "SPOTIFY" || soundSource == "YOUTUBE") {
                            Spacer(modifier = Modifier.height(10.dp))
                            val isYoutube = (soundSource == "YOUTUBE")
                            val currentMediaUrl = if (isYoutube) savedYoutubeUrl else savedSpotifyUrl

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(primaryColor.copy(alpha = 0.14f))
                                    .border(1.dp, primaryColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = if (isYoutube) strings.youtubeShareInstruction else strings.spotifyShareInstruction,
                                    color = textPrimary,
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Button(
                                onClick = {
                                    performUiHaptic(context)
                                    stopPreview()
                                    try {
                                        val appIntent = if (isYoutube) {
                                            context.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                                                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://m.youtube.com"))
                                        } else {
                                            context.packageManager.getLaunchIntentForPackage("com.spotify.music")
                                                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com"))
                                        }
                                        context.startActivity(appIntent)
                                    } catch (_: Exception) {}
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primaryColor,
                                    contentColor = contentColorForBg(primaryColor)
                                )
                            ) {
                                Text(
                                    if (isYoutube) strings.openYoutubeBtn else strings.openSpotifyBtn,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            OutlinedButton(
                                onClick = {
                                    performUiHaptic(context)
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    if (clipText.isNotEmpty()) {
                                        if (isYoutube) {
                                            savedYoutubeUrl = clipText
                                            hasUnsavedChanges = true
                                            startYoutubePreviewInSetup(clipText)
                                        } else {
                                            savedSpotifyUrl = clipText
                                            hasUnsavedChanges = true
                                            startSpotifyPreviewInSetup(clipText)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, textSecondary.copy(alpha = 0.5f))
                            ) {
                                Text(strings.pasteClipboardBtn, color = textPrimary)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = currentMediaUrl,
                                onValueChange = {
                                    if (isYoutube) savedYoutubeUrl = it else savedSpotifyUrl = it
                                    hasUnsavedChanges = true
                                },
                                label = { Text(if (isYoutube) "YouTube URL" else "Spotify URL") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            if (!isYoutube && currentMediaUrl.isNotEmpty()) {
                                val statusText = when {
                                    isResolvingSpotify -> if (appLanguage == "EN") "⏳ Loading Spotify track..." else "⏳ Načítavam skladbu zo Spotify..."
                                    resolvedSpotifyTitle.isNotEmpty() -> "🎵 ${strings.selectedFile} $resolvedSpotifyTitle"
                                    else -> null
                                }
                                if (statusText != null) {
                                    Text(
                                        text = statusText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = primaryColor,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                }
                            }

                            if (currentMediaUrl.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        performUiHaptic(context)
                                        if (isYoutube) {
                                            if (isPlayingYoutubePreview) stopPreview() else startYoutubePreviewInSetup(savedYoutubeUrl)
                                        } else {
                                            if (isPlayingSpotifyPreview) stopPreview() else startSpotifyPreviewInSetup(savedSpotifyUrl)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    border = BorderStroke(1.5.dp, primaryColor)
                                ) {
                                    val isCurrentlyPreviewing = if (isYoutube) isPlayingYoutubePreview else isPlayingSpotifyPreview
                                    Text(
                                        text = if (isCurrentlyPreviewing) {
                                            strings.stopExternalPreviewBtn
                                        } else if (isYoutube) {
                                            strings.previewYoutubeBtn
                                        } else {
                                            strings.previewSpotifyBtn
                                        },
                                        color = primaryColor,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 6. KARTA: TEXT DÔVODU VSTÁVANIA
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = cardColor)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = strings.wakeReasonCardTitle,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = secondaryColor,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SmallInfoIconButton(
                                primaryColor = primaryColor,
                                elevatedColor = elevatedColor,
                                onClick = {
                                    performUiHaptic(context)
                                    val popupInfoText = if (appLanguage == "EN") {
                                        "Flashes in huge letters across the full screen after failed attempts to wake you up."
                                    } else {
                                        "Zobrazí sa ako veľký blikajúci nápis cez celú obrazovku, po neúspešných pokusoch zobudiť ťa."
                                    }
                                    quickInfoDialog = strings.wakeReasonCardTitle to popupInfoText
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (appLanguage == "EN") "Write your specific reason why you must get up:" else "Napíš si konkrétny dôvod, prečo musíš vstať:",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = wakeUpReasonText,
                            onValueChange = {
                                wakeUpReasonText = it
                                hasUnsavedChanges = true
                            },
                            label = { Text(strings.wakeReasonFieldLabel) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            strings.wakeReasonPresets.forEach { presetText ->
                                val isSelected = (wakeUpReasonText.trim().equals(presetText, ignoreCase = true))
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        performUiHaptic(context)
                                        wakeUpReasonText = presetText
                                        hasUnsavedChanges = true
                                    },
                                    label = {
                                        Text(
                                            text = presetText,
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = primaryColor.copy(alpha = 0.14f),
                                        labelColor = textPrimary,
                                        selectedContainerColor = primaryColor,
                                        selectedLabelColor = contentColorForBg(primaryColor)
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // 7. SPODNÉ DVE TLAČIDLÁ SO ZMENOU STAVU (DNES vs ZAJTRA)
                val formattedAlarmTime = formatTimeNoLeadingZero(alarmHour, alarmMin)
                val activeDayButtonLabel = if (isForToday) strings.btnAlarmIsActiveToday else strings.btnAlarmIsActiveTomorrow
                val topButtonText = when {
                    !isEnabled -> "${strings.btnSaveAndTurnOn} ($formattedAlarmTime)"
                    hasUnsavedChanges -> "${strings.btnSaveModifiedChanges} ($formattedAlarmTime)"
                    else -> "$activeDayButtonLabel ($formattedAlarmTime)"
                }

                Button(
                    onClick = { saveAndEnableAlarmNow() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(
                        width = if (isEnabled && !hasUnsavedChanges) 2.5.dp else 1.dp,
                        color = if (isEnabled && !hasUnsavedChanges) activeGreen else textPrimary.copy(alpha = 0.25f)
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = contentColorForBg(primaryColor)
                    )
                ) {
                    Text(
                        text = topButtonText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = { deactivateAlarmNow() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(
                        width = 2.dp,
                        color = if (isEnabled) textPrimary.copy(alpha = 0.25f) else textSecondary.copy(alpha = 0.35f)
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isEnabled) secondaryColor else elevatedColor,
                        contentColor = if (isEnabled) contentColorForBg(secondaryColor) else textSecondary
                    )
                ) {
                    Text(
                        text = if (isEnabled) strings.btnDeactivateActive else strings.btnAlreadyDeactivated,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

private fun Int.valueForSlider(): Float = this.coerceIn(1, 120).toFloat()

@Composable
fun ColorGridSelector(
    selectedKey: String,
    language: String,
    onSelect: (PaletteColor) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppColorPalette.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    val isSelected = (item.key == selectedKey)
                    val label = if (language == "EN") item.nameEn else item.nameSk
                    val textCol = contentColorForBg(item.color)

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(item.color)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) textCol else Color.Gray.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelect(item) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isSelected) "✓ $label" else label,
                            color = textCol,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

object AlarmScheduler {
    fun scheduleDailyAlarm(context: Context, hour: Int, min: Int) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                return
            }
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, min)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val showIntent = Intent(context, MainActivity::class.java)
            val showPendingIntent = PendingIntent.getActivity(
                context, 105, showIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmClockInfo = AlarmManager.AlarmClockInfo(calendar.timeInMillis, showPendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (_: SecurityException) {
        } catch (_: Exception) {}
    }

    fun scheduleSnooze(context: Context, snoozeMinutes: Int) {
        try {
            WakeUpMotionTracker.stopMonitoring()
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                return
            }
            val intent = Intent(context, AlarmReceiver::class.java).putExtra("IS_SNOOZE", true)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 101, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = System.currentTimeMillis() + (snoozeMinutes * 60 * 1000L)
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (_: SecurityException) {
        } catch (_: Exception) {}
    }

    fun scheduleWakeupCheck(context: Context, delayMinutes: Int = 2) {
        try {
            WakeUpMotionTracker.startMonitoring(context)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                return
            }
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("IS_SNOOZE", true)
                putExtra("IS_WAKEUP_CHECK", true)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 102, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerTime = System.currentTimeMillis() + (delayMinutes * 60 * 1000L)
            val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerTime, pendingIntent)
            alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
        } catch (_: SecurityException) {
        } catch (_: Exception) {}
    }

    fun updateScheduledStatusNotification(
        context: Context,
        isEnabled: Boolean,
        alarmHour: Int,
        alarmMin: Int,
        failsafeHour: Int,
        failsafeMin: Int,
        strings: AppStrings
    ) {
        try {
            val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "budik_active_status_channel"
            if (!isEnabled) {
                notifManager.cancel(888)
                return
            }
            val channel = NotificationChannel(
                channelId,
                strings.appName,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
            notifManager.createNotificationChannel(channel)

            val openIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val openPending = PendingIntent.getActivity(
                context, 108, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmTimeStr = formatTimeNoLeadingZero(alarmHour, alarmMin)
            val failsafeTimeStr = formatTimeNoLeadingZero(failsafeHour, failsafeMin)

            val notif = Notification.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("${strings.activeNotifTitlePrefix} $alarmTimeStr")
                .setContentText("${strings.activeNotifBodyPrefix} $failsafeTimeStr")
                .setContentIntent(openPending)
                .setOngoing(true)
                .build()

            notifManager.notify(888, notif)
        } catch (_: Exception) {}
    }

    fun cancelAlarm(context: Context) {
        try {
            WakeUpMotionTracker.stopMonitoring()
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context, 100, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val snoozeIntent = PendingIntent.getBroadcast(
                context, 101, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val wakeupCheckIntent = PendingIntent.getBroadcast(
                context, 102, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            alarmManager.cancel(pendingIntent)
            alarmManager.cancel(snoozeIntent)
            alarmManager.cancel(wakeupCheckIntent)
        } catch (_: Exception) {}
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("budik_pre_spacov_prefs", Context.MODE_PRIVATE)
        val isSnooze = intent.getBooleanExtra("IS_SNOOZE", false)
        val isWakeupCheck = intent.getBooleanExtra("IS_WAKEUP_CHECK", false)

        if (isWakeupCheck) {
            val confirmedByMotion = WakeUpMotionTracker.isUserConfirmedAwakeByMotion()
            WakeUpMotionTracker.stopMonitoring()
            if (confirmedByMotion) {
                prefs.edit()
                    .putInt("failsafe_stage", 1)
                    .putInt("alarm_escalation_boost", 0)
                    .putInt("failsafe_escalation_boost", 0)
                    .putInt("vib_escalation_boost", 0)
                    .putInt("failsafe_snooze_count", 0)
                    .putLong("first_ring_timestamp", 0L)
                    .apply()
                return
            }
        } else {
            WakeUpMotionTracker.stopMonitoring()
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val cpuWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BudikPreSpacov::AlarmCpuWakeLock"
        )
        cpuWakeLock.acquire(20 * 1000L)

        @Suppress("DEPRECATION")
        val screenWakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            "BudikPreSpacov::AlarmScreenWakeLock"
        )
        screenWakeLock.acquire(10 * 1000L)

        val lang = resolveInitialLanguage(prefs)
        val offset = prefs.getInt("failsafe_offset_min_v2", 15)
        val vibEnabled = prefs.getBoolean("vibration_enabled", false)
        val vibStrength = prefs.getInt("vibration_strength_v2", 25)
        val strings = getStrings(lang, offset, vibEnabled, vibStrength)

        if (!isSnooze && !isWakeupCheck) {
            prefs.edit()
                .putInt("failsafe_stage", 1)
                .putInt("alarm_escalation_boost", 0)
                .putInt("failsafe_escalation_boost", 0)
                .putInt("vib_escalation_boost", 0)
                .putInt("failsafe_snooze_count", 0)
                .putLong("first_ring_timestamp", System.currentTimeMillis())
                .apply()
            if (prefs.getBoolean("is_enabled", true)) {
                val h = prefs.getInt("alarm_hour", 7)
                val m = prefs.getInt("alarm_min", 45)
                AlarmScheduler.scheduleDailyAlarm(context, h, m)
            }
        } else if (prefs.getLong("first_ring_timestamp", 0L) == 0L) {
            prefs.edit().putLong("first_ring_timestamp", System.currentTimeMillis()).apply()
        }

        val ringIntent = Intent(context, AlarmRingActivity::class.java).apply {
            putExtra("IS_WAKEUP_CHECK", isWakeupCheck)
            putExtra("FROM_ALARM_RECEIVER", true)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }

        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "budik_pre_spacov_silent_v2"
        val channel = NotificationChannel(
            channelId,
            strings.appName,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        notifManager.createNotificationChannel(channel)

        // Zrušíme prípadnú starú notifikáciu 999, aby Android vždy vynútil vyskočenie okna nad zámok
        notifManager.cancel(999)

        val fullScreenPending = PendingIntent.getActivity(
            context, 200, ringIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(strings.appName)
            .setContentText(if (isWakeupCheck) strings.line5WakeCheckTitle.replace("\n", " ") else strings.snoozeAlarmUpper)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPending, true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        notifManager.notify(999, notification)

        try {
            context.startActivity(ringIntent)
        } catch (_: Exception) {}
    }
}

class AlarmRingActivity : ComponentActivity() {
    private var mediaPlayer: MediaPlayer? = null
    private var activeSoundSource: String = "DEFAULT_1"
    private var isFailsafeCurrentlyActive: Boolean = false
    private var isActivelyRingingSession: Boolean = true
    private var wakeupCheckModeState = mutableStateOf(false)
    private var ringSessionTrigger = mutableIntStateOf(0)
    private var lastSessionStartRealtimeMs: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private val forceStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopAllSoundsAndDismiss()
        }
    }

    private fun applyImmersiveGestureProtection() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        )
            }
        } catch (_: Exception) {}
    }

    private fun wakeScreenAndShowOverLockscreen() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
            applyImmersiveGestureProtection()
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "BudikPreSpacov::RingActivityWakeLock"
            )
            wl.acquire(6000L)
        } catch (_: Exception) {}
    }

    private fun bringAlarmRingToFront() {
        if (isFinishing || isDestroyed || !isActivelyRingingSession) return
        try {
            wakeScreenAndShowOverLockscreen()
            val frontIntent = Intent(this, AlarmRingActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
            val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "budik_pre_spacov_silent_v2"
            val fullScreenPending = PendingIntent.getActivity(
                this, 200, frontIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = Notification.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Budík pre spáčov")
                .setContentText("POISTKA ČASU")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPending, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()
            notifManager.cancel(999)
            notifManager.notify(999, notification)

            startActivity(frontIntent)
        } catch (_: Exception) {}
    }

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        wakeScreenAndShowOverLockscreen()
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        isActivelyRingingSession = true
        lastSessionStartRealtimeMs = SystemClock.elapsedRealtime()
        wakeupCheckModeState.value = intent?.getBooleanExtra("IS_WAKEUP_CHECK", false) == true
        intent?.removeExtra("FROM_ALARM_RECEIVER")

        // Zablokovanie náhodného gesta Späť počas zvonenia
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Gesto Späť ignorujeme, aby sa budík v polospánku nezhodil z obrazovky
            }
        })

        applyImmersiveGestureProtection()

        val stopFilter = IntentFilter("sk.kozmali.budikprespacov.ACTION_FORCE_STOP_ALARM")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(forceStopReceiver, stopFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(forceStopReceiver, stopFilter)
        }

        val prefs = getSharedPreferences("budik_pre_spacov_prefs", Context.MODE_PRIVATE)
        val failsafeHour = prefs.getInt("failsafe_hour", 8)
        val failsafeMin = prefs.getInt("failsafe_min", 0)
        val failsafeOffset = prefs.getInt("failsafe_offset_min_v2", 15)
        val failsafeSoundType = prefs.getInt("failsafe_sound_type_v2", 5)
        val baseFailsafeVolume = prefs.getInt("failsafe_volume_v3", 62)
        val snoozeMinutes = prefs.getInt("snooze_min", 5)
        val baseAlarmVolume = prefs.getInt("alarm_volume_v3", 47)
        val gradualVolumeEnabled = prefs.getBoolean("gradual_volume_enabled", true)
        val vibrationEnabled = prefs.getBoolean("vibration_enabled", false)
        val baseVibrationStrength = prefs.getInt("vibration_strength_v2", 25)
        val soundSource = prefs.getString("sound_source", "DEFAULT_1") ?: "DEFAULT_1"

        val legacyUri = prefs.getString("custom_uri", "") ?: ""
        val customUri = when (soundSource) {
            "LOCAL" -> prefs.getString("saved_local_uri", legacyUri) ?: legacyUri
            "SPOTIFY" -> prefs.getString("saved_spotify_url", legacyUri) ?: legacyUri
            "YOUTUBE" -> prefs.getString("saved_youtube_url", legacyUri) ?: legacyUri
            else -> legacyUri
        }
        activeSoundSource = soundSource

        val isLightMode = prefs.getBoolean("light_mode", false)
        val appLanguage = resolveInitialLanguage(prefs)
        val primaryColor = getPaletteColor(prefs.getString("primary_color", "BLUE") ?: "BLUE", "BLUE").color
        val secondaryColor = getPaletteColor(prefs.getString("secondary_color", "RED") ?: "RED", "RED").color
        val strings = getStrings(appLanguage, failsafeOffset, vibrationEnabled, baseVibrationStrength)
        val wakeUpReasonText = (prefs.getString("wake_up_reason_text", strings.wakeReasonDefaultText)
            ?: strings.wakeReasonDefaultText).ifBlank { strings.wakeReasonDefaultText }

        val bgColor = if (isLightMode) Color(0xFFF1F5F9) else Color(0xFF0B0F17)
        val borderContrast = if (isLightMode) Color(0xFF0F172A).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.25f)

        setContent {
            val sessionKey by ringSessionTrigger
            val isWakeupCheckMode by wakeupCheckModeState
            var isClosingScreen by remember(sessionKey) { mutableStateOf(false) }

            // Načítanie (alebo okamžité dohľadanie) audio streamu pre vybranú Spotify skladbu
            var activeSpotifyYtId by remember(sessionKey) {
                mutableStateOf(prefs.getString("resolved_spotify_yt_id", "") ?: "")
            }
            var activeSpotifyTitle by remember(sessionKey) {
                mutableStateOf(prefs.getString("resolved_spotify_title", "") ?: "")
            }

            LaunchedEffect(sessionKey, soundSource, customUri) {
                if (soundSource == "SPOTIFY" && customUri.isNotEmpty() && activeSpotifyYtId.isEmpty()) {
                    val resolved = withContext(Dispatchers.IO) {
                        resolveSpotifyTrackToBackgroundStream(customUri)
                    }
                    if (resolved != null) {
                        activeSpotifyTitle = resolved.displayTitle
                        activeSpotifyYtId = resolved.youtubeVideoId
                        prefs.edit()
                            .putString("resolved_spotify_title", resolved.displayTitle)
                            .putString("resolved_spotify_yt_id", resolved.youtubeVideoId)
                            .putString("resolved_spotify_source_url", customUri.trim())
                            .apply()
                    }
                }
            }

            var isFailsafeActive by remember(sessionKey) {
                mutableStateOf(
                    if (isWakeupCheckMode) true else checkIfPastFailsafe(failsafeHour, failsafeMin)
                )
            }
            var failsafeStage by remember(sessionKey) {
                mutableIntStateOf(prefs.getInt("failsafe_stage", 1).coerceIn(1, 4))
            }

            var alarmEscalationBoost by remember(sessionKey) {
                mutableIntStateOf(prefs.getInt("alarm_escalation_boost", 0).coerceIn(0, 100))
            }
            var failsafeEscalationBoost by remember(sessionKey) {
                mutableIntStateOf(prefs.getInt("failsafe_escalation_boost", 0).coerceIn(0, 100))
            }
            var vibEscalationBoost by remember(sessionKey) {
                mutableIntStateOf(prefs.getInt("vib_escalation_boost", 0).coerceIn(0, 100))
            }

            var failsafeSnoozeCount by remember(sessionKey) {
                mutableIntStateOf(prefs.getInt("failsafe_snooze_count", 0))
            }
            val firstRingTimestamp = remember(sessionKey) {
                val ts = prefs.getLong("first_ring_timestamp", 0L)
                if (ts == 0L) System.currentTimeMillis() else ts
            }
            var is40MinLockoutActive by remember(sessionKey) {
                mutableStateOf((System.currentTimeMillis() - firstRingTimestamp) >= 40 * 60 * 1000L)
            }

            fun saveNormalEscalationToPrefsOnly() {
                val nextAlarmBoost = (alarmEscalationBoost + 5).coerceAtMost(100)
                val nextVibBoost = (vibEscalationBoost + 10).coerceAtMost(100)
                prefs.edit()
                    .putInt("alarm_escalation_boost", nextAlarmBoost)
                    .putInt("vib_escalation_boost", nextVibBoost)
                    .apply()
            }

            fun saveFailsafeEscalationToPrefsOnly() {
                val nextFailsafeBoost = (failsafeEscalationBoost + 5).coerceAtMost(100)
                val nextVibBoost = (vibEscalationBoost + 10).coerceAtMost(100)
                prefs.edit()
                    .putInt("failsafe_escalation_boost", nextFailsafeBoost)
                    .putInt("vib_escalation_boost", nextVibBoost)
                    .apply()
            }

            fun escalateFailsafeInPlace() {
                val nextFailsafeBoost = (failsafeEscalationBoost + 5).coerceAtMost(100)
                val nextVibBoost = (vibEscalationBoost + 10).coerceAtMost(100)
                failsafeEscalationBoost = nextFailsafeBoost
                vibEscalationBoost = nextVibBoost
                prefs.edit()
                    .putInt("failsafe_escalation_boost", nextFailsafeBoost)
                    .putInt("vib_escalation_boost", nextVibBoost)
                    .apply()
            }

            fun performSnoozeCleanly(minutesToSnooze: Int, isFailsafeSnooze: Boolean) {
                if (isClosingScreen) return
                isClosingScreen = true
                isActivelyRingingSession = false
                stopEmergencyBeepImmediately()
                stopAllSounds()
                performUiHaptic(this@AlarmRingActivity)
                if (isFailsafeSnooze) {
                    saveFailsafeEscalationToPrefsOnly()
                    val nextCnt = failsafeSnoozeCount + 1
                    prefs.edit().putInt("failsafe_snooze_count", nextCnt).apply()
                } else {
                    saveNormalEscalationToPrefsOnly()
                }
                AlarmScheduler.scheduleSnooze(this@AlarmRingActivity, minutesToSnooze)
                cancelAlarmNotification()
                finish()
            }

            var line2Phase by remember(sessionKey) { mutableStateOf("BEEPING") }
            var showLine2CancelCircle by remember(sessionKey) { mutableStateOf(false) }
            var line2SilenceRemainingSec by remember(sessionKey) { mutableIntStateOf(45) }
            var line2WindowRemainingSec by remember(sessionKey) { mutableIntStateOf(10) }

            var line3CaughtCount by remember(sessionKey) { mutableIntStateOf(0) }
            var line3OffsetX by remember(sessionKey) { mutableIntStateOf(Random.nextInt(-85, 86)) }
            var line3OffsetY by remember(sessionKey) { mutableIntStateOf(Random.nextInt(-110, 111)) }

            var isHoldingLine4 by remember(sessionKey) { mutableStateOf(false) }
            var line4HoldProgress by remember(sessionKey) { mutableFloatStateOf(0f) }

            var wakeupCheckSecondsLeft by remember(sessionKey) { mutableIntStateOf(50) }

            var unattendedSeconds by remember(sessionKey) { mutableIntStateOf(0) }
            var inactivityBoostPercent by remember(sessionKey) { mutableIntStateOf(0) }

            var flashToggle by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(450)
                    flashToggle = !flashToggle
                }
            }

            LaunchedEffect(failsafeStage, line2Phase, line3CaughtCount, isHoldingLine4) {
                unattendedSeconds = 0
            }

            LaunchedEffect(sessionKey, isFailsafeActive, failsafeStage, line2Phase, isWakeupCheckMode) {
                if (!isWakeupCheckMode && isFailsafeActive && failsafeStage == 2 && line2Phase == "BEEPING") {
                    showLine2CancelCircle = false
                    delay(4000)
                    showLine2CancelCircle = true
                }
            }

            LaunchedEffect(line2Phase) {
                if (line2Phase == "COUNTDOWN_45") {
                    stopEmergencyBeepImmediately()
                    stopMediaPlayerOnly()
                    stopAlarmVibration(this@AlarmRingActivity)
                    line2SilenceRemainingSec = 45
                    while (line2SilenceRemainingSec > 0 && !isClosingScreen) {
                        delay(1000)
                        line2SilenceRemainingSec--
                    }
                    if (!isClosingScreen) {
                        line2Phase = "WINDOW_10S"
                    }
                } else if (line2Phase == "WINDOW_10S") {
                    withContext(Dispatchers.IO) {
                        playGentleWakeCheckPromptBeep()
                    }
                    performUiHaptic(this@AlarmRingActivity)
                    line2WindowRemainingSec = 10
                    while (line2WindowRemainingSec > 0 && !isClosingScreen) {
                        delay(1000)
                        line2WindowRemainingSec--
                    }
                    if (!isClosingScreen) {
                        unattendedSeconds = 0
                        escalateFailsafeInPlace()
                        failsafeStage = 3
                        prefs.edit().putInt("failsafe_stage", 3).apply()
                        line2Phase = "BEEPING"
                    }
                }
            }

            LaunchedEffect(isHoldingLine4, failsafeStage) {
                if (failsafeStage == 4 && isHoldingLine4 && !isClosingScreen) {
                    stopEmergencyBeepImmediately()
                    stopAlarmVibration(this@AlarmRingActivity)
                    val stepMs = 50L
                    val totalSteps = 4000f / stepMs
                    var currentStep = (line4HoldProgress * totalSteps).toInt()
                    while (isHoldingLine4 && currentStep < totalSteps && !isClosingScreen) {
                        delay(stepMs)
                        currentStep++
                        line4HoldProgress = (currentStep / totalSteps).coerceIn(0f, 1f)
                        if (currentStep % 10 == 0) {
                            performUiHaptic(this@AlarmRingActivity, isLightTick = true)
                        }
                    }
                    if (line4HoldProgress >= 1f && !isClosingScreen) {
                        isClosingScreen = true
                        isActivelyRingingSession = false
                        stopEmergencyBeepImmediately()
                        stopAllSounds()
                        performUiHaptic(this@AlarmRingActivity)
                        saveFailsafeEscalationToPrefsOnly()
                        prefs.edit().putInt("failsafe_stage", 2).apply()
                        AlarmScheduler.scheduleWakeupCheck(this@AlarmRingActivity, 2)
                        cancelAlarmNotification()
                        finish()
                    }
                } else if (!isHoldingLine4) {
                    line4HoldProgress = 0f
                }
            }

            LaunchedEffect(sessionKey, isWakeupCheckMode) {
                if (isWakeupCheckMode && !isClosingScreen) {
                    stopEmergencyBeepImmediately()
                    stopMediaPlayerOnly()
                    stopAlarmVibration(this@AlarmRingActivity)
                    wakeupCheckSecondsLeft = 50
                    withContext(Dispatchers.IO) {
                        playGentleWakeCheckPromptBeep()
                    }
                    while (wakeupCheckSecondsLeft > 0 && wakeupCheckModeState.value && !isClosingScreen) {
                        delay(1000)
                        wakeupCheckSecondsLeft--
                        if (wakeupCheckSecondsLeft == 30 || wakeupCheckSecondsLeft == 15 || wakeupCheckSecondsLeft == 5) {
                            withContext(Dispatchers.IO) {
                                playGentleWakeCheckPromptBeep()
                            }
                        }
                    }
                    if (wakeupCheckModeState.value && wakeupCheckSecondsLeft <= 0 && !isClosingScreen) {
                        unattendedSeconds = 0
                        inactivityBoostPercent = 0
                        escalateFailsafeInPlace()
                        failsafeStage = 2
                        line2Phase = "BEEPING"
                        prefs.edit().putInt("failsafe_stage", 2).apply()
                        isFailsafeActive = true
                        isFailsafeCurrentlyActive = true
                        wakeupCheckModeState.value = false
                        bringAlarmRingToFront()
                    }
                }
            }

            var minutesPastFailsafe by remember { mutableIntStateOf(getMinutesPastFailsafe(failsafeHour, failsafeMin)) }
            var currentTimeText by remember { mutableStateOf(getCurrentFormattedTime()) }
            var nextSnoozeTimeText by remember { mutableStateOf(getNextSnoozeFormattedTime(snoozeMinutes)) }
            var webStreamAudioConfirmedPlaying by remember(sessionKey) { mutableStateOf(false) }
            var emergencySynthFallbackNeeded by remember(sessionKey) { mutableStateOf(false) }

            LaunchedEffect(sessionKey, isFailsafeActive, isWakeupCheckMode, gradualVolumeEnabled, alarmEscalationBoost, inactivityBoostPercent) {
                if (!isFailsafeActive && !isWakeupCheckMode && !isClosingScreen) {
                    val targetMaxVol = (baseAlarmVolume + alarmEscalationBoost + inactivityBoostPercent).coerceIn(10, 100)
                    if (gradualVolumeEnabled) {
                        val totalRampSteps = 25
                        for (step in 0..totalRampSteps) {
                            if (isClosingScreen || isFailsafeActive || isWakeupCheckMode) break
                            val fraction = step.toFloat() / totalRampSteps.toFloat()
                            val currentPct = (targetMaxVol * fraction).roundToInt()
                            applyProgressiveAlarmVolume(currentPct, targetMaxVol, soundSource)
                            delay(1000L)
                        }
                    } else {
                        setAlarmAndMediaVolumesForMainAlarm(targetMaxVol, soundSource)
                    }
                }
            }

            LaunchedEffect(sessionKey, isFailsafeActive, isWakeupCheckMode) {
                if (isClosingScreen) return@LaunchedEffect
                isFailsafeCurrentlyActive = isFailsafeActive
                unattendedSeconds = 0
                inactivityBoostPercent = 0

                val targetAlarmVol = (baseAlarmVolume + alarmEscalationBoost).coerceIn(10, 100)
                val effectiveVib = (baseVibrationStrength + vibEscalationBoost).coerceIn(5, 100)
                if (!gradualVolumeEnabled) {
                    setAlarmAndMediaVolumesForMainAlarm(targetAlarmVol, soundSource)
                }

                if (!isFailsafeActive && !isWakeupCheckMode) {
                    val ok = startConfiguredSound(soundSource, customUri, gradualVolumeEnabled)
                    if (!ok) {
                        emergencySynthFallbackNeeded = true
                    }
                    if (vibrationEnabled) {
                        startContinuousAlarmVibration(this@AlarmRingActivity, effectiveVib)
                    }
                }

                var silentSecondsCounter = 0
                while (!isClosingScreen) {
                    delay(1000)
                    if (isClosingScreen) break
                    currentTimeText = getCurrentFormattedTime()
                    nextSnoozeTimeText = getNextSnoozeFormattedTime(snoozeMinutes)
                    is40MinLockoutActive = (System.currentTimeMillis() - firstRingTimestamp) >= 40 * 60 * 1000L

                    val nowPast = checkIfPastFailsafe(failsafeHour, failsafeMin)
                    minutesPastFailsafe = getMinutesPastFailsafe(failsafeHour, failsafeMin)
                    if (nowPast && !isFailsafeActive && !isWakeupCheckMode) {
                        isFailsafeActive = true
                        isFailsafeCurrentlyActive = true
                        bringAlarmRingToFront()
                    }

                    // Ak sa aktivita počas aktívneho zvonenia ocitla v pozadí (napr. po geste Domov), vytiahneme ju späť na obrazovku
                    if (isActivelyRingingSession && !is40MinLockoutActive &&
                        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        bringAlarmRingToFront()
                    }

                    val isActivelyRingingOrBeeping = !isWakeupCheckMode &&
                            !(isFailsafeActive && failsafeStage == 2 && line2Phase != "BEEPING") &&
                            !(isFailsafeActive && failsafeStage == 4 && isHoldingLine4)

                    if (isActivelyRingingOrBeeping) {
                        unattendedSeconds++

                        if (unattendedSeconds == 5 * 60) {
                            inactivityBoostPercent = 15
                            if (!isFailsafeActive) {
                                val boostedVol = (baseAlarmVolume + alarmEscalationBoost + 15).coerceIn(10, 100)
                                setAlarmAndMediaVolumesForMainAlarm(boostedVol, soundSource)
                                mediaPlayer?.setVolume(1.0f, 1.0f)
                                if (vibrationEnabled) {
                                    val boostedVib = (baseVibrationStrength + vibEscalationBoost + 15).coerceIn(5, 100)
                                    startContinuousAlarmVibration(this@AlarmRingActivity, boostedVib)
                                }
                            }
                        } else if (unattendedSeconds == 10 * 60) {
                            inactivityBoostPercent = 30
                            if (!isFailsafeActive) {
                                val boostedVol = (baseAlarmVolume + alarmEscalationBoost + 30).coerceIn(10, 100)
                                setAlarmAndMediaVolumesForMainAlarm(boostedVol, soundSource)
                                mediaPlayer?.setVolume(1.0f, 1.0f)
                                if (vibrationEnabled) {
                                    val boostedVib = (baseVibrationStrength + vibEscalationBoost + 30).coerceIn(5, 100)
                                    startContinuousAlarmVibration(this@AlarmRingActivity, boostedVib)
                                }
                            }
                        } else if (unattendedSeconds >= 15 * 60) {
                            isClosingScreen = true
                            isActivelyRingingSession = false
                            stopEmergencyBeepImmediately()
                            prefs.edit()
                                .putBoolean("is_enabled", false)
                                .putInt("failsafe_stage", 1)
                                .putInt("alarm_escalation_boost", 0)
                                .putInt("failsafe_escalation_boost", 0)
                                .putInt("vib_escalation_boost", 0)
                                .putInt("failsafe_snooze_count", 0)
                                .putLong("first_ring_timestamp", 0L)
                                .apply()
                            AlarmScheduler.cancelAlarm(this@AlarmRingActivity)
                            AlarmScheduler.updateScheduledStatusNotification(
                                context = this@AlarmRingActivity,
                                isEnabled = false,
                                alarmHour = prefs.getInt("alarm_hour", 7),
                                alarmMin = prefs.getInt("alarm_min", 45),
                                failsafeHour = failsafeHour,
                                failsafeMin = failsafeMin,
                                strings = strings
                            )
                            stopAllSoundsAndDismiss()
                            break
                        }
                    }

                    if (!isFailsafeActive && !isWakeupCheckMode && !emergencySynthFallbackNeeded) {
                        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val isAnySoundPlaying = (mediaPlayer?.isPlaying == true) || am.isMusicActive ||
                                ((soundSource == "YOUTUBE" || soundSource == "SPOTIFY") && webStreamAudioConfirmedPlaying)
                        if (!isAnySoundPlaying) {
                            silentSecondsCounter++
                            val limitSec = if (soundSource == "YOUTUBE" || soundSource == "SPOTIFY") 6 else 4
                            if (silentSecondsCounter >= limitSec) {
                                val fallbackOk = playFallbackDefaultAlarm(gradualVolumeEnabled)
                                if (!fallbackOk) {
                                    emergencySynthFallbackNeeded = true
                                }
                                silentSecondsCounter = 0
                            }
                        } else {
                            silentSecondsCounter = 0
                        }
                    }
                }
            }

            val shouldBeepFailsafe = !isClosingScreen && ((isFailsafeActive && !isWakeupCheckMode &&
                    !(failsafeStage == 2 && line2Phase != "BEEPING") &&
                    !(failsafeStage == 4 && isHoldingLine4)) ||
                    (!isFailsafeActive && !isWakeupCheckMode && emergencySynthFallbackNeeded))

            LaunchedEffect(sessionKey, shouldBeepFailsafe, failsafeSoundType, baseFailsafeVolume, failsafeEscalationBoost, vibEscalationBoost, inactivityBoostPercent, gradualVolumeEnabled) {
                if (shouldBeepFailsafe && !isClosingScreen) {
                    if (isFailsafeActive) {
                        isFailsafeCurrentlyActive = true
                        stopMediaPlayerOnly()
                        stopAlarmVibration(this@AlarmRingActivity)
                        bringAlarmRingToFront()
                    }
                    val totalFailsafeBoost = (failsafeEscalationBoost + inactivityBoostPercent).coerceAtMost(100)
                    val totalVibBoost = (vibEscalationBoost + inactivityBoostPercent).coerceAtMost(100)
                    val targetMaxBeepVol = (baseFailsafeVolume + totalFailsafeBoost).coerceIn(10, 100)

                    setAlarmAndMediaVolumesForMainAlarm(targetMaxBeepVol, "DEFAULT_1")

                    val pauseMs = if (failsafeSoundType == 2) 1000L else 2100L
                    var beepStepIndex = 0
                    val rampBeepCount = 10

                    while (!isClosingScreen) {
                        val currentBeepTargetVol = if (gradualVolumeEnabled) {
                            val startVol = 10
                            if (targetMaxBeepVol <= startVol) {
                                targetMaxBeepVol
                            } else {
                                val progress = (beepStepIndex.toFloat() / rampBeepCount.toFloat()).coerceIn(0f, 1f)
                                (startVol + ((targetMaxBeepVol - startVol) * progress)).roundToInt().coerceIn(10, targetMaxBeepVol)
                            }
                        } else {
                            targetMaxBeepVol
                        }
                        beepStepIndex++

                        val forcedFailsafeVib = if (isFailsafeActive) {
                            when {
                                beepStepIndex == 3 -> 25
                                beepStepIndex == 4 -> 50
                                beepStepIndex >= 5 -> 100
                                else -> 0
                            }
                        } else 0

                        val normalVib = if (vibrationEnabled) {
                            (baseVibrationStrength + totalVibBoost).coerceIn(5, 100)
                        } else 0

                        val effectiveBeepVib = maxOf(normalVib, forcedFailsafeVib)

                        if (effectiveBeepVib > 0 && !isClosingScreen) {
                            previewAlarmVibration(this@AlarmRingActivity, effectiveBeepVib)
                        }
                        withContext(Dispatchers.IO) {
                            if (!isClosingScreen) {
                                playEmergencyBeepOnce(currentBeepTargetVol, failsafeSoundType, 0)
                            }
                        }
                        if (isClosingScreen) break
                        delay(pauseMs)
                    }
                }
            }

            MaterialTheme {
                val screenBg = when {
                    is40MinLockoutActive -> if (flashToggle) Color(0xFF450A0A) else Color.Black
                    isWakeupCheckMode -> Color(0xFF0B0F17)
                    isFailsafeActive && failsafeStage == 2 -> Color.Black
                    isFailsafeActive && failsafeStage == 4 -> if (flashToggle) Color(0xFF3B080C) else Color(0xFF090A0F)
                    else -> bgColor
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(screenBg)
                        .systemBarsPadding()
                        .padding(14.dp)
                ) {
                    // Prehrávač na pozadí pre YouTube video aj pre vybranú Spotify skladbu (hrá od 0:00 bez otvárania Spotify aplikácie)
                    val backgroundStreamUrl = when (soundSource) {
                        "YOUTUBE" -> customUri
                        "SPOTIFY" -> when {
                            activeSpotifyYtId.isNotEmpty() -> "https://m.youtube.com/watch?v=$activeSpotifyYtId"
                            activeSpotifyTitle.isNotEmpty() -> "https://m.youtube.com/results?search_query=" + URLEncoder.encode("$activeSpotifyTitle official audio", "UTF-8")
                            else -> ""
                        }
                        else -> ""
                    }

                    if ((soundSource == "YOUTUBE" || soundSource == "SPOTIFY") &&
                        !isFailsafeActive && !isWakeupCheckMode && !isClosingScreen && backgroundStreamUrl.isNotEmpty()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(1.dp)
                                .alpha(0.01f)
                        ) {
                            YouTubePlayerWebView(
                                rawUrl = backgroundStreamUrl,
                                onPlaybackStarted = {
                                    webStreamAudioConfirmedPlaying = true
                                },
                                onPlaybackError = {
                                    if (!playFallbackDefaultAlarm(gradualVolumeEnabled)) {
                                        emergencySynthFallbackNeeded = true
                                    }
                                }
                            )
                        }
                    }

                    // --- EXTRÉMNA POISTKA PO 40 MINÚTACH: ZABLOKOVANÉ ZRUŠENIE ---
                    if (is40MinLockoutActive) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color.Black)
                                .border(3.dp, secondaryColor, RoundedCornerShape(28.dp))
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = formatFailsafeExceededText(minutesPastFailsafe, appLanguage),
                                    color = secondaryColor,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = currentTimeText,
                                    color = if (flashToggle) Color.White else secondaryColor,
                                    fontSize = 68.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = wakeUpReasonText.uppercase(Locale.getDefault()),
                                    color = if (flashToggle) Color(0xFFFACC15) else Color.White,
                                    fontSize = 32.sp,
                                    lineHeight = 38.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = strings.lockout40MinTitle,
                                    color = secondaryColor,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = strings.lockout40MinDesc,
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Button(
                                onClick = {
                                    isActivelyRingingSession = false
                                    performUiHaptic(this@AlarmRingActivity)
                                    val openAppIntent = Intent(this@AlarmRingActivity, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    }
                                    startActivity(openAppIntent)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(78.dp),
                                shape = RoundedCornerShape(22.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primaryColor,
                                    contentColor = contentColorForBg(primaryColor)
                                )
                            ) {
                                Text(
                                    text = strings.lockout40MinOpenAppBtn,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Black,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else if (isWakeupCheckMode) {
                        // --- 5. LÍNIA ZÁCHRANY: KONTROLA BDELOSTI O 2 MINÚTY (50s ODPOČET) ---
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(28.dp))
                                .background(Color(0xFF151C28))
                                .border(2.dp, primaryColor, RoundedCornerShape(28.dp))
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = currentTimeText,
                                color = Color(0xFFE2E8F0).copy(alpha = 0.85f),
                                fontSize = 68.sp,
                                fontWeight = FontWeight.ExtraBold
                            )

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = strings.line5WakeCheckTitle,
                                    color = Color.White,
                                    fontSize = 30.sp,
                                    lineHeight = 38.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "${wakeupCheckSecondsLeft}\u00A0s",
                                    color = secondaryColor,
                                    fontSize = 56.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = strings.line5WakeCheckSubtitle,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Button(
                                onClick = {
                                    if (isClosingScreen) return@Button
                                    isClosingScreen = true
                                    isActivelyRingingSession = false
                                    stopEmergencyBeepImmediately()
                                    WakeUpMotionTracker.stopMonitoring()
                                    performUiHaptic(this@AlarmRingActivity)
                                    prefs.edit()
                                        .putInt("failsafe_stage", 1)
                                        .putInt("alarm_escalation_boost", 0)
                                        .putInt("failsafe_escalation_boost", 0)
                                        .putInt("vib_escalation_boost", 0)
                                        .putInt("failsafe_snooze_count", 0)
                                        .putLong("first_ring_timestamp", 0L)
                                        .apply()
                                    setAlarmAndMediaVolumesForMainAlarm(baseAlarmVolume, soundSource)
                                    stopAllSoundsAndDismiss()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(96.dp),
                                shape = RoundedCornerShape(24.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF22C55E),
                                    contentColor = Color(0xFF052E16)
                                )
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    val btnLabel = "${strings.line5ConfirmAwakeBtn} (${wakeupCheckSecondsLeft}\u00A0s)"
                                    Text(
                                        text = btnLabel,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = btnLabel,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Black,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.offset(x = 0.9.dp, y = 0.3.dp)
                                    )
                                }
                            }
                        }
                    } else if (!isFailsafeActive) {
                        // --- ŠTANDARDNÝ REŽIM PRED POISTKOU ČASU ---
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(0.27f)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(secondaryColor)
                                    .border(2.dp, borderContrast, RoundedCornerShape(28.dp))
                                    .clickable {
                                        if (isClosingScreen) return@clickable
                                        isClosingScreen = true
                                        isActivelyRingingSession = false
                                        stopEmergencyBeepImmediately()
                                        stopAllSounds()
                                        performUiHaptic(this@AlarmRingActivity)
                                        saveNormalEscalationToPrefsOnly()
                                        AlarmScheduler.scheduleWakeupCheck(this@AlarmRingActivity, 2)
                                        cancelAlarmNotification()
                                        finish()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = strings.cancelAlarmUpper,
                                    color = contentColorForBg(secondaryColor),
                                    fontSize = 26.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(0.73f)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(primaryColor)
                                    .border(2.dp, borderContrast, RoundedCornerShape(28.dp))
                                    .clickable {
                                        performSnoozeCleanly(snoozeMinutes, isFailsafeSnooze = false)
                                    }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .padding(top = 20.dp)
                                        .fillMaxWidth(0.92f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = currentTimeText,
                                        color = subtleClockColorForBg(primaryColor),
                                        fontSize = 92.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.5.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Text(
                                    text = "${strings.snoozeAlarmUpper}\n${strings.snoozeInPrefix}\u00A0${snoozeMinutes}\u00A0${strings.minUnit}\n\n${strings.itWillBeAlreadyPrefix}\u00A0$nextSnoozeTimeText",
                                    color = contentColorForBg(primaryColor),
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    textAlign = TextAlign.Center,
                                    lineHeight = 42.sp,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(top = 72.dp, start = 12.dp, end = 12.dp)
                                )
                            }
                        }
                    } else {
                        // --- KRÍZOVÝ REŽIM PO PREKROČENÍ POISTKY ČASU (LÍNIE 1 AŽ 4) ---
                        val canStillSnoozeInFailsafe = (failsafeSnoozeCount < 1)

                        when (failsafeStage) {
                            1 -> {
                                // === 1. LÍNIA ZÁCHRANY: FALOŠNÉ ZRUŠENIE ("ZRUŠIŤ POZOR!" a karta "ODLOŽIŤ BUDÍK o X min BUDE Y:ZZ") ===
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(28.dp))
                                        .background(bgColor)
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = formatFailsafeExceededText(minutesPastFailsafe, appLanguage),
                                        color = secondaryColor,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 24.sp,
                                        lineHeight = 28.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Box(
                                        modifier = Modifier
                                            .size(189.dp)
                                            .clip(CircleShape)
                                            .background(secondaryColor)
                                            .border(3.dp, borderContrast, CircleShape)
                                            .clickable {
                                                if (isClosingScreen) return@clickable
                                                isClosingScreen = true
                                                isActivelyRingingSession = false
                                                stopEmergencyBeepImmediately()
                                                stopAllSounds()
                                                performUiHaptic(this@AlarmRingActivity)
                                                saveFailsafeEscalationToPrefsOnly()
                                                prefs.edit().putInt("failsafe_stage", 2).apply()
                                                AlarmScheduler.scheduleSnooze(this@AlarmRingActivity, 1)
                                                cancelAlarmNotification()
                                                finish()
                                            }
                                            .padding(12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = strings.cancelWordUpper,
                                                color = contentColorForBg(secondaryColor),
                                                fontSize = 24.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = strings.riskSubtext,
                                                color = contentColorForBg(secondaryColor),
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                lineHeight = 22.sp,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    if (canStillSnoozeInFailsafe) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .clip(RoundedCornerShape(28.dp))
                                                .background(primaryColor)
                                                .border(2.dp, borderContrast, RoundedCornerShape(28.dp))
                                                .clickable {
                                                    performSnoozeCleanly(snoozeMinutes, isFailsafeSnooze = true)
                                                }
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopCenter)
                                                    .padding(top = 18.dp)
                                                    .fillMaxWidth(0.92f),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = currentTimeText,
                                                    color = subtleClockColorForBg(primaryColor),
                                                    fontSize = 86.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    letterSpacing = 1.5.sp,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    textAlign = TextAlign.Center
                                                )
                                            }

                                            Text(
                                                text = "${strings.snoozeAlarmUpper}\n${strings.snoozeInPrefix}\u00A0${snoozeMinutes}\u00A0${strings.minUnit}\n\n${strings.itWillBeAlreadyPrefix}\u00A0$nextSnoozeTimeText",
                                                color = contentColorForBg(primaryColor),
                                                fontSize = 34.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                textAlign = TextAlign.Center,
                                                lineHeight = 40.sp,
                                                modifier = Modifier
                                                    .align(Alignment.Center)
                                                    .padding(top = 56.dp, start = 12.dp, end = 12.dp)
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .clip(RoundedCornerShape(28.dp))
                                                .background(Color.Black)
                                                .border(
                                                    width = 3.dp,
                                                    color = if (flashToggle) Color.White else Color(0xFFFF5252),
                                                    shape = RoundedCornerShape(28.dp)
                                                )
                                                .padding(20.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = currentTimeText,
                                                    color = Color.White.copy(alpha = 0.7f),
                                                    fontSize = 52.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                                Spacer(modifier = Modifier.height(14.dp))
                                                Text(
                                                    text = wakeUpReasonText.uppercase(Locale.getDefault()),
                                                    color = if (flashToggle) Color.White else Color(0xFFFACC15),
                                                    fontSize = 38.sp,
                                                    lineHeight = 45.sp,
                                                    fontWeight = FontWeight.Black,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.alpha(if (flashToggle) 1f else 0.25f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            2 -> {
                                // === 2. LÍNIA ZÁCHRANY: ČIERNA OBRAZOVKA + VEĽKÝ BLIKAJÚCI TEXT + KRÚŽOK PO 4s + 45s ODPOČET + 10s OKNO ===
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black)
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = formatFailsafeExceededText(minutesPastFailsafe, appLanguage),
                                        color = Color(0xFFFF5252),
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 22.sp,
                                        lineHeight = 26.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    if (line2Phase == "BEEPING") {
                                        Box(
                                            modifier = Modifier.size(128.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (showLine2CancelCircle) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(128.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(0xFFD32F2F))
                                                        .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                                        .clickable {
                                                            stopEmergencyBeepImmediately()
                                                            performUiHaptic(this@AlarmRingActivity)
                                                            line2Phase = "COUNTDOWN_45"
                                                        }
                                                        .padding(8.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = strings.cancelWordUpper,
                                                        color = Color.White,
                                                        fontSize = 18.sp,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        textAlign = TextAlign.Center
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(12.dp))

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .weight(1f)
                                                .clip(RoundedCornerShape(28.dp))
                                                .background(Color.Black)
                                                .border(
                                                    width = 3.dp,
                                                    color = if (flashToggle) Color.White else Color(0xFFFF5252),
                                                    shape = RoundedCornerShape(28.dp)
                                                )
                                                .padding(20.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                Text(
                                                    text = currentTimeText,
                                                    color = Color.White.copy(alpha = 0.65f),
                                                    fontSize = 52.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                                Spacer(modifier = Modifier.height(16.dp))
                                                Text(
                                                    text = wakeUpReasonText.uppercase(Locale.getDefault()),
                                                    color = if (flashToggle) Color.White else Color(0xFFFACC15),
                                                    fontSize = 40.sp,
                                                    lineHeight = 48.sp,
                                                    fontWeight = FontWeight.Black,
                                                    textAlign = TextAlign.Center,
                                                    modifier = Modifier.alpha(if (flashToggle) 1f else 0.25f)
                                                )
                                            }
                                        }
                                    } else if (line2Phase == "COUNTDOWN_45") {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = currentTimeText,
                                                color = Color.White.copy(alpha = 0.9f),
                                                fontSize = 76.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                textAlign = TextAlign.Center
                                            )

                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = wakeUpReasonText.uppercase(Locale.getDefault()),
                                                    color = Color(0xFFFACC15),
                                                    fontSize = 24.sp,
                                                    lineHeight = 30.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    textAlign = TextAlign.Center
                                                )
                                                Spacer(modifier = Modifier.height(18.dp))
                                                Text(
                                                    text = strings.line2SilenceTitle,
                                                    color = Color(0xFF94A3B8),
                                                    fontSize = 17.sp,
                                                    lineHeight = 23.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    textAlign = TextAlign.Center
                                                )
                                                Spacer(modifier = Modifier.height(10.dp))
                                                Text(
                                                    text = "${line2SilenceRemainingSec}\u00A0s",
                                                    color = primaryColor,
                                                    fontSize = 84.sp,
                                                    fontWeight = FontWeight.ExtraBold
                                                )
                                            }

                                            Text(
                                                text = strings.line2SilenceDesc,
                                                color = Color(0xFFE2E8F0),
                                                fontSize = 16.sp,
                                                lineHeight = 23.sp,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(bottom = 12.dp)
                                            )
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = currentTimeText,
                                                color = Color.White.copy(alpha = 0.85f),
                                                fontSize = 64.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text(
                                                text = "${line2WindowRemainingSec}\u00A0s",
                                                color = Color(0xFF22C55E),
                                                fontSize = 68.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                            Spacer(modifier = Modifier.height(20.dp))
                                            Button(
                                                onClick = {
                                                    if (isClosingScreen) return@Button
                                                    isClosingScreen = true
                                                    isActivelyRingingSession = false
                                                    stopEmergencyBeepImmediately()
                                                    stopAllSounds()
                                                    performUiHaptic(this@AlarmRingActivity)
                                                    saveFailsafeEscalationToPrefsOnly()
                                                    prefs.edit().putInt("failsafe_stage", 2).apply()
                                                    AlarmScheduler.scheduleWakeupCheck(this@AlarmRingActivity, 2)
                                                    cancelAlarmNotification()
                                                    finish()
                                                },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(124.dp),
                                                shape = RoundedCornerShape(28.dp),
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color(0xFF22C55E),
                                                    contentColor = Color(0xFF052E16)
                                                )
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Text(
                                                        text = strings.line2ConfirmWindowBtn,
                                                        fontSize = 28.sp,
                                                        lineHeight = 35.sp,
                                                        fontWeight = FontWeight.Black,
                                                        textAlign = TextAlign.Center
                                                    )
                                                    Text(
                                                        text = strings.line2ConfirmWindowBtn,
                                                        fontSize = 28.sp,
                                                        lineHeight = 35.sp,
                                                        fontWeight = FontWeight.Black,
                                                        textAlign = TextAlign.Center,
                                                        modifier = Modifier.offset(x = 0.9.dp, y = 0.3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            3 -> {
                                // === 3. LÍNIA ZÁCHRANY: UHÝBAJÚCI KRÚŽOK (CHYŤ MA 3-KRÁT) ===
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(28.dp))
                                        .background(bgColor)
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = formatFailsafeExceededText(minutesPastFailsafe, appLanguage),
                                        color = secondaryColor,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 20.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = strings.line3CatchInstruction,
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 17.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )

                                    Spacer(modifier = Modifier.height(15.dp))
                                    Text(
                                        text = currentTimeText,
                                        color = if (flashToggle) Color(0xFFFF5252) else Color.White,
                                        fontSize = 56.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.alpha(if (flashToggle) 1f else 0.25f)
                                    )

                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val circleDiameterDp = 140
                                        val maxJumpX = ((maxWidth.value.toInt() - circleDiameterDp) / 2 - 6).coerceAtLeast(50)
                                        val maxJumpY = ((maxHeight.value.toInt() - circleDiameterDp) / 2 - 6).coerceAtLeast(70)

                                        Box(
                                            modifier = Modifier
                                                .offset(
                                                    x = line3OffsetX.coerceIn(-maxJumpX, maxJumpX).dp,
                                                    y = line3OffsetY.coerceIn(-maxJumpY, maxJumpY).dp
                                                )
                                                .size(circleDiameterDp.dp)
                                                .clip(CircleShape)
                                                .background(secondaryColor)
                                                .border(3.dp, Color.White, CircleShape)
                                                .clickable {
                                                    performUiHaptic(this@AlarmRingActivity)
                                                    val nextCount = line3CaughtCount + 1
                                                    if (nextCount >= 3) {
                                                        stopEmergencyBeepImmediately()
                                                        escalateFailsafeInPlace()
                                                        failsafeStage = 4
                                                        prefs.edit().putInt("failsafe_stage", 4).apply()
                                                    } else {
                                                        line3CaughtCount = nextCount
                                                        val (newX, newY) = generateFarRandomJump(
                                                            currentX = line3OffsetX,
                                                            currentY = line3OffsetY,
                                                            maxOffsetX = maxJumpX,
                                                            maxOffsetY = maxJumpY
                                                        )
                                                        line3OffsetX = newX
                                                        line3OffsetY = newY
                                                    }
                                                }
                                                .padding(10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    text = "${strings.cancelWordUpper}\n(${line3CaughtCount + 1}/3)",
                                                    color = contentColorForBg(secondaryColor),
                                                    fontSize = 22.sp,
                                                    lineHeight = 26.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }

                                    if (canStillSnoozeInFailsafe) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(110.dp)
                                                .clip(RoundedCornerShape(24.dp))
                                                .background(primaryColor)
                                                .clickable {
                                                    performSnoozeCleanly(snoozeMinutes, isFailsafeSnooze = true)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${strings.snoozeAlarmUpper} ${strings.snoozeInPrefix}\u00A0${snoozeMinutes}\u00A0${strings.minUnit}\n${strings.itWillBeAlreadyPrefix}\u00A0$nextSnoozeTimeText",
                                                color = contentColorForBg(primaryColor),
                                                fontSize = 22.sp,
                                                lineHeight = 28.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(horizontal = 12.dp)
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(110.dp)
                                                .clip(RoundedCornerShape(24.dp))
                                                .background(Color.Black)
                                                .border(2.dp, if (flashToggle) Color.White else secondaryColor, RoundedCornerShape(24.dp))
                                                .padding(12.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = wakeUpReasonText.uppercase(Locale.getDefault()),
                                                color = if (flashToggle) Color.White else Color(0xFFFACC15),
                                                fontSize = 22.sp,
                                                lineHeight = 27.sp,
                                                fontWeight = FontWeight.Black,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }

                            else -> {
                                // === 4. LÍNIA ZÁCHRANY: DLHÉ PODRŽANIE NA 4 SEKUNDY (POČAS DRŽANIA TICHO) ===
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = formatFailsafeExceededText(minutesPastFailsafe, appLanguage),
                                            color = Color(0xFFFF5252),
                                            fontSize = 22.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            textAlign = TextAlign.Center
                                        )
                                        Text(
                                            text = currentTimeText,
                                            color = Color.White.copy(alpha = 0.85f),
                                            fontSize = 54.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Text(
                                            text = strings.line4AreYouAwake.uppercase(Locale.getDefault()),
                                            color = if (flashToggle) Color(0xFFFF5252) else Color.White,
                                            fontSize = 36.sp,
                                            lineHeight = 42.sp,
                                            fontWeight = FontWeight.Black,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.alpha(if (flashToggle) 1f else 0.35f)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(230.dp)
                                            .pointerInput(Unit) {
                                                detectTapGestures(
                                                    onPress = {
                                                        isHoldingLine4 = true
                                                        tryAwaitRelease()
                                                        isHoldingLine4 = false
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            val strokePx = 14.dp.toPx()
                                            drawArc(
                                                color = Color.White.copy(alpha = 0.2f),
                                                startAngle = 0f,
                                                sweepAngle = 360f,
                                                useCenter = false,
                                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                            )
                                            drawArc(
                                                color = Color(0xFF22C55E),
                                                startAngle = -90f,
                                                sweepAngle = 360f * line4HoldProgress,
                                                useCenter = false,
                                                style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(196.dp)
                                                .clip(CircleShape)
                                                .background(if (isHoldingLine4) Color(0xFFB71C1C) else secondaryColor),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            val remainingHoldSec = ((1f - line4HoldProgress) * 4f).coerceAtLeast(0f)
                                            Text(
                                                text = if (isHoldingLine4) {
                                                    String.format(Locale.US, "%.1f\u00A0s", remainingHoldSec)
                                                } else {
                                                    strings.line4HoldInstruction
                                                },
                                                color = Color.White,
                                                fontSize = if (isHoldingLine4) 38.sp else 18.sp,
                                                lineHeight = if (isHoldingLine4) 42.sp else 24.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.padding(12.dp)
                                            )
                                        }
                                    }

                                    if (canStillSnoozeInFailsafe) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(96.dp)
                                                .clip(RoundedCornerShape(24.dp))
                                                .background(primaryColor)
                                                .clickable {
                                                    performSnoozeCleanly(snoozeMinutes, isFailsafeSnooze = true)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${strings.snoozeAlarmUpper} ${strings.snoozeInPrefix}\u00A0${snoozeMinutes}\u00A0${strings.minUnit}",
                                                color = contentColorForBg(primaryColor),
                                                fontSize = 24.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(96.dp)
                                                .clip(RoundedCornerShape(24.dp))
                                                .background(Color.Black)
                                                .border(2.dp, if (flashToggle) Color.White else secondaryColor, RoundedCornerShape(24.dp))
                                                .padding(10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = wakeUpReasonText.uppercase(Locale.getDefault()),
                                                color = if (flashToggle) Color.White else Color(0xFFFACC15),
                                                fontSize = 20.sp,
                                                lineHeight = 25.sp,
                                                fontWeight = FontWeight.Black,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        isActivelyRingingSession = true
        wakeScreenAndShowOverLockscreen()
        if (intent.getBooleanExtra("FROM_ALARM_RECEIVER", false)) {
            intent.removeExtra("FROM_ALARM_RECEIVER")
            val nowElapsed = SystemClock.elapsedRealtime()
            val newWakeCheck = intent.getBooleanExtra("IS_WAKEUP_CHECK", false)
            if (nowElapsed - lastSessionStartRealtimeMs > 1500L || newWakeCheck != wakeupCheckModeState.value) {
                lastSessionStartRealtimeMs = nowElapsed
                wakeupCheckModeState.value = newWakeCheck
                ringSessionTrigger.intValue++
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isActivelyRingingSession) {
            applyImmersiveGestureProtection()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isActivelyRingingSession) {
            wakeScreenAndShowOverLockscreen()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isActivelyRingingSession && !isFinishing && !isDestroyed) {
            mainHandler.postDelayed({
                if (isActivelyRingingSession && !isFinishing && !isDestroyed) {
                    bringAlarmRingToFront()
                }
            }, 350L)
        }
    }

    private fun getCurrentFormattedTime(): String {
        val now = Calendar.getInstance()
        return formatTimeNoLeadingZero(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
    }

    private fun getSignedMinutesDiffFromFailsafe(failsafeHour: Int, failsafeMin: Int): Int {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val failsafeMinutes = failsafeHour * 60 + failsafeMin
        var diff = (currentMinutes - failsafeMinutes) % (24 * 60)
        if (diff < -720) diff += 24 * 60
        if (diff > 720) diff -= 24 * 60
        return diff
    }

    private fun checkIfPastFailsafe(failsafeHour: Int, failsafeMin: Int): Boolean {
        return getSignedMinutesDiffFromFailsafe(failsafeHour, failsafeMin) >= 0
    }

    private fun getMinutesPastFailsafe(failsafeHour: Int, failsafeMin: Int): Int {
        return getSignedMinutesDiffFromFailsafe(failsafeHour, failsafeMin).coerceAtLeast(1)
    }

    private fun applyProgressiveAlarmVolume(currentPct: Int, maxTargetPct: Int, source: String) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val targetAlarmStep = ((maxTargetPct / 100f) * maxAlarm).roundToInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarmStep, 0)

            val playerScale = if (maxTargetPct > 0) {
                (currentPct.toFloat() / maxTargetPct.toFloat()).coerceIn(0.01f, 1.0f)
            } else 1.0f
            try {
                mediaPlayer?.setVolume(playerScale, playerScale)
            } catch (_: Exception) {}

            if (source == "SPOTIFY" || source == "YOUTUBE") {
                val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetMusicStep = ((currentPct / 100f) * maxMusic).roundToInt().coerceIn(0, maxMusic)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusicStep, 0)
            }
        } catch (_: Exception) {}
    }

    private fun setAlarmAndMediaVolumesForMainAlarm(volPercent: Int, source: String) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val targetAlarm = ((volPercent / 100f) * maxAlarm).roundToInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)

            if (source == "SPOTIFY" || source == "YOUTUBE") {
                val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val targetMusic = ((volPercent / 100f) * maxMusic).roundToInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)
            }
        } catch (_: Exception) {}
    }

    private fun playFallbackDefaultAlarm(gradualVolume: Boolean = false): Boolean {
        if (isFailsafeCurrentlyActive || isFinishing || isDestroyed) return true
        if (mediaPlayer?.isPlaying == true) return true
        val fallbackUris = listOfNotNull(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            getOrCreateSynthesizedAlarmUri(this, "DEFAULT_3")
        )
        for (uri in fallbackUris) {
            if (playUri(uri, gradualVolume)) return true
        }
        return false
    }

    private fun startConfiguredSound(source: String, customUri: String, gradualVolume: Boolean): Boolean {
        return try {
            when (source) {
                "DEFAULT_1" -> playUri(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), gradualVolume) || playFallbackDefaultAlarm(gradualVolume)
                "DEFAULT_2" -> playUri(getOrCreateSynthesizedAlarmUri(this, "DEFAULT_2"), gradualVolume) || playFallbackDefaultAlarm(gradualVolume)
                "DEFAULT_3" -> playUri(getOrCreateSynthesizedAlarmUri(this, "DEFAULT_3"), gradualVolume) || playFallbackDefaultAlarm(gradualVolume)
                "LOCAL" -> {
                    if (customUri.isNotEmpty() && playUri(Uri.parse(customUri), gradualVolume)) true
                    else playFallbackDefaultAlarm(gradualVolume)
                }
                "SPOTIFY" -> {
                    if (customUri.isEmpty() || !isInternetAvailable(this)) {
                        playFallbackDefaultAlarm(gradualVolume)
                    } else {
                        true
                    }
                }
                "YOUTUBE" -> {
                    if (customUri.isEmpty() || !isInternetAvailable(this) || extractYouTubeVideoId(customUri) == null) {
                        playFallbackDefaultAlarm(gradualVolume)
                    } else {
                        true
                    }
                }
                else -> playFallbackDefaultAlarm(gradualVolume)
            }
        } catch (_: Exception) {
            playFallbackDefaultAlarm(gradualVolume)
        }
    }

    private fun playUri(uri: Uri, startMutedForGradual: Boolean = false): Boolean {
        stopMediaPlayerOnly()
        return try {
            val initVol = if (startMutedForGradual) 0.02f else 1.0f
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@AlarmRingActivity, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setVolume(initVol, initVol)
                isLooping = true
                prepare()
                start()
            }
            mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            stopMediaPlayerOnly()
            false
        }
    }

    private fun cancelAlarmNotification() {
        val notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifManager.cancel(999)
    }

    private fun stopAllSoundsAndDismiss() {
        isActivelyRingingSession = false
        mainHandler.removeCallbacksAndMessages(null)
        stopEmergencyBeepImmediately()
        stopAllSounds()
        cancelAlarmNotification()
        finish()
    }

    private fun stopMediaPlayerOnly() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }

    private fun stopAllSounds() {
        isActivelyRingingSession = false
        mainHandler.removeCallbacksAndMessages(null)
        stopEmergencyBeepImmediately()
        stopMediaPlayerOnly()
        stopAlarmVibration(this)
    }

    override fun onDestroy() {
        isActivelyRingingSession = false
        mainHandler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(forceStopReceiver) } catch (_: Exception) {}
        stopAllSounds()
        super.onDestroy()
    }
}

// Prehrávač na pozadí s 200 ms bufferom od 0:00 pre YouTube aj Spotify skladby
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubePlayerWebView(
    rawUrl: String,
    onPlaybackStarted: () -> Unit,
    onPlaybackError: () -> Unit
) {
    val videoId = remember(rawUrl) { extractYouTubeVideoId(rawUrl) }
    val targetUrl = remember(videoId, rawUrl) {
        if (videoId != null) {
            "https://m.youtube.com/watch?v=$videoId&autoplay=1"
        } else {
            rawUrl
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                cm.setAcceptThirdPartyCookies(this, true)
                cm.setCookie("https://www.youtube.com", "CONSENT=YES+cb.20230531-04-p0.en+FX+908")
                cm.setCookie("https://m.youtube.com", "CONSENT=YES+cb.20230531-04-p0.en+FX+908")

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(
                            """
                            (function() {
                                if (window.__ytAlarmInterval) clearInterval(window.__ytAlarmInterval);
                                window.__ytBufferingDone = false;
                                window.__ytBufferingStarted = false;
                                var tickFn = function() {
                                    var buttons = document.querySelectorAll('button');
                                    for (var b = 0; b < buttons.length; b++) {
                                        var txt = (buttons[b].innerText || '').toLowerCase();
                                        if (txt.indexOf('prijať') !== -1 || txt.indexOf('accept') !== -1 || txt.indexOf('súhlasím') !== -1) {
                                            buttons[b].click();
                                        }
                                    }
                                    // Ak sme na stránke výsledkov vyhľadávania, klikneme na prvú skladbu
                                    if (location.pathname.indexOf('/results') !== -1 && !window.__ytSearchClicked) {
                                        var firstLink = document.querySelector('a[href*="/watch?v="]');
                                        if (firstLink) {
                                            window.__ytSearchClicked = true;
                                            firstLink.click();
                                            return;
                                        }
                                    }
                                    var v = document.querySelector('video');
                                    if (v) {
                                        v.loop = true;
                                        if (!window.__ytBufferingStarted) {
                                            window.__ytBufferingStarted = true;
                                            v.muted = true;
                                            v.currentTime = 0;
                                            v.pause();
                                            setTimeout(function() {
                                                window.__ytBufferingDone = true;
                                                v.currentTime = 0;
                                                v.muted = false;
                                                v.volume = 1.0;
                                                v.play().catch(function(e){});
                                            }, 200);
                                        } else if (window.__ytBufferingDone) {
                                            v.muted = false;
                                            v.volume = 1.0;
                                            if (v.paused) {
                                                v.play().catch(function(e){});
                                            }
                                        }
                                    }
                                    if (window.__ytBufferingDone) {
                                        var unmuteBtn = document.querySelector('.ytp-unmute, button[aria-label*="Unmute"], button[aria-label*="Zrušiť stlmenie"]');
                                        if (unmuteBtn && unmuteBtn.offsetParent !== null) {
                                            unmuteBtn.click();
                                        }
                                        var playBtn = document.querySelector('.ytp-large-play-button, button[aria-label*="Play"], button[aria-label*="Prehrať"]');
                                        if (playBtn && playBtn.offsetParent !== null) {
                                            playBtn.click();
                                        }
                                    }
                                };
                                tickFn();
                                window.__ytAlarmInterval = setInterval(tickFn, 350);
                            })();
                            """.trimIndent(),
                            null
                        )

                        view?.postDelayed({
                            view.evaluateJavascript(
                                """
                                (function() {
                                    var v = document.querySelector('video');
                                    return (v && (!v.paused || window.__ytBufferingStarted)) ? "PLAYING" : "NOT_PLAYING";
                                })();
                                """.trimIndent()
                            ) { result ->
                                if (result != null && result.contains("PLAYING")) {
                                    onPlaybackStarted()
                                }
                            }
                        }, 900L)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            onPlaybackError()
                        }
                    }
                }
                val headers = mapOf("Referer" to "https://www.youtube.com")
                loadUrl(targetUrl, headers)
            }
        },
        onRelease = { webView ->
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    )
}
