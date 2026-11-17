# Zehn AI V4.2 (`com.zehn.assistant`)

**Zehn AI V4.2** is an autonomous, privacy-first Android AI assistant featuring continuous GitHub & Hugging Face research discovery, license & security-gated feature staging on isolated GitHub branches, automated GitHub Actions APK building, verified release update notifications, auto-generated permanent keystore in fresh repositories, repository readiness diagnostics, and Safe Mode crash recovery—built on top of Zehn's bilingual voice assistant, 6-stage Accessibility automation agent, CameraX vision, Call Screening, and LiteRT-LM on-device engine (`minSdk 24`, `targetSdk 35`).

---

## V4.2 Highlights

- **Autonomous Upgrade Center (`upgrade_center` in Command Hub)**:
  - Live telemetry dashboard: GitHub connection status, research cycle stage, last/next scan schedule, discovered tools, approved & integrated counts, CI build pass count, and latest APK release.
  - Complete controls: `Start Research`, `Stop Research`, `Scan GitHub`, `Scan Hugging Face`, `Review New Features`, `Approve Integration`, `Reject Integration`, `Build APK`, `Check Updates`, and `Restore Previous Version`.
- **Zero-Manual-Keystore Build Pipeline**:
  - Automatically checks whether `app/debug.keystore` exists in the repo.
  - If missing on a push to `main`, auto-generates a permanent debug keystore using `keytool` and commits it to `main` as the GitHub Actions bot (`git add -f`).
  - PR and branch builds create a temporary keystore in the runner only.
- **Repository Readiness & Bootstrap**:
  - Real API checks for token validity, repo reachability, contents write permissions, actions enabled, keystore presence, build.yml presence, and build status with clear fix hints.
  - 1-tap **Bootstrap new repo** button to create missing workflow files and `.gitignore`.
- **Build Status Dashboard Component**:
  - Fetches the latest workflow run status from the GitHub API with color-coded status icons (green for success, red for failure, yellow for running).

---

## Using a New Repository

To deploy and build Zehn AI in any fresh GitHub repository with zero manual keystore setup:
1. Set your repository slug (`owner/repo`) in Zehn AI's Upgrade Center / Settings.
2. Provide your fine-grained GitHub PAT with access to the repository.
3. In GitHub Repository **Settings > Actions > General > Workflow permissions**, select **Read and write permissions**.
4. Push to `main` once (or use the **"Bootstrap new repo"** button in the Upgrade Center to create the workflow files).
5. The first build automatically generates and commits `app/debug.keystore` permanently to `main` as the GitHub Actions bot. Subsequent builds and updates use it seamlessly!

---

## Build & Test Commands

- **AI Studio / system Gradle container**:
  ```bash
  gradle :app:testDebugUnitTest --console=plain
  gradle assembleDebug
  ```
- **Local Android Studio / Git checkout**:
  ```bash
  ./gradlew clean testDebugUnitTest lintDebug assembleDebug --stacktrace
  ```
