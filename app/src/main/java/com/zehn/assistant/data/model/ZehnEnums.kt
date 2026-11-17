package com.zehn.assistant.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class AiProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val supportsVision: Boolean,
    val supportsTools: Boolean,
    val requiresKey: Boolean
) {
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/", true, true, true),
    OPENAI_COMPATIBLE("OpenAI Compatible", "https://api.openai.com/v1/", true, true, true),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com/v1/", true, true, true),
    GROQ("Groq Cloud", "https://api.groq.com/openai/v1/", true, true, true),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/", true, true, true),
    TOGETHER("Together AI", "https://api.together.xyz/v1/", false, true, true),
    COHERE("Cohere", "https://api.cohere.com/v2/", false, true, true),
    HUGGINGFACE("Hugging Face Inference", "https://router.huggingface.co/v1/", true, true, true),
    OLLAMA("Ollama Local Server", "http://127.0.0.1:11434/v1/", true, true, false),
    WEBHOOK("Webhook Backend", "http://127.0.0.1:8080/zehn", false, false, false),
    ON_DEVICE_LITERT("LiteRT-LM (On-Device)", "local://litert-lm", true, true, false)
}

@Serializable
enum class AiExecutionMode(val label: String, val description: String) {
    CLOUD("Cloud", "Full cloud reasoning, multimodal vision & tool calling"),
    HYBRID("Hybrid", "Simple tasks & local controls on-device; complex tasks in cloud"),
    OFFLINE("Offline", "100% private on-device LiteRT-LM, local STT & TTS")
}

@Serializable
enum class VoiceLanguage(val code: String, val label: String, val bcp47: String) {
    AUTO("auto", "Auto (EN/UR/HI/Hinglish)", "en-US"),
    ENGLISH("en", "English", "en-US"),
    URDU("ur", "Urdu (اردو)", "ur-PK"),
    HINDI("hi", "Hindi (हिन्दी)", "hi-IN"),
    HINGLISH("hinglish", "Hinglish / Roman Urdu", "en-IN")
}

@Serializable
enum class VoiceProfile(
    val id: String,
    val displayName: String,
    val gender: String,
    val pitch: Float,
    val rate: Float,
    val localeTag: String,
    val tagline: String
) {
    AOEDE("aoede", "Aoede", "Female", 1.10f, 1.02f, "en-US", "Crisp soprano"),
    KORE("kore", "Kore", "Female", 1.04f, 0.98f, "en-US", "Warm alto"),
    ZEPHYR("zephyr", "Zephyr", "Female", 1.05f, 1.00f, "en-GB", "Bright tenor"),
    LEDA("leda", "Leda", "Female", 1.02f, 0.98f, "en-US", "Smooth mezzo"),
    CALLIRRHOE("callirrhoe", "Callirrhoe", "Female", 1.08f, 0.96f, "en-US", "Rich resonant"),
    AUTONOE("autonoe", "Autonoe", "Female", 1.07f, 1.01f, "en-US", "Clear expressive"),
    CHARON("charon", "Charon", "Male", 0.90f, 1.00f, "en-US", "Deep calm baritone"),
    PUCK("puck", "Puck", "Male", 0.95f, 1.02f, "en-US", "Playful tenor"),
    FENRIR("fenrir", "Fenrir", "Male", 0.88f, 0.96f, "en-US", "Powerful bass"),
    ORUS("orus", "Orus", "Male", 0.85f, 0.95f, "en-GB", "Commanding studio bass"),
    ARIA("aria", "Aria", "Female", 1.10f, 1.02f, "en-US", "Crisp assistant soprano"),
    ZARA("zara", "Zara", "Female", 1.04f, 0.98f, "en-GB", "Warm conversational alto"),
    NOOR("noor", "Noor", "Female", 1.08f, 0.96f, "ur-PK", "Bilingual Urdu & English"),
    MEERA("meera", "Meera", "Female", 1.06f, 1.00f, "hi-IN", "Clear Hindi & Hinglish"),
    ORION("orion", "Orion", "Male", 0.90f, 1.00f, "en-US", "Deep calm baritone"),
    ZAYD("zayd", "Zayd", "Male", 0.94f, 0.98f, "ur-PK", "Natural Urdu & Roman Urdu"),
    KABIR("kabir", "Kabir", "Male", 0.92f, 1.02f, "hi-IN", "Energetic Hindi & English"),
    ATLAS("atlas", "Atlas", "Male", 0.85f, 0.95f, "en-GB", "Commanding studio bass")
}

@Serializable
enum class VadEngineType(val label: String) {
    SILERO_NEURAL("Silero Acoustic VAD"),
    ENERGY_ADAPTIVE("Adaptive Energy VAD")
}

@Serializable
enum class SttEngineType(val label: String) {
    ANDROID_SPEECH("Android SpeechRecognizer"),
    WHISPER_ENDPOINT("Whisper / Groq Endpoint"),
    VOSK_OFFLINE("Vosk / Parakeet Offline")
}

@Serializable
enum class TtsEngineType(val label: String) {
    ANDROID_STREAMING("Android Sentence-Stream TTS"),
    CLONED_PROFILE("Reference-Matched Voice Profile")
}

@Serializable
enum class OrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING
}

@Serializable
enum class MemoryTier(val label: String, val description: String) {
    WORKING("Working", "Active task & immediate conversation scratchpad"),
    SESSION("Session", "Current multi-turn context & recent tool outputs"),
    LONG_TERM("Long-Term", "Persistent user facts, contacts & preferences"),
    NOTIFICATION_INTEL("Notification Intel", "Prioritized summaries of incoming alerts")
}

@Serializable
enum class ExecutionPath(val label: String) {
    FAST_PATH("Fast Path (Direct Intent)"),
    STANDARD_PATH("Standard Path (GUI Agent Loop)")
}

@Serializable
enum class TriggerType(val label: String) {
    TIME("Scheduled Time"),
    NOTIFICATION("Incoming Notification"),
    APP_OPEN("App Opened"),
    CHARGING("Charger Connected"),
    BATTERY_LOW("Battery Threshold"),
    CONNECTIVITY("Wi-Fi / Network Change")
}

@Serializable
enum class LiteRtBackend(val label: String) {
    AUTO("Auto-Tuned"),
    GPU("GPU (OpenCL)"),
    CPU("CPU (XNNPACK)"),
    NPU("NPU Delegate (Fallback CPU)")
}
