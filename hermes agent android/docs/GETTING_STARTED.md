# Getting Hermes Agent Running on Your Phone

This guide walks you through installing the Hermes Agent Android app on a
physical phone or emulator. Total time: **~30–45 minutes** for a first-time
setup; **~5 minutes** once Android Studio is configured.

> **Prerequisite:** A laptop or desktop running macOS, Windows, or Linux
> with at least 8 GB RAM and ~10 GB free disk space.

---

## Step 1 — Install Android Studio

Android Studio is the official IDE for Android development. It bundles the
JDK, Android SDK, build tools, and an emulator — everything you need.

1. Go to **https://developer.android.com/studio**
2. Download the version for your OS:
   - **macOS (Apple Silicon):** `android-studio-2024.1.x-mac_arm.dmg`
   - **macOS (Intel):** `android-studio-2024.1.x-mac.dmg`
   - **Windows:** `android-studio-2024.1.x-windows.exe`
   - **Linux:** `android-studio-2024.1.x-linux.tar.gz`
3. Install:
   - **macOS:** drag Android Studio to Applications, then launch.
   - **Windows:** run the `.exe`, accept defaults.
   - **Linux:** `tar -xzf android-studio-*.tar.gz -C /opt/ && /opt/android-studio/bin/studio.sh`
4. On first launch, the **Setup Wizard** appears. Click **Next** through
   it; accept the default SDK packages. This installs:
   - Android SDK Platform 34 (Android 14)
   - Android SDK Build-Tools 34.0.0
   - Android Emulator
   - Android SDK Platform-Tools
5. When the wizard finishes, click **Finish**. Android Studio opens to
   the welcome screen.

**Verify:** In Android Studio, go to **Android Studio → About Android
Studio** (macOS) or **Help → About** (Windows/Linux). The version should
be **Hedgehog (2023.1)** or newer.

---

## Step 2 — Unzip the project

1. Locate the zip file you downloaded alongside this guide:
   `hermes-agent-android.zip`
2. Unzip it to a path with no spaces, e.g.:
   - **macOS:** `~/Projects/hermes-agent-android`
   - **Windows:** `C:\Projects\hermes-agent-android`
   - **Linux:** `~/projects/hermes-agent-android`
3. Verify the unzipped structure looks like:

   ```
   hermes-agent-android/
   ├── README.md
   ├── settings.gradle.kts
   ├── build.gradle.kts
   ├── gradle.properties
   ├── gradle/
   │   ├── libs.versions.toml
   │   └── wrapper/
   │       └── gradle-wrapper.properties
   ├── app/
   │   ├── build.gradle.kts
   │   ├── proguard-rules.pro
   │   └── src/
   │       ├── main/
   │       │   ├── AndroidManifest.xml
   │       │   ├── kotlin/com/hermes/agent/...
   │       │   └── res/...
   │       └── test/...
   └── docs/
       ├── ARCHITECTURE.md
       ├── BUILD.md
       ├── MODULES.md
       ├── PHASE2.md
       ├── PHASE3.md
       ├── PHASE4.md
       └── GETTING_STARTED.md   ← you are here
   ```

> **Note:** The zip does NOT include the Gradle wrapper jar
> (`gradle/wrapper/gradle-wrapper.jar`) or the Gradle binary itself.
> Android Studio will download them on first sync — see Step 4.

---

## Step 3 — Open the project in Android Studio

1. Launch Android Studio.
2. On the welcome screen, click **Open** (not "New Project").
3. Navigate to the unzipped `hermes-agent-android/` folder and click
   **Open**.
4. Android Studio detects the Gradle project and starts a **Gradle
   Sync**. The first sync downloads:
   - The Gradle 8.9 distribution (~120 MB)
   - All Kotlin / AndroidX / Hilt / Room / Retrofit dependencies (~500 MB)
5. Wait for sync to complete. You'll see a progress bar at the bottom.
   On a fast connection this takes 2–5 minutes; on a slow one, up to 15.

**If sync fails**, check the **Build** tab at the bottom of Android
Studio. The most common issues are:

| Error | Fix |
|-------|-----|
| `SDK location not found` | Click the link in the error to open SDK Manager, install SDK Platform 34 |
| `Failed to transform kotlin-stdlib` | You're on JDK 8 or 11 — install JDK 17 via Android Studio → Settings → Build → Gradle → JDK |
| `Could not resolve com.google.dagger:hilt-android` | Check your internet connection / corporate proxy |
| `Minimum supported Gradle version is X` | Let Android Studio update the wrapper when prompted |

---

## Step 4 — Set up an emulator (recommended for first run)

If you have a physical Samsung Galaxy S24 Ultra, you can skip to Step 5.
Otherwise, create an emulator:

