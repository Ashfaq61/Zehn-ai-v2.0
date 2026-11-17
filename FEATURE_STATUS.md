# MAHI / Zehn AI V4.2 — Complete Feature Audit (`FEATURE_STATUS.md`)

**Package:** `com.zehn.assistant` | **Version:** `4.1.0` (`versionCode = 5`, CI releases set `4.0.<run>` via Gradle properties) | **Target SDK:** 35 | **Min SDK:** 24  
**Verified Automated Checks:**
- `compile_applet` (Debug Kotlin + KSP + Hilt + Manifest + Resources): **PASSED**
- `gradle :app:testDebugUnitTest :app:assembleDebug --console=plain`: **41 / 41 tests PASSED** (0 failures, 0 skipped) across `ExampleUnitTest` (1), `SafetyAndIntentTest` (3), `ZehnRobolectricTest` (2), `ZehnCriticalFeaturesTest` (7), `ZehnV33FeaturesTest` (6), `ZehnV40AutonomousUpgradeTest` (4), `ZehnV41AccessibilityRepairTest` (12, API 28 & 35), and `ZehnV42SelfUpgradeTest` (6).
- Installable Debug APK assembled & verified (`assembleDebug` succeeded).

> Physical device features (OS `PackageInstaller` session UI & `REQUEST_INSTALL_PACKAGES` grant, OS-bound Accessibility Service connection, live `dispatchGesture` execution across third-party apps, live 16kHz/24kHz Gemini Live audio, CameraX preview hardware, MediaProjection consent dialog, and CI GitHub Actions runner execution) cannot run in this headless cloud environment and are honestly classified as `2. Implemented but not tested.`

---

## Classification Key

1. **1. Fully implemented and tested.** — Implemented in production code and verified in Robolectric/JUnit unit tests during this build.
2. **2. Implemented but not tested.** — Compiled cleanly and wired end-to-end in Kotlin/Compose/Manifest, requiring a physical Android device (Android 7–16) or external live credentials/CI runner.
3. **3. Partially implemented.** — Configuration/bridge hooks present; external runtime component intentionally out-of-bundle.
4. **4. UI placeholder only.** — Zero in V4.1.
5. **5. Missing.** — Capability deliberately not bundled.
6. **6. Blocked by Android or device restrictions.** — Restricted by standard non-root Android security boundaries.

---

## Detailed Subsystem Audit

### 0. Autonomous Self-Upgrade, Release Builder, Verified Updater & Safe Mode (V4.2)

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Continuous Discovery Pre-Filters & Scoring (`AutonomousResearchAgent`, `RepoSafetyAnalyzer`, `UpgradeScheduler`) | **1. Fully implemented and tested.** *(GPL/AGPL/non-commercial/unknown rejection, stale >18 mo check, HF missing model card, RAM limit, fit < 7.0 Suggested gating, protected path flagging, Room v2->v3 migration)* / **2. Implemented but not tested.** *(live periodic WorkManager execution on Wi-Fi)* | Verified in `ZehnV42SelfUpgradeTest`. Daily Wi-Fi-only / battery-aware `WorkManager` queries GitHub REST API + Hugging Face Hub API from editable topics; auto-approve only if `fitScore >= 8.0`, switch ON (default OFF), and zero protected paths. |
| GitHub Auto-Dev PR Cap, Cycle Telemetry, Issue Filing & Rollback PR (`GitHubAutoDevEngine`) | **1. Fully implemented and tested.** *(max 2 open PRs, exact cycle stages `Idle`/`Running`/`Waiting for CI`/`Waiting for approval`/`Failed`, secret redaction on Issues, monotonic Rollback PR, rate-limit backoff)* / **2. Implemented but not tested.** *(live remote repo & 5x CI self-repair loop)* | Verified in `ZehnV42SelfUpgradeTest`. Real GitHub open/merged PR and workflow counts; 403/429 `Retry-After`/`X-RateLimit-Reset` backoff; protected paths always require manual approval. |
| CI/CD Release & Branch Verification Workflows (`.github/workflows/release.yml`, `android-build.yml`, `build.yml`) | **2. Implemented but not tested.** *(valid YAML workflow definitions; requires GitHub Actions runner)* | `release.yml` decodes fixed `debug.keystore.base64`/`DEBUG_KEYSTORE_BASE64`, assigns `versionCode = GITHUB_RUN_NUMBER` & `versionName = "4.0.<run>"`, signs APK, generates `.sha256`, builds changelog from merged PRs, and publishes GitHub Release via `softprops/action-gh-release@v2`. |
| In-App Verified Updater & Pre-Update Backup (`ReleaseUpdateManager`, `PackageInstallStatusReceiver`, `BackupManager`) | **1. Fully implemented and tested.** *(Stable/Beta channel filter, monotonic upgrade guard, HTTP `Range` resume in `cacheDir/updates/`, SHA-256 + cert digest verification, pre-update JSON snapshot & restore)* / **2. Implemented but not tested.** *(on-device `PackageInstaller` Session UI & `REQUEST_INSTALL_PACKAGES` grant)* | Verified in `ZehnV42SelfUpgradeTest`. Supports private repos via Keystore PAT, Wi-Fi-only toggle + byte counter, cache cleanup broadcast receiver on install success, and automatic `BackupManager` snapshot before install. |
| Crash Guard Safe Mode & Health/Emergency Controls (`CrashRecoveryManager`, `SafeModeScreen`, `AutonomousUpgradeCenterScreen`, `HomeScreen`) | **1. Fully implemented and tested.** *(3 crashes in 5 min window activation, exit reset, secret sanitization)* / **2. Implemented but not tested.** *(interactive Compose UI flows on device)* | `SafeModeScreen` offers sanitized crash log, minimal Basic Chat, Create GitHub Issue, Trigger Auto-Dev Fix, Restore Backup, and Exit Safe Mode. Includes `HomeScreen` Emergency Pause toggle, 5-tab Upgrade Center (`Proposals`, `Discovery`, `Updater`, `Health`, `Monitor & Logs`), provider reachability & Voice Health Check (`Last Successful Voice Test`), Storage/Battery monitor, and sanitized error log viewer. |

