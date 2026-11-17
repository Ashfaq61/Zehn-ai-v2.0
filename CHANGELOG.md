# CHANGELOG — Zehn AI (`com.zehn.assistant`)

## V4.2 — Complete Self-Upgrade System (Discovery, Release CI, Verified Updater & Safe Mode)

### Added
- **Continuous Discovery Pipeline (`AutonomousResearchAgent`, `RepoSafetyAnalyzer`, `UpgradeScheduler`, `ResearchScheduleWorker`)**:
  - Daily Wi-Fi/battery-aware `WorkManager` scan querying GitHub REST API and Hugging Face Hub API from an editable topic list.
  - Strict pre-filters reject GPL/AGPL/non-commercial/unknown licenses, unmaintained repos (>18 months stale), missing Hugging Face model cards, and over-RAM / Android-incompatible items before scoring.
  - Items scoring `< 7.0` remain `"Suggested"` (never auto-integrated); items `>= 8.0` auto-approve only when `"Auto-approve"` is ON (default OFF) and touch no protected paths (`AndroidManifest.xml`, Accessibility, Security/Touch Guard, Keystore/tokens, signing, workflows).
- **Hardened GitHub Auto-Dev (`GitHubAutoDevEngine`)**:
  - Enforces a strict max of 2 open Auto-Dev PRs, duplicate branch prevention, exact cycle telemetry (`Idle`, `Running`, `Waiting for CI`, `Waiting for approval`, `Failed`), real GitHub open/merged PR and CI counts, `Retry-After`/`X-RateLimit-Reset` 403/429 backoff, and non-downgrading rollback PRs.
- **Monotonic Release CI/CD & Fixed Keystore Signing (`.github/workflows/release.yml`, `android-build.yml`, `build.yml`)**:
  - `release.yml` runs on `push` to `main`, decodes the committed `debug.keystore.base64` / `DEBUG_KEYSTORE_BASE64` (never regenerating certs), sets `versionCode = GITHUB_RUN_NUMBER` and `versionName = "4.0.<versionCode>"`, publishes signed APK + `.sha256` + merged PR changelog via `softprops/action-gh-release@v2`. Branch/PR workflows upload artifacts only.
- **Verified In-App Updater & Pre-Update Backup (`ReleaseUpdateManager`, `PackageInstallStatusReceiver`, `BackupManager`)**:
  - Checks GitHub Releases every 4 hours, on startup, and on demand (`STABLE` vs `BETA` channels, private repo PAT support, strict upward `versionCode` guard).
  - Resumable HTTP `Range` downloads into `cacheDir/updates/`, Wi-Fi-only toggle + byte counter, mandatory SHA-256 & `GET_SIGNING_CERTIFICATES` key match check, automatic `BackupManager` snapshot before install, and `PackageInstaller` Session API (`REQUEST_INSTALL_PACKAGES` + `USER_ACTION_NOT_REQUIRED` on Android 12+).
- **Crash Guard Safe Mode, Emergency Pause & Health Monitor (`CrashRecoveryManager`, `SafeModeScreen`, `AutonomousUpgradeCenterScreen`, `HomeScreen`, `ZehnDatabase` v3)**:
  - Triggers Safe Mode automatically after 3 crashes within 5 minutes, providing sanitized crash log, minimal Basic Chat, Create GitHub Issue, Trigger Auto-Dev Fix, Restore Backup, and Exit Safe Mode.
  - Added `HomeScreen` Emergency Pause toggle, `MIGRATION_2_3`, 5-tab Upgrade Center (`Proposals`, `Discovery`, `Updater`, `Health`, `Monitor & Logs`), live provider tests, Voice Health Check (`Last Successful Voice Test`), and Storage/Battery/Error Log panels.
- **V4.2 Verification (`ZehnV42SelfUpgradeTest`)**:
  - Added 6 Robolectric unit tests verifying Room v2->v3 migration, strict pre-filters, fit-score & protected-path gating, max-2 PRs & secret redaction, Range/SHA-256/cert updater checks + backups, and 3-crash/5-min Safe Mode activation; total verified suite stands at **41 / 41 passing**.

---

## V4.1 — Screen Vision & Android Accessibility Automation Repair (`versionCode = 5`)

