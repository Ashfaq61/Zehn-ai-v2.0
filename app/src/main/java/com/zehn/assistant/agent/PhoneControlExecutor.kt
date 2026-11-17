package com.zehn.assistant.agent

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.security.PendingConfirmationRequest
import com.zehn.assistant.security.PendingConfirmationType
import com.zehn.assistant.security.SafetyPolicyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhoneControlExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val safetyPolicy: SafetyPolicyManager,
    private val actionLogDao: ActionLogDao
) {
    private var torchEnabled = false

    private val knownAppAliases: List<Pair<String, List<String>>> = listOf(
        "whatsapp business" to listOf("com.whatsapp.w4b"),
        "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
        "facebook lite" to listOf("com.facebook.lite"),
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "youtube" to listOf("com.google.android.youtube"),
        "instagram" to listOf("com.instagram.android"),
        "spotify" to listOf("com.spotify.music"),
        "google maps" to listOf("com.google.android.apps.maps"),
        "maps" to listOf("com.google.android.apps.maps"),
        "google search" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome"),
        "google" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome"),
        "chrome" to listOf("com.android.chrome"),
        "gmail" to listOf("com.google.android.gm"),
        "gallery" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d", "com.miui.gallery"),
        "photos" to listOf("com.google.android.apps.photos", "com.sec.android.gallery3d"),
        "phone" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
        "dialer" to listOf("com.google.android.dialer", "com.samsung.android.dialer", "com.android.dialer"),
        "messages" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
        "sms" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"),
        "settings" to listOf("com.android.settings")
    )

    suspend fun openApp(appNameOrPackage: String): String {
        val clean = appNameOrPackage.trim().lowercase()
        if (clean == "camera") {
            return openSystemCameraApp(frontCamera = false)
        }
        val candidates = knownAppAliases.firstOrNull { (alias, _) -> clean.contains(alias) }?.second
            ?: listOf(appNameOrPackage.trim())

        val candidatePkg = candidates.firstOrNull { isPackageInstalled(it) } ?: candidates.first()

        if (safetyPolicy.isAppBlocked(candidatePkg)) {
            actionLogDao.insert(ActionLogEntity("SAFETY", "App Launch Blocked", "$candidatePkg is on block-list", "BLOCKED"))
            return "Blocked: $appNameOrPackage is in your app block-list."
        }

        if (!isPackageInstalled(candidatePkg)) {
            actionLogDao.insert(ActionLogEntity("AGENT_FAST", "Open App Failed", "$candidatePkg not installed", "FAILED"))
            return "$appNameOrPackage ($candidatePkg) is not installed on this device."
        }

        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(candidatePkg) ?: run {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(candidatePkg)
            }
            val resolve = pm.queryIntentActivities(mainIntent, 0).firstOrNull()
            resolve?.let {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(it.activityInfo.packageName, it.activityInfo.name)
                }
            }
        }

        if (launchIntent == null) {
            return "$appNameOrPackage is installed, but has no launchable main screen."
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            actionLogDao.insert(ActionLogEntity("AGENT_FAST", "Opened $appNameOrPackage", candidatePkg, "SUCCESS"))
            "Opened $appNameOrPackage."
        }.getOrElse { err ->
            "Could not open $appNameOrPackage: ${err.message}"
        }
    }

    suspend fun openSystemCameraApp(frontCamera: Boolean = false): String {
        val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (frontCamera) {
                putExtra("android.intent.extras.CAMERA_FACING", 1)
                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            }
        }
        return runCatching {
            context.startActivity(intent)
            actionLogDao.insert(
                ActionLogEntity("AGENT_FAST", "Camera Launched", if (frontCamera) "Front" else "Back", "SUCCESS")
            )
            if (frontCamera) "Opened Camera app (requested front lens)." else "Opened Camera app."
        }.getOrElse { "Unable to launch Camera app: ${it.message}" }
    }

    fun closeCurrentApp(): String {
        val svc = ZehnAccessibilityService.instance.value
        return if (svc != null && svc.performHome()) {
            "Returned to Home screen. (Android security prohibits force-closing other apps cleanly without root.)"
        } else {
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                context.startActivity(homeIntent)
                "Minimized current app to Home screen. (Android does not permit non-root force-stop of other apps.)"
            }.getOrElse {
                "Enable Accessibility Service or grant permission to return Home."
            }
        }
    }

    fun searchGoogle(query: String): String {
        val clean = query.trim().ifBlank { "latest news" }
        val url = "https://www.google.com/search?q=${URLEncoder.encode(clean, "UTF-8")}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isPackageInstalled("com.android.chrome")) {
                setPackage("com.android.chrome")
            }
        }
        return runCatching {
            context.startActivity(intent)
            "Opened Google Search for \"$clean\"."
        }.getOrElse { "Could not open Google Search: ${it.message}" }
    }

    fun openMaps(destinationOrQuery: String): String {
        val clean = destinationOrQuery.trim()
        val uri = if (clean.isBlank()) {
            Uri.parse("geo:0,0")
        } else {
            Uri.parse("geo:0,0?q=${URLEncoder.encode(clean, "UTF-8")}")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (isPackageInstalled("com.google.android.apps.maps")) {
                setPackage("com.google.android.apps.maps")
            }
        }
        return runCatching {
            context.startActivity(intent)
            if (clean.isBlank()) "Opened Google Maps." else "Opened Google Maps for \"$clean\"."
        }.getOrElse { "Could not open Maps: ${it.message}" }
    }

    suspend fun initiateCall(phoneNumber: String): String {
        val clean = phoneNumber.trim()
        if (clean.isBlank()) return "Please specify a phone number to call."
        if (safetyPolicy.policy.value.confirmBeforeMessagesAndCalls) {
            safetyPolicy.requestConfirmation(
                PendingConfirmationRequest(
                    id = UUID.randomUUID().toString(),
                    type = PendingConfirmationType.CALL,
                    title = "Confirm Voice Call",
                    detail = "Call $clean using your phone dialer?",
                    onConfirm = { performCallNow(clean) },
                    onDeny = {}
                )
            )
            return "Waiting for your confirmation to call $clean."
        }
        return performCallNow(clean)
    }

    private fun performCallNow(clean: String): String {
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val action = if (hasCallPerm) Intent.ACTION_CALL else Intent.ACTION_DIAL
        val intent = Intent(action, Uri.parse("tel:$clean")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            "Dialing $clean."
        }.getOrElse { "Unable to launch dialer: ${it.message}" }
    }

    suspend fun sendSms(number: String, message: String): String {
        if (safetyPolicy.policy.value.confirmBeforeMessagesAndCalls) {
            safetyPolicy.requestConfirmation(
                PendingConfirmationRequest(
                    id = UUID.randomUUID().toString(),
                    type = PendingConfirmationType.MESSAGE,
                    title = "Confirm SMS",
                    detail = "Send SMS to $number: \"$message\"",
                    onConfirm = { executeSms(number, message) },
                    onDeny = {}
                )
            )
            return "Confirmation requested before sending SMS to $number."
        }
        return executeSms(number, message)
    }

    private fun executeSms(number: String, message: String): String {
        val hasSmsPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        return if (hasSmsPerm) {
            runCatching {
                val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                sms?.sendTextMessage(number, null, message, null, null)
                "SMS sent to $number."
            }.getOrElse { "Failed to send SMS: ${it.message}" }
        } else {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Opened SMS composer for $number."
        }
    }

    suspend fun sendWhatsApp(phoneOrName: String, message: String, imageUri: Uri? = null): String {
        val installed = isPackageInstalled("com.whatsapp") || isPackageInstalled("com.whatsapp.w4b")
        if (!installed) {
            return "WhatsApp is not installed on this phone."
        }
        if (safetyPolicy.policy.value.confirmBeforeMessagesAndCalls) {
            safetyPolicy.requestConfirmation(
                PendingConfirmationRequest(
                    id = UUID.randomUUID().toString(),
                    type = PendingConfirmationType.MESSAGE,
                    title = "Confirm WhatsApp Message",
                    detail = "Send to $phoneOrName: \"$message\"",
                    targetPackage = "com.whatsapp",
                    onConfirm = { launchWhatsApp(phoneOrName, message, imageUri) },
                    onDeny = {}
                )
            )
            return "Confirmation requested before sending WhatsApp message."
        }
        return launchWhatsApp(phoneOrName, message, imageUri)
    }

    private fun launchWhatsApp(phoneOrName: String, message: String, imageUri: Uri?): String {
        val pkg = if (isPackageInstalled("com.whatsapp")) "com.whatsapp" else "com.whatsapp.w4b"
        val digits = phoneOrName.filter { it.isDigit() }
        val intent = if (imageUri != null) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                putExtra(Intent.EXTRA_TEXT, message)
                setPackage(pkg)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else if (digits.length >= 7) {
            val url = "https://wa.me/$digits?text=${URLEncoder.encode(message, "UTF-8")}"
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        return runCatching {
            context.startActivity(intent)
            "Opened WhatsApp with your message."
        }.getOrElse { "Could not open WhatsApp: ${it.message}" }
    }

    fun postToSocialMedia(platform: String, caption: String, imageUri: Uri? = null): String {
        val pkg = when (platform.lowercase()) {
            "instagram" -> "com.instagram.android"
            "facebook" -> "com.facebook.katana"
            "tiktok" -> "com.zhiliaoapp.musically"
            "whatsapp", "whatsapp status" -> "com.whatsapp"
            else -> ""
        }
        if (pkg.isNotEmpty() && !isPackageInstalled(pkg)) {
            return "$platform is not installed on this device."
        }
        safetyPolicy.requestConfirmation(
            PendingConfirmationRequest(
                id = UUID.randomUUID().toString(),
                type = PendingConfirmationType.SOCIAL_POST,
                title = "Confirm $platform Post",
                detail = "Prepare $platform share with caption: \"$caption\"",
                targetPackage = pkg,
                warningBanner = "Policy Warning: Automated posting or bot interaction on Instagram, Facebook, TikTok, or WhatsApp may violate platform Terms of Service. Zehn will open the official share sheet for your manual review.",
                onConfirm = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = if (imageUri != null) "image/*" else "text/plain"
                        putExtra(Intent.EXTRA_TEXT, caption)
                        if (imageUri != null) putExtra(Intent.EXTRA_STREAM, imageUri)
                        if (pkg.isNotEmpty()) setPackage(pkg)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(shareIntent) }
                },
                onDeny = {}
            )
        )
        return "Platform rules warning displayed; awaiting your confirmation for $platform."
    }

    fun setVolumePercent(percent: Int): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = ((percent.coerceIn(0, 100) / 100f) * max).toInt()
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return "Media volume set to $percent%."
    }

    fun setBrightnessPercent(percent: Int): String {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return "Please grant Modify System Settings permission to adjust brightness."
        }
        val value = ((percent.coerceIn(5, 100) / 100f) * 255).toInt()
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        return "Screen brightness set to $percent%."
    }

    fun controlMedia(action: String): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keyCode = when (action.lowercase()) {
            "play", "pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return "Sent media $action command."
    }

    fun playYouTube(query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isPackageInstalled("com.google.android.youtube")) {
            intent.setPackage("com.google.android.youtube")
        }
        context.startActivity(intent)
        return "Searching YouTube for \"$query\"."
    }

    fun playSpotify(query: String): String {
        if (!isPackageInstalled("com.spotify.music")) {
            return "Spotify is not installed on this device."
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(query)}"))
            .setPackage("com.spotify.music")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Searching Spotify for \"$query\"."
    }

    fun toggleFlashlight(desiredState: Boolean? = null): String {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "No camera flash hardware found on this device."
        val next = desiredState ?: !torchEnabled
        return runCatching {
            cm.setTorchMode(camId, next)
            torchEnabled = next
            if (next) "Flashlight turned ON." else "Flashlight turned OFF."
        }.getOrElse { "Could not toggle flashlight: ${it.message}" }
    }

    fun setSpeakerphone(enabled: Boolean): String {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val speaker = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null) am.setCommunicationDevice(speaker)
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = enabled
        }
        return if (enabled) "Speakerphone enabled." else "Speakerphone disabled."
    }

    fun openConnectivityPanel(target: String): String {
        val action = when (target.lowercase()) {
            "wifi", "wi-fi" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
        }
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened $target controls."
    }

    fun setAlarm(hour: Int, minute: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            "Alarm set for %02d:%02d (%s).".format(hour, minute, label)
        }.getOrElse { "Could not set alarm: ${it.message}" }
    }

    fun setTimer(seconds: Int, label: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            "Timer started for $seconds seconds."
        }.getOrElse { "Could not start timer: ${it.message}" }
    }

    fun addCalendarEvent(title: String, startMillis: Long, durationMinutes: Int = 60): String {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMillis + durationMinutes * 60_000L)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return "Opened Calendar event '$title'."
    }

    fun getSystemStatusSummary(): String {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val freeRamGb = mi.availMem / (1024f * 1024f * 1024f)
        val totalRamGb = mi.totalMem / (1024f * 1024f * 1024f)

        val cores = Runtime.getRuntime().availableProcessors()

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi Connected"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular Connected"
            else -> "Online"
        }

        val localTime = SimpleDateFormat("hh:mm a, z", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())

        return "Battery: $pct% (${if (charging) "Charging" else "Discharging"}) • RAM: ${"%.1f".format(freeRamGb)} / ${"%.1f".format(totalRamGb)} GB free • CPU: $cores cores • Network: $net • Local Time: $localTime"
    }

    fun getClipboardText(): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item: ClipData.Item? = cm.primaryClip?.getItemAt(0)
        return item?.coerceToText(context)?.toString().orEmpty()
    }

    suspend fun fetchWeather(city: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=1"
            val geoBody = okHttpClient.newCall(Request.Builder().url(geoUrl).build()).execute().use { it.body?.string().orEmpty() }
            val place = JSONObject(geoBody).optJSONArray("results")?.optJSONObject(0)
                ?: return@runCatching "Could not find weather coordinates for '$city'."
            val lat = place.getDouble("latitude")
            val lon = place.getDouble("longitude")
            val name = place.optString("name", city)
            val wxUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true"
            val wxBody = okHttpClient.newCall(Request.Builder().url(wxUrl).build()).execute().use { it.body?.string().orEmpty() }
            val cw = JSONObject(wxBody).getJSONObject("current_weather")
            val temp = cw.getDouble("temperature")
            val wind = cw.getDouble("windspeed")
            "Weather in $name: $temp°C, wind $wind km/h."
        }.getOrElse { "Weather lookup failed: ${it.message}" }
    }

    suspend fun webSearch(query: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.duckduckgo.com/?q=${URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1"
            val body = okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string().orEmpty() }
            val obj = JSONObject(body)
            val abstractText = obj.optString("AbstractText", "")
            val heading = obj.optString("Heading", "")
            if (abstractText.isNotBlank()) {
                "$heading: $abstractText"
            } else {
                val related = obj.optJSONArray("RelatedTopics")?.optJSONObject(0)?.optString("Text", "").orEmpty()
                related.ifBlank { "No instant summary returned for '$query'. Opened search results." }
            }
        }.getOrElse { "Web search error: ${it.message}" }
    }

    fun isPackageInstalled(packageName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }
}
