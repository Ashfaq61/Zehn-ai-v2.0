package com.zehn.assistant.agent

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.zehn.assistant.data.db.MemoryDao
import com.zehn.assistant.data.db.MemoryEntity
import com.zehn.assistant.data.model.MemoryTier
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CapturedNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val body: String,
    val priority: Int,
    val timestamp: Long,
    val replyAction: Notification.Action? = null
)

class ZehnNotificationListenerService : NotificationListenerService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface NotifDeps {
        fun memoryDao(): MemoryDao
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deps by lazy {
        EntryPointAccessors.fromApplication(applicationContext, NotifDeps::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
    }

    override fun onListenerDisconnected() {
        _connected.value = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        if (item.packageName == packageName) return
        val extras = item.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val replyAction = item.notification.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }
        val priority = computePriority(item.packageName, "$title $text")
        val captured = CapturedNotification(
            key = item.key,
            packageName = item.packageName,
            title = title,
            body = text,
            priority = priority,
            timestamp = item.postTime,
            replyAction = replyAction
        )
        _recentNotifications.value = (listOf(captured) + _recentNotifications.value).take(25)

        scope.launch {
            deps.memoryDao().insert(
                MemoryEntity(
                    tier = MemoryTier.NOTIFICATION_INTEL.name,
                    title = title.ifBlank { item.packageName },
                    content = text,
                    priority = priority,
                    sourceApp = item.packageName
                )
            )
        }
        onNotificationTrigger?.invoke(captured)
    }

    private fun computePriority(pkg: String, content: String): Int {
        val lower = content.lowercase()
        var score = 4
        if (pkg.contains("whatsapp") || pkg.contains("messaging") || pkg.contains("dialer")) score += 3
        if (lower.contains("otp") || lower.contains("urgent") || lower.contains("call") || lower.contains("bank")) score += 3
        return score.coerceIn(1, 10)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        private val _recentNotifications = MutableStateFlow<List<CapturedNotification>>(emptyList())
        val recentNotifications: StateFlow<List<CapturedNotification>> = _recentNotifications.asStateFlow()

        var onNotificationTrigger: ((CapturedNotification) -> Unit)? = null

        fun replyToLatest(replyText: String): Boolean {
            val target = _recentNotifications.value.firstOrNull { it.replyAction != null } ?: return false
            val action = target.replyAction ?: return false
            val remoteInputs = action.remoteInputs ?: return false
            val intent = Intent()
            val bundle = Bundle()
            remoteInputs.forEach { ri -> bundle.putCharSequence(ri.resultKey, replyText) }
            RemoteInput.addResultsToIntent(remoteInputs, intent, bundle)
            return runCatching {
                action.actionIntent.send(null, 0, intent)
                true
            }.getOrDefault(false)
        }
    }
}