### Fixed
- **Accessibility Service Binding (`AndroidManifest.xml`, `accessibility_service_config.xml`, `ZehnAccessibilityService`)**:
  - Changed `ZehnAccessibilityService` from `android:exported="false"` to `android:exported="true"` with `BIND_ACCESSIBILITY_SERVICE` permission so Android 7–16 binds the service reliably.
  - Removed deprecated `flagRequestEnhancedWebAccessibility`, configured `flagReportViewIds|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews`, `canPerformGestures="true"`, `canRetrieveWindowContent="true"`, `canTakeScreenshot="true"`, and clean `onUnbind`/`onInterrupt`/`onDestroy`/`refreshServiceConfiguration()` lifecycle tracking.
- **Silent False-Positive Clicks, Scrolls & Swipes**:
  - Replaced direct leaf-node clicking and unverified fire-and-forget `dispatchGesture(..., null, null)` calls with clickable/long-clickable ancestor discovery (up to 7 parent hops), `AccessibilityNodeInfo.refresh()`, verified `GestureResultCallback` suspension (`suspendCancellableCoroutine` + timeouts), largest visible scrollable container prioritization, and `WindowManager.currentWindowMetrics` visible-bounds coordinate clamping.
  - Added multi-window `windows` fallback when `rootInActiveWindow` returns null, plus strict password/PIN/OTP masking (`isPassword`, `isPasswordViewId`, `isPasswordInputType`).
- **Compound & Bilingual GUI Voice Commands (`AgentEngine`)**:
  - Fixed `IntentClassifier.classify()` so compound commands such as `"WhatsApp kholo aur chat list scroll karo"` and `"Open Settings and tap Wi-Fi"` route to `COMPOUND_GUI` (`STANDARD_PATH`) instead of misrouting to SMS/WhatsApp message sending.
  - Fixed `extractClickTarget()` for English/Roman-Urdu targets (e.g., `"Screen par Settings button click karo"` -> `"Settings"`).
  - Upgraded `AgentLoop.runGuiLoop()` to a non-blocking `Dispatchers.Default` 6-stage `OBSERVE -> UNDERSTAND -> PLAN -> EXECUTE -> VERIFY -> RECOVER` pipeline with genuine post-action screen state checks and OCR recovery fallback.

### Added
- **Two-Method Screen Vision & OCR Engine (`ScreenCaptureService`, `ScreenVisionManager`)**:
  - Explicitly separated **Method A** (Accessibility tree observation) from **Method B** (`MediaProjection` Foreground Service with `foregroundServiceType="mediaProjection"` & `MediaProjection.Callback.onStop()` cleanup, plus Android 11+ `AccessibilityService.takeScreenshot` fallback) paired with on-device ML Kit `TextRecognition` OCR and optional Vision LLM analysis.
- **Accessibility Debug Dashboard (`AccessibilityDebugScreen`)**:
  - Routed in `MainActivity` (`accessibility_debug`), Command Hub, `DiagnosticsScreen`, and `TouchGuardScreen`.
  - Shows all 11 live telemetry fields (permission enabled/disabled, service connected/disconnected, foreground app, node counts, last action & result, gesture capability, screenshot permission status, last error), all 7 real test actions (`Test Screen Reading`, `Test Click`, `Test Scroll Down`, `Test Scroll Up`, `Test Swipe Left`, `Test Swipe Right`, `Reconnect Service`), `MediaProjection` + OCR buttons, and built-in interactive test targets (live counter button, 20-item scrollable list, horizontal card strip).
- **Gemini Live Screen Vision Tools (`GeminiLiveAudioClient`)**:
  - Added `observe_screen`, `click_screen_element`, `scroll_screen`, `swipe_screen`, `long_press_screen`, `navigate_back`, and `capture_screen_ocr` tool declarations and handlers.
- **V4.1 Automated Test Suite (`ZehnV41AccessibilityRepairTest`)**:
  - Added 12 Robolectric multi-SDK tests (`@Config(sdk = [28, 35])`); total verified test suite expanded to **35 / 35 passing** alongside verified `app-debug.apk`.

---

## V4.0 — Autonomous GitHub Research, Self-Upgrade & APK Build Engine
*(Preserved in full: `AutonomousResearchAgent`, `RepoSafetyAnalyzer`, `GitHubAutoDevEngine`, `ReleaseUpdateManager`, `UpgradeScheduler`, `AutonomousUpgradeCenterScreen`, Room v2 migration, and CI/CD workflow.)*

---

## V3.3 — Autonomous Android Assistant Upgrade
*(Preserved in full: HTTPS network security config, Gemini `x-goog-api-key` headers, Hugging Face Inference Router, key-slot CRUD + latency/usage metrics, bilingual voice commands, Continuous Conversation mode, CameraX photo/video/flash/lens flip, Call Screening + VIP filter + Auto-Answer, Open-Source Discovery UI, and Honest Diagnostics.)*
