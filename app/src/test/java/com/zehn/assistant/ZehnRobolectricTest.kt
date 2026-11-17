package com.zehn.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zehn.assistant.voice.LanguageDetector
import com.zehn.assistant.data.model.VoiceLanguage
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZehnRobolectricTest {

    @Test
    fun `app name matches Zehn AI`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Zehn AI", context.getString(R.string.app_name))
    }

    @Test
    fun `language detector identifies English, Urdu, Hindi, and Hinglish`() {
        assertEquals(VoiceLanguage.ENGLISH, LanguageDetector.detect("Open Spotify and play music", VoiceLanguage.AUTO))
        assertEquals(VoiceLanguage.URDU, LanguageDetector.detect("میرا نام ذہن ہے", VoiceLanguage.AUTO))
        assertEquals(VoiceLanguage.HINDI, LanguageDetector.detect("मेरा नाम ज़ेहन है", VoiceLanguage.AUTO))
        assertEquals(VoiceLanguage.HINGLISH, LanguageDetector.detect("Bhai mera alarm laga do kal subah", VoiceLanguage.AUTO))
    }
}
