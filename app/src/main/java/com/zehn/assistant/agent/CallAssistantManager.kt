package com.zehn.assistant.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.zehn.assistant.data.PreferencesRepository
import com.zehn.assistant.data.db.ActionLogDao
import com.zehn.assistant.data.db.ActionLogEntity
import com.zehn.assistant.voice.SentenceStreamingTtsEngine
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class CallCapabilityReport(
    val readPhoneStateGranted: Boolean,
    val answerCallsGranted: Boolean,
    val readContactsGranted: Boolean,
    val canScreenCallerNumber: Boolean,
    val canAutoAnswerAndReject: Boolean,
    val canInjectAudioIntoCellularUplink: Boolean = false,
    val restrictionSummary: String = "Android Telecom blocks third-party non-root apps from injecting TTS audio directly into the cellular call uplink microphone. Zehn can announce callers locally, auto-answer/reject, reply via SMS, log summaries, and bridge speakerphone or configured VoIP/SIP channels."
)

data class CallInteractionRecord(
    val callerDisplay: String,
    val phoneNumber: String,
    val isVip: Boolean,
    val outcome: String,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * CallScreeningService (API 29+) receiving exact caller phone number when set as system caller ID / screening app.
 */
class ZehnCallScreeningService : CallScreeningService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ScreeningDeps {
        fun callAssistantManager(): CallAssistantManager
    }

    override fun onScreenCall(callDetails: Call.Details) {
        val number = callDetails.handle?.schemeSpecificPart.orEmpty()
        runCatching {
            val deps = EntryPointAccessors.fromApplication(applicationContext, ScreeningDeps::class.java)
            deps.callAssistantManager().onScreenedIncomingNumber(number)
        }
        val response = CallResponse.Builder()
            .setDisallowCall(false)
            .setRejectCall(false)
            .setSilenceCall(false)
            .build()
        respondToCall(callDetails, response)
    }
}

@Singleton
class CallAssistantManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: PreferencesRepository,
    private val tts: SentenceStreamingTtsEngine,
    private val actionLogDao: ActionLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _incomingCaller = MutableStateFlow<String?>(null)
    val incomingCaller: StateFlow<String?> = _incomingCaller.asStateFlow()

    private val _recentCalls = MutableStateFlow<List<CallInteractionRecord>>(emptyList())
    val recentCalls: StateFlow<List<CallInteractionRecord>> = _recentCalls.asStateFlow()

    private var registeredCallback: Any? = null
    private var autoAnswerCountdownJob: Job? = null
    private var lastScreenedNumber: String = ""

    fun capabilityReport(): CallCapabilityReport {
        val readPhone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val answerCalls = ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
        val contacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        return CallCapabilityReport(
            readPhoneStateGranted = readPhone,
            answerCallsGranted = answerCalls,
            readContactsGranted = contacts,
            canScreenCallerNumber = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            canAutoAnswerAndReject = answerCalls && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        )
    }

    @Suppress("DEPRECATION")
    fun startMonitoringIfPermitted() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (registeredCallback != null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    handleCallState(state, lastScreenedNumber.ifBlank { "Incoming Caller" })
                }
            }
            runCatching {
                tm.registerTelephonyCallback(context.mainExecutor, cb)
                registeredCallback = cb
            }
        } else {
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    handleCallState(state, phoneNumber?.takeIf { it.isNotBlank() } ?: lastScreenedNumber.ifBlank { "Incoming Caller" })
                }
            }
            runCatching {
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                registeredCallback = listener
            }
        }
    }

    fun onScreenedIncomingNumber(number: String) {
        if (number.isNotBlank()) {
            lastScreenedNumber = number
        }
    }

    internal fun isVipCaller(callerName: String, phoneNumber: String, vipCsv: String): Boolean {
        val tokens = vipCsv.split(",", "\n").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        if (tokens.isEmpty()) return false
        val cleanName = callerName.lowercase()
        val cleanDigits = phoneNumber.filter { it.isDigit() }
        return tokens.any { token ->
            cleanName.contains(token) || (cleanDigits.isNotEmpty() && token.filter { it.isDigit() }.let { d -> d.isNotEmpty() && cleanDigits.endsWith(d) })
        }
    }

    private fun resolveContactName(number: String): String {
        if (number.isBlank() || number == "Incoming Caller") return "Incoming Caller"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return number
        }
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull() ?: number
    }

    private fun handleCallState(state: Int, number: String) {
        scope.launch {
            val settings = prefs.settings.first()
            if (!settings.callAssistantEnabled) return@launch
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> {
                    val displayName = resolveContactName(number)
                    val isVip = isVipCaller(displayName, number, settings.vipContactsCsv)
                    _incomingCaller.value = if (isVip) "VIP: $displayName" else displayName

                    val announcement = if (isVip) {
                        "VIP call from $displayName. Ringing directly."
                    } else {
                        "Incoming call from $displayName. Say answer or reject."
                    }
                    tts.speakImmediate(announcement, settings.activeVoice)
                    actionLogDao.insert(ActionLogEntity("CALL", "Call Announced", "$displayName ($number) vip=$isVip", "INFO"))

                    _recentCalls.value = (listOf(
                        CallInteractionRecord(displayName, number, isVip, "Incoming call announced")
                    ) + _recentCalls.value).take(20)

                    if (settings.callAutoAnswerEnabled && !isVip) {
                        autoAnswerCountdownJob?.cancel()
                        autoAnswerCountdownJob = scope.launch {
                            delay(settings.callAutoAnswerDelaySec * 1000L)
                            val outcome = answerIncomingCall()
                            actionLogDao.insert(ActionLogEntity("CALL", "Auto-Answer Triggered", outcome, "INFO"))
                            if (outcome.startsWith("Answered")) {
                                tts.speakImmediate(settings.callReceptionistPrompt, settings.activeVoice)
                            }
                        }
                    }
                }

                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    autoAnswerCountdownJob?.cancel()
                }

                TelephonyManager.CALL_STATE_IDLE -> {
                    autoAnswerCountdownJob?.cancel()
                    _incomingCaller.value = null
                    lastScreenedNumber = ""
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun answerIncomingCall(): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return "Grant Answer Phone Calls permission in Permissions to answer by voice."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            telecom?.acceptRingingCall()
            return "Answered incoming call."
        }
        return "Voice call answer requires Android 8.0+."
    }

    @SuppressLint("MissingPermission")
    fun rejectIncomingCall(sendAutoReply: Boolean = false, number: String? = null): String {
        autoAnswerCountdownJob?.cancel()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return "Grant Answer Phone Calls permission to reject calls."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            @Suppress("DEPRECATION")
            telecom?.endCall()
            if (sendAutoReply && !number.isNullOrBlank() &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
            ) {
                scope.launch {
                    val reply = prefs.settings.first().autoReplySmsText
                    val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    sms?.sendTextMessage(number, null, reply, null, null)
                    actionLogDao.insert(ActionLogEntity("CALL", "Auto-Reply SMS Sent", "To $number: $reply", "SUCCESS"))
                }
            }
            return "Call rejected."
        }
        return "Voice call rejection requires Android 9.0+."
    }
}