### 1. Screen Vision, Accessibility Automation & Gesture Repair (V4.1)

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Accessibility Manifest Registration & Config (`AndroidManifest.xml`, `accessibility_service_config.xml`) | **1. Fully implemented and tested.** *(manifest, exported="true", permission & XML caps)* / **2. Implemented but not tested.** *(live OS Accessibility Settings toggle)* | Fixed `android:exported="true"` with `BIND_ACCESSIBILITY_SERVICE`, removed deprecated `flagRequestEnhancedWebAccessibility`, enabled `canPerformGestures`, `canRetrieveWindowContent`, `canTakeScreenshot`, and clean `onUnbind`/`onInterrupt`/`refreshServiceConfiguration()`. Verified on API 28 & API 35 in `ZehnV41AccessibilityRepairTest`. |
| Permission vs Connected-State Detection (`ZehnAccessibilityService`) | **1. Fully implemented and tested.** *(Settings.Secure + AccessibilityManager separation)* / **2. Implemented but not tested.** *(live detached-service recovery)* | Accurately distinguishes OS-level enabled setting (`ENABLED_ACCESSIBILITY_SERVICES`) from live bound `isConnected` state across `AccessibilityDebugScreen`, `DiagnosticsScreen`, `TouchGuardScreen`, and `PermissionsScreen`. |
| Structured Screen Reading & Password Masking (`observeScreenStructured`) | **1. Fully implemented and tested.** *(password/PIN/OTP heuristics, disconnected guard)* / **2. Implemented but not tested.** *(live external app node tree)* | Multi-window `windows` fallback when `rootInActiveWindow` is null, child/depth safety limits, bounds/actions/input/scrollable node extraction, and strict masking (`[PROTECTED_PASSWORD]`, `[PROTECTED_FIELD]`). |
| Verified Click, Scroll, Swipe & Long-Press (`ZehnAccessibilityService`) | **1. Fully implemented and tested.** *(disconnected failure + Room logging)* / **2. Implemented but not tested.** *(on-device Accessibility actions & gestures)* | Walks up to 7 parent hops for `isClickable`/`isLongClickable`, calls `node.refresh()`, clamps gestures inside `WindowManager.currentWindowMetrics` screen bounds, awaits `GestureResultCallback` with timeouts via `suspendCancellableCoroutine`, and enforces `SafetyPolicyManager` payment gates. |
| Screenshot Capture & On-Device ML Kit OCR (`ScreenCaptureService`, `ScreenVisionManager`) | **1. Fully implemented and tested.** *(manifest mediaProjection FGS declaration, consent status, unavailable error)* / **2. Implemented but not tested.** *(live screen capture + ML Kit OCR + Vision LLM)* | Explicit Separation: Method A (Accessibility tree) vs Method B (`MediaProjection` Foreground Service with `MediaProjection.Callback.onStop()` cleanup + Android 11+ `AccessibilityService.takeScreenshot` fallback + ML Kit `TextRecognition`). |
| Bilingual Compound Intent & 6-Stage `AgentLoop` (`AgentEngine`, `GeminiLiveAudioClient`) | **1. Fully implemented and tested.** *(compound & Roman Urdu parsing, target extraction, disconnected fail)* / **2. Implemented but not tested.** *(end-to-end live third-party app control)* | Fixed compound routes like `"WhatsApp kholo aur chat list scroll karo"` and `"Screen par Settings button click karo"`, added 7 dedicated Accessibility/OCR tools to Gemini Live Voice, and wired real `OBSERVE -> UNDERSTAND -> PLAN -> EXECUTE -> VERIFY -> RECOVER` on `Dispatchers.Default`. |
| Accessibility Debug Dashboard (`AccessibilityDebugScreen`) | **2. Implemented but not tested.** *(compiled, routed in Hub/Diagnostics/Touch Guard & APK built)* | Displays all 11 live telemetry fields, all 7 real test buttons (`Test Screen Reading`, `Test Click`, `Test Scroll Down`, `Test Scroll Up`, `Test Swipe Left`, `Test Swipe Right`, `Reconnect Service`), `MediaProjection` + OCR controls, and built-in interactive test targets (counter button, 20-item scrollable list, horizontal card strip). |

