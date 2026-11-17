package com.zehn.assistant.ai

import com.zehn.assistant.data.model.AiProvider

data class ModelOption(
    val id: String,
    val label: String,
    val contextWindow: String,
    val supportsVision: Boolean = false
)

data class LiteRtModelSpec(
    val id: String,
    val name: String,
    val quantization: String,
    val repo: String,
    val fileName: String,
    val expectedSizeBytes: Long,
    val minRamMb: Long,
    val description: String
) {
    val downloadUrl: String
        get() = "https://huggingface.co/$repo/resolve/main/$fileName"
}

object ProviderCatalog {
    const val DEFAULT_GEMINI_LIVE_MODEL = "gemini-3.8-live"
    const val FALLBACK_GEMINI_LIVE_MODEL = "gemini-3.1-flash-live-preview"

    val defaultGeminiLiveModels: List<ModelOption> = listOf(
        ModelOption("gemini-3.8-live", "Gemini 3.8 Live", "Live Audio", true),
        ModelOption("gemini-3.1-flash-live-preview", "Gemini 3.1 Flash Live (preview)", "Live Audio", true),
        ModelOption("gemini-2.5-flash-native-audio-preview-12-2025", "Gemini 2.5 Flash Native Audio (Dec 2025)", "Live Audio", true)
    )

    val modelsByProvider: Map<AiProvider, List<ModelOption>> = mapOf(
        AiProvider.GEMINI to listOf(
            ModelOption("gemini-2.5-flash", "Gemini 2.5 Flash", "1M", true),
            ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro", "1M", true),
            ModelOption("gemini-2.0-flash", "Gemini 2.0 Flash", "1M", true)
        ),
        AiProvider.OPENAI_COMPATIBLE to listOf(
            ModelOption("gpt-4o", "GPT-4o", "128K", true),
            ModelOption("gpt-4o-mini", "GPT-4o Mini", "128K", true),
            ModelOption("o3-mini", "o3-mini", "200K", false)
        ),
        AiProvider.ANTHROPIC to listOf(
            ModelOption("claude-sonnet-4-20250514", "Claude Sonnet 4", "200K", true),
            ModelOption("claude-3-5-haiku-latest", "Claude 3.5 Haiku", "200K", true)
        ),
        AiProvider.GROQ to listOf(
            ModelOption("llama-3.3-70b-versatile", "Llama 3.3 70B", "128K", false),
            ModelOption("llama-3.2-11b-vision-preview", "Llama 3.2 11B Vision", "128K", true),
            ModelOption("qwen-qwq-32b", "Qwen QwQ 32B", "128K", false)
        ),
        AiProvider.OPENROUTER to listOf(
            ModelOption("google/gemini-2.5-flash", "Gemini 2.5 Flash (OR)", "1M", true),
            ModelOption("anthropic/claude-sonnet-4", "Claude Sonnet 4 (OR)", "200K", true),
            ModelOption("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B (OR)", "128K", false)
        ),
        AiProvider.TOGETHER to listOf(
            ModelOption("meta-llama/Llama-3.3-70B-Instruct-Turbo", "Llama 3.3 70B Turbo", "128K", false),
            ModelOption("Qwen/Qwen2.5-72B-Instruct-Turbo", "Qwen 2.5 72B Turbo", "128K", false)
        ),
        AiProvider.COHERE to listOf(
            ModelOption("command-a-03-2025", "Command A", "256K", false),
            ModelOption("command-r-plus-08-2024", "Command R+", "128K", false)
        ),
        AiProvider.HUGGINGFACE to listOf(
            ModelOption("Qwen/Qwen2.5-72B-Instruct", "Qwen 2.5 72B Instruct (HF)", "128K", false),
            ModelOption("meta-llama/Llama-3.3-70B-Instruct", "Llama 3.3 70B Instruct (HF)", "128K", false),
            ModelOption("google/gemma-3-27b-it", "Gemma 3 27B IT (HF)", "128K", true)
        ),
        AiProvider.OLLAMA to listOf(
            ModelOption("gemma3:4b", "Gemma 3 4B (Local)", "128K", true),
            ModelOption("llama3.2:3b", "Llama 3.2 3B (Local)", "128K", false),
            ModelOption("qwen2.5:7b", "Qwen 2.5 7B (Local)", "128K", false)
        ),
        AiProvider.WEBHOOK to listOf(
            ModelOption("webhook-default", "Custom Webhook Endpoint", "N/A", false)
        ),
        AiProvider.ON_DEVICE_LITERT to listOf(
            ModelOption("gemma-4-e2b-q4", "Gemma 4 E2B (Q4 LiteRT-LM)", "32K", true),
            ModelOption("gemma-4-e4b-q4", "Gemma 4 E4B (Q4 LiteRT-LM)", "32K", true),
            ModelOption("qwen3-0.6b-q4", "Qwen3 0.6B (Ultra-Light)", "32K", false)
        )
    )

    val liteRtModels: List<LiteRtModelSpec> = listOf(
        LiteRtModelSpec(
            id = "gemma-4-e2b-q4",
            name = "Gemma 4 E2B",
            quantization = "Q4_K_M (.litertlm)",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            expectedSizeBytes = 2_600_000_000L,
            minRamMb = 3500L,
            description = "Default multimodal Gemma 4 E2B quantized for phones (4GB+ RAM recommended)."
        ),
        LiteRtModelSpec(
            id = "gemma-4-e4b-q4",
            name = "Gemma 4 E4B",
            quantization = "Q4_K_M (.litertlm)",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            fileName = "gemma-4-E4B-it.litertlm",
            expectedSizeBytes = 4_200_000_000L,
            minRamMb = 7200L,
            description = "Higher-capacity Gemma 4 E4B for flagship 8GB+ RAM devices."
        ),
        LiteRtModelSpec(
            id = "qwen3-0.6b-q4",
            name = "Qwen3 0.6B",
            quantization = "Q4 (.litertlm)",
            repo = "litert-community/Qwen3-0.6B",
            fileName = "Qwen3-0.6B.litertlm",
            expectedSizeBytes = 600_000_000L,
            minRamMb = 2000L,
            description = "Compact 600MB model for lower-RAM phones and fast intent classification."
        )
    )

    fun modelsFor(provider: AiProvider): List<ModelOption> =
        modelsByProvider[provider] ?: emptyList()
}