1. In Android Studio, click the **Device Manager** icon in the top-right
   toolbar (it looks like a phone with a downward arrow).
2. Click **+ Create Device**.
3. Pick a phone — **Pixel 8** is a good default. Click **Next**.
4. Pick a system image — **API 34 (Android 14)** is what the project
   targets. If it's not downloaded, click **Download** next to it
   (~1 GB). Accept the license, wait for download, click **Finish**.
5. Click **Next**, then **Finish** to create the AVD (Android Virtual
   Device).
6. Back in the Device Manager, click the ▶ play button next to your new
   emulator to launch it. The emulator boots in 30–60 seconds.

**Verify:** You should see an Android phone screen in a separate window
showing the home screen with the Pixel wallpaper.

---

## Step 5 — (Optional) Set up your physical phone

To run on a real Samsung Galaxy S24 Ultra or any Android 10+ phone:

1. On your phone, go to **Settings → About phone**.
2. Scroll to **Build number** (sometimes under **Software information**).
3. Tap **Build number** 7 times. You'll see "You are now a developer!"
4. Go back to **Settings → System → Developer options**.
5. Enable **USB debugging**.
6. Plug your phone into your computer with a USB cable.
7. On the phone, a dialog appears: **Allow USB debugging?** Tap **Allow**
   (and check "Always allow from this computer").
8. In Android Studio, your phone should now appear in the **Device
   dropdown** at the top of the window (next to the ▶ Run button).

**Verify:** In Android Studio, the device dropdown at the top should
show your phone's model name (e.g. "SM-S928B" for the S24 Ultra).

---

## Step 6 — (Optional) Configure a cloud LLM API key

The app works out of the box with a **mock on-device LLM** that returns
canned responses. To get real LLM replies, configure a cloud API key:

### Option A — Build-time (recommended for personal use)

1. In the project root (next to `settings.gradle.kts`), create a file
   named `hermes.local.properties`.
2. Add these lines (replace with your actual key):

   ```properties
   hermes.cloudApiKey=sk-your-openai-key-here
   hermes.cloudBaseUrl=https://api.openai.com/v1
   hermes.cloudModel=gpt-4o-mini
   ```

3. Save the file. It's gitignored — it won't be committed.
4. **Re-sync Gradle** (click the 🐘 elephant icon with the sync arrows
   in the top toolbar) so `BuildConfig.CLOUD_API_KEY` picks up the new
   value.

### Option B — Runtime (recommended if sharing the build)

1. Skip the `hermes.local.properties` file.
2. Run the app (Step 7).
3. Open the **Settings** tab in the app.
4. Under **Cloud LLM**, toggle **Cloud fallback** on.
5. Tap **Cloud API key** and paste your key. It's encrypted at rest via
   Android Keystore.

### Supported backends

Any OpenAI-compatible endpoint works. Replace `cloudBaseUrl` and
`cloudModel` accordingly:

| Backend | Base URL | Model example |
|---------|----------|---------------|
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Together AI | `https://api.together.xyz/v1` | `meta-llama/Llama-3-8B-chat-hf` |
| Anyscale | `https://api.endpoints.anyscale.com/v1` | `meta-llama/Meta-Llama-3-8B-Instruct` |
| vLLM (self-hosted) | `http://your-host:8000/v1` | any served model |
| Ollama | `http://localhost:11434/v1` | `llama3` |
| llama.cpp server | `http://your-host:8080/v1` | any served model |

> **Note on `localhost`:** if you're running the app on the Android
> emulator and want to reach a server on your computer, use
> `http://10.0.2.2:PORT` instead of `http://localhost:PORT`. The
> emulator's `localhost` is the emulator itself, not your computer.

---

## Step 7 — Build and run the app

1. In Android Studio, make sure your emulator or phone is selected in
   the **device dropdown** at the top.
2. Click the green ▶ **Run** button (or press `Shift+F10` /
   `Control+R` on macOS).
3. Android Studio:
   - Runs `./gradlew assembleDebug` (1–3 minutes on first run).
   - Installs the resulting APK on the device.
   - Launches `MainActivity`.
4. The app opens to the **onboarding flow** on first launch.

**If the build fails**, check the **Build** tab. Common issues:

| Error | Fix |
|-------|-----|
| `Execution failed for task ':app:kspDebugKotlin'` | Hilt/KSP version mismatch — click "Try again" once; if it persists, run `./gradlew --stop` then build again |
| `Cannot fit requested classes in a single dex file` | Rare; add `multiDexEnabled true` to `app/build.gradle.kts` defaultConfig |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | Free up space on the emulator, or wipe data via Device Manager → ⋮ → Wipe Data |
| `CameraX/SAF permission denied` at runtime | The app asks at first use; if you declined, go to Settings → Apps → Hermes → Permissions |

