package com.zehn.assistant.voice

import com.zehn.assistant.data.model.VoiceLanguage

object LanguageDetector {
    private val hinglishWords = setOf(
        "kya", "hai", "hain", "haan", "nahi", "nhi", "karo", "kardo", "batao", "btao",
        "mera", "meri", "mere", "tum", "aap", "kaise", "kaisa", "acha", "accha", "theek",
        "thik", "bhai", "yaar", "kal", "aaj", "abhi", "chalo", "sunao", "bhejo", "kholo",
        "band", "lagao", "bajao", "zehn", "shukriya", "meherbani", "naam", "waqt"
    )

    fun detect(text: String, preferred: VoiceLanguage): VoiceLanguage {
        if (preferred != VoiceLanguage.AUTO) return preferred
        if (text.isBlank()) return VoiceLanguage.ENGLISH

        val hasArabicUrdu = text.any { it in '\u0600'..'\u06FF' }
        if (hasArabicUrdu) return VoiceLanguage.URDU

        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        if (hasDevanagari) return VoiceLanguage.HINDI

        val tokens = text.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val hits = tokens.count { it in hinglishWords }
        if (hits >= 1 && (hits.toFloat() / tokens.size.coerceAtLeast(1)) >= 0.15f) {
            return VoiceLanguage.HINGLISH
        }
        return VoiceLanguage.ENGLISH
    }
}