### 2. Autonomous GitHub Research, Self-Upgrade & APK Build Engine (V4.0 Preserved)

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Safety, License, Security & Duplicate Analyzer (`RepoSafetyAnalyzer`) | **1. Fully implemented and tested.** | Verified in `ZehnV40AutonomousUpgradeTest`. |
| Autonomous Research Agent (`AutonomousResearchAgent`) | **1. Fully implemented and tested.** *(parsing, inspection, Room dedup)* / **2. Implemented but not tested.** *(live internet scan)* | Covers 14 GitHub topics + 6 Hugging Face tracks with Room caching. |
| GitHub Auto-Dev Engine (`GitHubAutoDevEngine`) | **1. Fully implemented and tested.** *(scripted Git Data API & rollback)* / **2. Implemented but not tested.** *(live remote repo)* | Branch-isolated commits (`zehn-auto/...`), PRs, bounded CI log self-repair, approval-gated merge, rollback. |
| Release & Update Notification Manager (`ReleaseUpdateManager`) | **1. Fully implemented and tested.** *(semver, trusted origin)* / **2. Implemented but not tested.** *(live tray notification + download)* | Trusted GitHub URL & `minSdk` checks + standard OS install flow. |
| GitHub Actions CI/CD (`.github/workflows/android-build.yml`) | **2. Implemented but not tested.** *(updated for `4.1.*`; runs on GitHub Actions)* | Runs `gradle` wrapper-independent tests, `lintDebug`, deps audit, `assembleDebug` + `aapt2` check, optional signed `assembleRelease`, artifact uploads. |

### 3. Core AI, Security, Voice, Camera & Call Systems (V1.0–V3.3 Preserved)

| Feature | Classification | Notes & Evidence |
|---|---|---|
| Global Crash Logger & Recovery (`ZehnApplication`, `CrashRecoveryManager`) | **1. Fully implemented and tested.** | Verified in `ZehnCriticalFeaturesTest`. |
| Keystore AES-256-GCM Secret Vault (`KeystoreCryptoManager`) | **1. Fully implemented and tested.** | Encrypted storage & Gemini multi-key rotation verified. |
| Multi-Provider AI Brain & Room RAG (`CloudLlmClient`, `AiEngineRouter`, `RagEngine`) | **1. Fully implemented and tested.** *(Room RAG, offline guard)* / **2. Implemented but not tested.** *(live external LLM HTTP & native LiteRT-LM)* | Supports Gemini, OpenAI, Groq, OpenRouter, Hugging Face, Anthropic, Together, Cohere, Ollama, and LiteRT-LM. |
| Bilingual Voice Control & Gemini Live Audio | **1. Fully implemented and tested.** *(LanguageDetector, STOP, barge-in, model list)* / **2. Implemented but not tested.** *(live mic PCM hardware)* | WebSocket Live audio with automatic Classic STT/LLM/TTS fallback. |
| CameraX Vision & Call Screening (`ZehnCallScreeningService`, `CallAssistantManager`) | **1. Fully implemented and tested.** *(VIP/permission/uplink audit)* / **2. Implemented but not tested.** *(live calls/camera hardware)* | CameraX + ML Kit OCR/QR + Telecom screening. |

### 4. Blocked by Android OS or Device Restrictions

| Capability | Classification | Technical Reason & Implemented Alternative |
|---|---|---|
| Self-Rewriting Installed APK Binary / Dynamic DEX/JAR Loading | **6. Blocked by Android or device restrictions.** | Handled exclusively via GitHub Auto-Dev PRs, fixed-keystore GitHub Actions CI builds (`release.yml`), SHA-256 + cert checks, and Android `PackageInstaller` Session API (`REQUEST_INSTALL_PACKAGES`). |
| Injecting TTS Audio Directly into Cellular Call Uplink Microphone | **6. Blocked by Android or device restrictions.** | Blocked by non-root Android Telecom APIs (`canInjectAudioIntoCellularUplink = false`). |
| Force-Stopping Arbitrary Third-Party Apps / Silent Background Mic or Camera | **6. Blocked by Android or device restrictions.** | Restricted to system/signature privileges; Zehn uses Home/Back global actions and visible foreground notifications. |