---

## Step 8 — First-launch onboarding

The first time you launch the app you'll see a 3-screen onboarding flow:

1. **Welcome** — Hermes brand intro. Click **Continue**.
2. **Privacy** — explains on-device-first architecture. Click **Continue**.
3. **Permissions** — request microphone (for voice input) and
   notifications (for the notification agent). Click **Allow** for
   each, or **Skip** to decline — you can grant them later.
4. Click **Get started**.

You're now on the **Conversations** screen. Tap the **+** FAB
(floating action button) in the bottom-right to start a new chat.

---

## Step 9 — Try the demo prompts

In a new conversation, try these prompts to exercise different parts of
the app:

### Tool calling (Conversational agent)

| Prompt | What happens |
|--------|--------------|
| `what time is it in Tokyo?` | Conversational agent calls `get_current_datetime` tool → reply includes Tokyo time |
| `calculate (15 + 27) * 4` | Conversational agent calls `calculator` tool → reply shows `168` |
| `remember that I prefer dark mode` | Conversational agent calls `notes` tool → memory is persisted |
| `hello` | Plain conversational reply, no tool call |

### Multi-agent routing

| Prompt | Routed to |
|--------|-----------|
| `search for recent EU AI Act news then draft a short summary memo` | Research → Creative (multi-agent plan) |
| `schedule a meeting for tomorrow at 3pm` | Productivity agent |
| `write a short poem about autumn` | Creative agent |
| `lower the screen brightness to 100` | Device Control agent (with confirmation) |

### Voice input

1. Tap the **mic icon** in the input bar (left of the text field).
2. Grant **RECORD_AUDIO** permission when prompted.
3. Speak a short prompt. The recognized text appears in the input field.
4. Tap **send**.

### Voice output

Every assistant reply is automatically spoken aloud via Android's TTS
engine. To stop speech mid-utterance, tap the **stop** button that
appears in the input bar while a reply is streaming.

### S Pen (Samsung devices only)

On a Galaxy S24 Ultra (or other S Pen device), an **S Pen toggle**
appears in the input bar between the mic and the text field. Tap it to
toggle handwriting mode.

> **Note:** Phase 4 only flips the toggle icon color — the actual stroke
> capture surface is staged for Phase 3.x. The S Pen SDK is probed at
> runtime via reflection; on non-Samsung devices the toggle is hidden.

### Plugins

1. Tap the **Plugins** tab in the bottom nav.
2. You'll see three plugins: **Weather**, **File Manager**, **Contacts**.
3. Toggle **Weather** on (it's installed by default but not active).
4. Go back to Conversations, start a new chat, and ask:
   `what's the weather in Paris?`
5. The Conversational agent will call the `weather_get` tool (provided
   by the active Weather plugin) and reply with mock weather data.

### Memory

1. Tap the **Memory** tab.
2. Add a memory manually, e.g. `I'm vegetarian`.
3. In a chat, ask `what do you know about my diet?`
4. The Conversational agent will call the `notes` tool with
   `action=recall` to retrieve the memory.

### Documents (RAG)

1. Tap the **Documents** tab.
2. Tap the **+** FAB and paste a paragraph of text (e.g. from a news
   article).
3. The document is chunked and indexed.
4. In a chat, ask a question about the document's content. (Full RAG
   grounding of LLM replies is staged for Phase 3.x, but the chunker +
   BM25 + vector ANN pipeline runs end-to-end now.)

---

## Step 10 — Explore the Settings

Tap the **Settings** tab to explore:

- **Inference** — toggle on-device / cloud; adjust idle-unload minutes
  and complexity threshold.
- **Cloud LLM** — paste your API key (encrypted at rest), change base
  URL / model.
- **Security & Privacy** — Knox status (not available on non-Samsung),
  Keystore status.
- **About** — app version, build type, current phase.
- **Security audit** (Phase 4) — a checklist of 12 security controls
  with ENFORCED / PARTIAL / PENDING status icons.

---

## Step 11 — Build a release APK (optional)

If you want to share the app with someone outside Android Studio:

### Unsigned release APK (quick, for testing)

1. In Android Studio, go to **Build → Build Bundle(s) / APK(s) →
   Build APK(s)**.
2. Wait for the build to finish.
3. Click the **locate** link in the popup notification. This opens the
   folder containing `app-debug.apk`.
4. Share that APK file. The recipient can install it via "Install
   unknown apps" on their phone.

### Signed release APK (for distribution)

