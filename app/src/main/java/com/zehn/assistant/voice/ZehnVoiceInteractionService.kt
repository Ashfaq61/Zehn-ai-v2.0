package com.zehn.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.zehn.assistant.MainActivity

/**
 * Registers Zehn AI as a system default digital assistant (Home button long-press / power assist).
 */
class ZehnVoiceInteractionService : VoiceInteractionService()

class ZehnVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return ZehnVoiceInteractionSession(this)
    }
}

class ZehnVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        launchZehnOrb()
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        launchZehnOrb()
        finish()
    }

    private fun launchZehnOrb() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = WakeWordService.ACTION_TRIGGER_ORB
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startVoiceActivity(intent)
    }
}
