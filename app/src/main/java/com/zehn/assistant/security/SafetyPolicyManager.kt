package com.zehn.assistant.security

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SafetyPolicyState(
    val gamingScreenCheckEnabled: Boolean = false,
    val imageGenerationEnabled: Boolean = true,
    val automatedPaymentConfirmEnabled: Boolean = false, // Default OFF: always ask
    val confirmBeforeMessagesAndCalls: Boolean = true,
    val confirmBeforeDeletes: Boolean = true,
    val maxAgentSteps: Int = 12,
    val blockedAppPackages: Set<String> = setOf(
        "com.android.vending",
        "com.google.android.apps.walletnfcrel",
        "com.paypal.android.p2pmobile"
    ),
    val emergencyStopped: Boolean = false
)

enum class PendingConfirmationType {
    PAYMENT,
    CALL,
    MESSAGE,
    DELETE,
    SOCIAL_POST
}

data class PendingConfirmationRequest(
    val id: String,
    val type: PendingConfirmationType,
    val title: String,
    val detail: String,
    val targetPackage: String? = null,
    val warningBanner: String? = null,
    val onConfirm: () -> Unit,
    val onDeny: () -> Unit
)

@Singleton
class SafetyPolicyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    private val prefs = context.getSharedPreferences("zehn_safety_policy", Context.MODE_PRIVATE)

    private val _policy = MutableStateFlow(loadPolicy())
    val policy: StateFlow<SafetyPolicyState> = _policy.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmationRequest?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmationRequest?> = _pendingConfirmation.asStateFlow()

    private fun loadPolicy(): SafetyPolicyState {
        val raw = prefs.getString("policy_json", null) ?: return SafetyPolicyState()
        return try {
            json.decodeFromString<SafetyPolicyState>(raw).copy(emergencyStopped = false)
        } catch (_: Exception) {
            SafetyPolicyState()
        }
    }

    private fun persist(state: SafetyPolicyState) {
        prefs.edit().putString("policy_json", json.encodeToString(state.copy(emergencyStopped = false))).apply()
    }

    fun updatePolicy(transform: (SafetyPolicyState) -> SafetyPolicyState) {
        _policy.update { current ->
            val updated = transform(current).copy(
                maxAgentSteps = transform(current).maxAgentSteps.coerceIn(1, 30)
            )
            persist(updated)
            updated
        }
    }

    fun triggerEmergencyStop() {
        _policy.update { it.copy(emergencyStopped = true) }
        _pendingConfirmation.value?.onDeny?.invoke()
        _pendingConfirmation.value = null
    }

    fun clearEmergencyStop() {
        _policy.update { it.copy(emergencyStopped = false) }
    }

    fun requestConfirmation(request: PendingConfirmationRequest) {
        _pendingConfirmation.value = request
    }

    fun resolveConfirmation(approved: Boolean) {
        val current = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        if (approved) current.onConfirm() else current.onDeny()
    }

    fun isAppBlocked(packageName: String): Boolean {
        return packageName in _policy.value.blockedAppPackages
    }

    fun isKnownGamePackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return KNOWN_GAME_HINTS.any { lower.contains(it) }
    }

    fun canReadCurrentScreen(foregroundPackage: String?): Boolean {
        val state = _policy.value
        if (state.emergencyStopped) return false
        if (foregroundPackage != null && isAppBlocked(foregroundPackage)) return false
        if (!state.gamingScreenCheckEnabled && foregroundPackage != null && isKnownGamePackage(foregroundPackage)) {
            return false
        }
        return true
    }

    fun isSensitivePaymentNode(text: String?, contentDesc: String?, viewId: String?): Boolean {
        val combined = listOfNotNull(text, contentDesc, viewId).joinToString(" ").lowercase()
        return PAYMENT_KEYWORDS.any { combined.contains(it) }
    }

    fun isSamsungDevice(): Boolean = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    companion object {
        private val PAYMENT_KEYWORDS = listOf(
            "pay now", "place order", "send money", "confirm purchase", "checkout",
            "complete payment", "transfer funds", "authorize payment", "buy now", "easypaisa", "jazzcash"
        )
        private val KNOWN_GAME_HINTS = listOf(
            "pubg", "tencent.ig", "supercell", "roblox", "garena", "minecraft", "genshin",
            "activision", "callofduty", "ea.gp", "gameloft", "unity", "mobile.legends"
        )
    }
}