1. Generate a signing key (one-time):

   ```bash
   keytool -genkeypair -v \
     -keystore hermes-release.jks \
     -alias hermes-release \
     -keyalg RSA -keysize 4096 \
     -validity 10000
   ```

2. Create `hermes.local.properties` in the project root (next to
   `settings.gradle.kts`) with:

   ```properties
   hermes.signing.storeFile=/absolute/path/to/hermes-release.jks
   hermes.signing.storePassword=your-store-password
   hermes.signing.keyAlias=hermes-release
   hermes.signing.keyPassword=your-key-password
   ```

3. Re-sync Gradle (🐘 icon with arrows).
4. Go to **Build → Generate Signed Bundle / APK → APK**.
5. The signing config is auto-detected. Click **Next**, pick
   **release**, click **Finish**.
6. The signed APK appears at
   `app/build/outputs/apk/release/app-release.apk`.

---

## Troubleshooting

### The app crashes on launch

Open **Logcat** in Android Studio (bottom tab). Filter by `package:com.hermes.agent`
and look for `FATAL EXCEPTION`. The most common causes:

- **Missing Hilt binding** — a Kotlin class is missing `@Inject`. The
  stack trace will name the class.
- **Room schema migration failure** — uninstall the app from the device
  (Device Manager → ⋮ → Uninstall) and reinstall.

### Voice input doesn't work

- Verify the mic permission: Settings → Apps → Hermes → Permissions →
  Microphone.
- Test the mic in another app (e.g. Google search) to rule out hardware.
- On the emulator, the mic uses your computer's microphone — make sure
  it's not muted.

### Cloud LLM calls fail

- Open Settings → Cloud LLM and verify the API key is set.
- Check Logcat for `CloudLlm` tag errors.
- If you see `401 Unauthorized`, the key is wrong.
- If you see `Connection refused` on the emulator, you used
  `localhost` instead of `10.0.2.2`.

### S Pen toggle doesn't appear

- You're not on a Samsung device. The toggle is hidden via
  `SPenManager.isAvailable` which probes for the Samsung SDK class at
  runtime.

### Plugins tab is empty

- The three first-party plugins (Weather / File Manager / Contacts) are
  auto-installed at app startup. If the list is empty, check Logcat for
  `PluginRegistry` errors. Force-stop the app and relaunch.

### Build is slow

- First build downloads ~500 MB of dependencies — unavoidable.
- Subsequent builds are faster (~30s for incremental).
- To speed up: in `gradle.properties`, ensure `org.gradle.parallel=true`
  and `org.gradle.caching=true` (they already are).
- If you have >8 GB RAM, bump `org.gradle.jvmargs` to `-Xmx8192m`.

---

## What to expect (and what's stubbed)

The app runs end-to-end against **mock / stub backends**. This is by
design — see `docs/PHASE4.md` § "What's still staged" for the full list.

| Feature | Phase 4 status | What you'll see |
|---------|----------------|-----------------|
| Multi-agent orchestration | ✅ Working | Agent-role badge on each reply |
| Tool calling (7 first-party + 3 plugin) | ✅ Working | Tool-call cards appear above the streaming reply |
| Real SSE cloud streaming | ✅ Working | Tokens stream in real time from your cloud LLM |
| Voice I/O | ✅ Working | Mic button + auto-speak-reply |
| Plugin framework | ✅ Working | 3 plugins in the Plugins tab, toggleable |
| On-device LLM | Mock | Canned replies — see `OnDeviceLlmProvider` |
| Embeddings (for vector search) | Mock | SHA-256 hashing — search still works, just less semantic |
| Memory consolidation | Working but simple | Regex-based fact extraction (no LLM summarization yet) |
| S Pen stroke capture | Toggle-only | Icon flips color; no actual handwriting recognition yet |
| gRPC plugin sandbox | Stub | Third-party plugins can't load (only first-party in-process) |

The contracts (`LlmProvider`, `EmbeddingService`, `VectorStore`,
`PluginSandbox`, `SPenManager`) are stable — swapping in real backends
is a per-class change documented in each file.

---

## Where to go next

- **`README.md`** — project overview + roadmap.
- **`docs/ARCHITECTURE.md`** — layered design + Mermaid sequence diagrams.
- **`docs/MODULES.md`** — per-package reference.
- **`docs/PHASE2.md`** / **`PHASE3.md`** / **`PHASE4.md`** — what each
  phase added.
- **`docs/BUILD.md`** — advanced build topics (CI, release signing,
  backend swap instructions).

Enjoy exploring Hermes! If something doesn't work, the
[Troubleshooting](#troubleshooting) section above covers the most common
issues. For deeper questions, the architecture docs explain why each
piece exists and how it maps back to the technical plan.
