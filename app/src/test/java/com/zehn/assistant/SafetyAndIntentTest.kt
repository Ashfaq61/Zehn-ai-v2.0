package com.zehn.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zehn.assistant.agent.IntentClassifier
import com.zehn.assistant.data.model.ExecutionPath
import com.zehn.assistant.security.SafetyPolicyManager
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafetyAndIntentTest {

    @Test
    fun `automated payment confirmation defaults to false and detects payment buttons`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = SafetyPolicyManager(context, Json { ignoreUnknownKeys = true })
        assertFalse(policy.policy.value.automatedPaymentConfirmEnabled)
        assertTrue(policy.isSensitivePaymentNode("Place Order", null, null))
        assertTrue(policy.isSensitivePaymentNode("Pay Now", null, "btn_checkout"))
        assertFalse(policy.isSensitivePaymentNode("Open Settings", null, "btn_settings"))
    }

    @Test
    fun `gaming guard blocks screen reading when off`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val policy = SafetyPolicyManager(context, Json { ignoreUnknownKeys = true })
        assertFalse(policy.canReadCurrentScreen("com.tencent.ig"))
        policy.updatePolicy { it.copy(gamingScreenCheckEnabled = true) }
        assertTrue(policy.canReadCurrentScreen("com.tencent.ig"))
    }

    @Test
    fun `intent classifier chooses fast path vs standard gui loop`() {
        val classifier = IntentClassifier()
        val fast = classifier.classify("Open Instagram")
        assertEquals(ExecutionPath.FAST_PATH, fast.path)
        assertEquals("OPEN_APP", fast.action)

        val gui = classifier.classify("Tap Place Order on screen")
        assertEquals(ExecutionPath.STANDARD_PATH, gui.path)
        assertEquals("GUI_AUTOMATION", gui.action)
    }
}
