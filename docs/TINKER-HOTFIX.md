# Hot-fix patches (Tinker)

Hermes can fix its own Kotlin/Java code, native libraries and resources **without a
reinstall**: a small patch built on the release PC is published with the release, the phone
offers **Apply fix (restart required)**, verifies it, and Tinker loads it the next time Hermes
starts. It is not "live": the user always restarts, and always chooses to.

This is the delivery path for `APP_CHANGE` fixes from [feature evolution](FEATURE-EVOLUTION.md)
once their pull request is merged, and for any other small fix to a published release.

Runtime: Tencent Tinker `1.9.15.2` (`tinker-android-lib` / `tinker-android-loader`).
Patch generation: Tinker's own `tinker-patch-lib` of the same version, driven by `tools/tinker`.

```
merged PR ──► PC: build-patch (fix build vs archived base, tinker-patch-lib, release-key signature)
                 └─► publish: hermes-patch-<baseTinkerId>-<n>.apk + hermes-patch.json on release v<base>
phone: OTA check ──► patch for *this* TINKER_ID? ──► "Apply fix (restart required)"  [user taps]
        ──► download to app-private storage ──► PatchGate (sha256, TINKER_ID, every entry signed by
            this app's certificate) ──► Tinker :patch process (signature + TINKER_ID again)
        ──► "Restart now" [user taps] ──► Tinker loads the patch at start (and re-checks it)
```

## What a patch can and cannot change

| Can | Cannot (ship a full release) |
|---|---|
| Any Kotlin/Java code outside the loader classes below — UI, ViewModels, the Hilt graph, workers, agent-core engine code, library code | The loader classes (`HermesTinkerApplication`, `com.tencent.tinker.loader.**`) |
| Resources (strings, layouts, drawables), with stable resource ids | `AndroidManifest.xml`: new activities/services/receivers/providers, permissions, versionCode |
| `libai-chat.so` and other `System.loadLibrary` libraries | llama.cpp / ggml libraries (see risks) |
| | Anything a patch built against another base; the Room schema is code, but a migration still needs care |

## Trust model

A patch is executable code with all of Hermes' permissions, so the rule is narrow:

> **Dex/native code is loaded only from Tinker patches that pass the checks below. Code
> produced on the device, by a model, or by a bot is never loaded.** Patches are produced only by
> the PC build pipeline from reviewed, merged code, and signed there with the release key.

The checks, all of which must pass:

1. **Signature (Tinker).** Tinker's `ShareSecurityCheck` verifies the patch's JAR signature on its
   `*_meta.txt` entries against the MD5 of the installed app's signing certificate, and the meta
   files carry the MD5 of every dex/so/resource file. This runs when the patch is installed *and*
   on every load at start-up.
2. **Signature (Hermes, stricter).** `data/hotfix/PatchGate` + `PatchSignatureVerifier` require
   **every** non-`META-INF` entry to verify under the JAR signature and every signer
   certificate's **SHA-256** to be one of the installed package's current signing certificates
   (`PackageManager.GET_SIGNING_CERTIFICATES`, `apkContentsSigners`). For release builds that is
   the release key `99255c31…`; the PC scripts also refuse to write a patch whose signer is not.
3. **Base build (TINKER_ID).** The manifest's `baseTinkerId`, the patch's own
   `assets/package_meta.txt` `TINKER_ID`, and the installed manifest's `TINKER_ID` must be
   identical. Tinker re-checks the last two itself at install and at every load.
4. **Published digest.** The downloaded file's exact size and SHA-256 must match
   `hermes-patch.json` from the same GitHub release.
5. **Signed patch version.** The manifest is not signed, so its `patchVersion` must equal the
   `HERMES_PATCH_VERSION` that `tinker_config.xml` writes into the patch's signed
   `assets/package_meta.txt`. An older signed patch cannot be replayed under a higher number, and
   a removed or rolled-back fix cannot come back under a new one.
6. **One door.** `tinker/HermesPatchListener` is Tinker's only patch listener. It admits only a
   file that belongs to a verified record (`data/hotfix/PatchAdmission`): the OTA flow's staged
   download (path and MD5), or Tinker's own copy of the staged/applied patch inside its private
   `tinker` directory, which Tinker re-runs to rebuild compiled code after the system discarded it
   (e.g. an Android system update). Either way it re-runs `PatchGate` with the recorded SHA-256
   and size before Tinker's own checks. Nothing else in the app calls into Tinker with a path.

The signature is the trust anchor. The SHA-256 and TINKER_ID checks are integrity and
compatibility checks: someone who could edit the GitHub release could also change the JSON,
but still could not produce a patch this app accepts without the release key — the same
guarantee that protects a full APK update. Everything happens in app-private storage.

The user approves every step: nothing is downloaded, applied or restarted without a tap.

## Runtime architecture (Hilt + Tinker)

Tinker installs a patch in `Application.attachBaseContext` by giving the process a **new class
loader** (a `DelegateLastClassLoader` over the patched dex). Classes loaded before that — the
Application and whatever it references — stay on the original class loader forever and can never
be patched ("loader classes"); worse, a class resolved from both loaders exists twice, and a cast
between the two copies crashes.

That rules out making the `@HiltAndroidApp` class the manifest Application (with or without the
intermediate "B extends TinkerApplication" class from Tencent/tinker#1474): Hilt would build the
whole singleton component on the old loader, every injected class would be unpatchable, and
patched activities would receive old-loader objects. So:

| Class | Loader? | Role |
|---|---|---|
| `tinker/loader/HermesTinkerApplication` (Java) | **yes** | Manifest `android:name`. Extends `TinkerApplication` (`TINKER_ENABLE_ALL`, `DelegateLastClassLoader`), implements Hilt's `GeneratedComponentManager` and forwards it. Nothing else. |
| `dagger.hilt.internal.GeneratedComponentManager` | **yes** | The one interface Hilt's generated activities/services/receivers/entry points check the Application for. Listed as a loader class so both loaders share it: tinker-patch-lib strips loader classes from every patched dex (`removeLoaderForAllDex`), so the patched loader falls back to the original copy. |
| `com.tencent.tinker.loader.**`, `com.tencent.tinker.anno.**` | **yes** | Tinker's loader. |
| `tinker/HermesApplicationLike` | no | Tinker's delegate, created **through the patched loader**. Installs the Hilt component manager (built lazily), installs Tinker, runs the start-up. |
| `tinker/HermesComponentFactory` | no | Builds `DaggerHermesApp_HiltComponents_SingletonC` exactly as `Hilt_HermesApp` would (by name: Hilt generates the root after the app compiles). |
| `HermesApp` | no | `@HiltAndroidApp` **code-generation root only**; never instantiated, not in the manifest. |
| `HermesAppStartup` | no | What `HermesApp.onCreate` used to do, unchanged, members-injected through an entry point. |

Consequences:

- The whole Dagger graph, every activity, worker, service and receiver is created from patched
  classes. `@ApplicationContext` / `Application` bindings are the shell instance (the real
  Application), so nothing observable changes for app code.
- WorkManager can no longer be configured through `Configuration.Provider` on the Application
  (that would drag `androidx.work` into the loader set), so `HermesAppStartup` calls
  `WorkManager.initialize(app, config)` with the Hilt worker factory first thing. The manifest
  already removes WorkManager's default initializer.
- `tinker-patch-lib` enforces the rules at build time: a loader class that refers to a
  non-loader class, or a loader class that differs between base and fix, fails patch generation
  (`ignoreWarning=false`). `TinkerWiringTest` mirrors the first rule on the source, and CI builds
  a real patch (below).
- Tinker's `:patch` process runs the delegate but skips `HermesAppStartup`.

### Failure handling and rollback

- **Tinker safe mode:** a start that dies before the delegate attaches is counted; after
  `TINKER_SAFE_MODE_MAX_COUNT` (3) the patch is deleted.
- **Hermes crash guard** (`tinker/PatchCrashGuard`, `CrashLoopPolicy`): with a patch loaded, three
  crashes in a row within 10 s of start remove it, record "Fix #n was removed after Hermes crashed
  3 times right after start", and block that patch version from being offered again. Surviving
  10 s resets the count.
- **Load exceptions:** Tinker's `DefaultLoadReporter` behaviour is kept: a dex/resource load
  exception disables Tinker for this base (the app runs as installed; Settings says so) until the
  next full update.
- **Remove patch** (Settings → About → Updates → Hot-fixes) cleans the patch and blocks its version;
  it takes effect on restart.
- Tinker's `DefaultTinkerResultService` kills the app after a successful install. Hermes uses
  `HermesPatchResultService`, which never does; the UI asks the user to restart.

State shared between the main and `:patch` processes lives in `files/hotfix/state.json`
(`HotfixStateStore`, file-locked, atomic replace).

## Delivery through the OTA channel

A GitHub release may carry, next to (or instead of) the APK:

- `hermes-patch-<baseTinkerId>-<n>.apk` — the signed patch;
- `hermes-patch.json` — its manifest:

```json
{
  "schema": 1,
  "baseTinkerId": "hermes-90-1a2b3c4d5e6f",
  "baseVersionName": "1.1.3",
  "newTinkerId": "hermes-90-9f8e7d6c5b4a",
  "patchVersion": 1,
  "asset": "hermes-patch-hermes-90-1a2b3c4d5e6f-1.apk",
  "sha256": "<64 lowercase hex>",
  "size": 482113,
  "notes": "Fixes the calendar tool crash",
  "minRestartPrompt": true
}
```

`minRestartPrompt`: `true` asks the user to restart as soon as the fix is prepared (a filled
"Restart now" button); `false` only shows that it applies on the next start, with a quiet
"Restart now" text button (`restartPromptWanted` in `ui/settings/HotfixSection.kt`). Either way
nothing restarts by itself.

Patches are attached to **the base's own release** (`v<versionName>`); the checker also looks at
the newest release. `decideOta` (pure, unit-tested) picks:

1. the highest `patchVersion` whose `baseTinkerId` equals the installed TINKER_ID, that is newer
   than what is applied or waiting, not blocked (rolled back / removed), with its asset present
   and sized as declared → **Apply fix (restart required)**, plus "Install full update X instead"
   when a newer full release also exists;
2. else a newer full release → **Download & install** as before;
3. else nothing.

Patch assets are never mistaken for the full APK. The daily `OtaUpdateWorker` only notifies.

## PC pipeline (`tools/tinker`)

Prerequisites (the release PC): JBR 21 (`JAVA_HOME`), Android SDK (`ANDROID_HOME`, for
`apksigner`), `hermes.local.properties` + `hermes-release.jks` as for any release (never moved or
regenerated), `gh` authenticated for publishing. The scripts build the patch CLI themselves
(`./gradlew -p tools/tinker/patch-cli installDist`; Maven Central only).

The Tinker Gradle plugin is **not** used: it needs `applicationVariants` and the Transform API,
both removed in AGP 8/9. `app/build.gradle.kts` does its parts by hand:

| What | How |
|---|---|
| `TINKER_ID` | Manifest meta-data + `BuildConfig.TINKER_ID` = `hermes-<versionCode>-<git sha12>`; `-Phermes.tinkerId=` overrides. |
| Resource-id table | `archive-base` reads it from the base APK (`aapt2 dump resources`, needs `ANDROID_HOME`) into `<archive>/stable-ids.txt`; `build-patch` refuses a fix build in which any base resource id moved. (An aapt2 `--emit-ids` side output was tried first; it is an undeclared task output and CI showed it is not reliably written.) |
| Patch build mode | `-Phermes.tinker.base=<archived base>`: versionCode/versionName pinned to the base's, aapt2 `--stable-ids <base>/stable-ids.txt`, R8 `-applymapping <base>/mapping.txt` (release). |

### 1. Every release: archive the base (mandatory)

A patch can only target a base whose artifacts were archived **from the exact build that was
published**. Build the release this way (it is the normal signed `assembleRelease`, plus the
archive), then publish `base.apk` as usual:

```powershell
# Windows, repo root, clean tree at the release commit
.\tools\tinker\hermes-tinker.ps1 archive-base
# → tinker-archive\hermes-90-1a2b3c4d5e6f\ {base.apk, mapping.txt, stable-ids.txt, R.txt, tinker-base.properties, SHA256SUMS}
gh release create v1.1.3 app\build\outputs\apk\release\app-release.apk --title "Hermes v1.1.3" --latest
```

```bash
tools/tinker/hermes-tinker.sh archive-base        # Linux/macOS equivalent
```

The script refuses a dirty tree (the git sha is part of the TINKER_ID), an unsigned APK, a signer
other than `99255c31…`, a missing mapping or id table, and overwriting an archived base. Keep
`tinker-archive/` (gitignored; `HERMES_TINKER_ARCHIVE` moves it) backed up: without it no patch can
ever target that release.

### 2. After the fix is merged: build the patch

On the commit to ship (the base commit plus the merged fix — typically a branch from the release
tag with the fix cherry-picked, or `main` if nothing else changed since):

```powershell
.\tools\tinker\hermes-tinker.ps1 build-patch -Base tinker-archive\hermes-90-1a2b3c4d5e6f -PatchVersion 1 `
    -Notes "Fixes the calendar tool crash"
```

It builds `assembleRelease -Phermes.tinker.base=…`, checks the fix has the base's
versionCode/versionName/package and a different TINKER_ID, refuses llama.cpp/ggml library changes,
runs tinker-patch-lib with `tools/tinker/tinker_config.xml`, signs the patch with the release key
(`jarsigner -digestalg SHA-256`, passwords via environment variables, never on the command
line), verifies the signer, and writes `patches/1/hermes-patch-<base>-1.apk` and
`patches/1/hermes-patch.json`.

Rules: `patchVersion` starts at 1 and increases per base; every patch is diffed against the
**base**, never against a previous patch (a new patch replaces the old one on the phone).

### 3. Publish

```powershell
.\tools\tinker\hermes-tinker.ps1 publish -PatchDir tinker-archive\hermes-90-1a2b3c4d5e6f\patches\1
```

Uploads both files to release `v1.1.3` with `gh` after re-checking the SHA-256 and that the
release does not already carry an equal or newer patch; asks for confirmation (`-Yes` skips).
Phones on that build see the fix at their next check (daily, or Settings → Check for updates).

### CI

`ci.yml` has an unsigned **Tinker patch smoke check**: it archives the debug build as a base,
builds a second debug APK against it with a different TINKER_ID, and runs the same
`build-patch` with `--unsigned`. That exercises the Gradle gating, stable ids, the patch CLI and
tinker-patch-lib's loader-class checks on every PR. It holds no key and publishes nothing.

## Feature evolution

`APP_CHANGE` proposals still go through `RepairReporter` → issue → draft PR, reviewed and merged by
a human; nothing merges automatically. The issue body now asks for delivery as a hot-fix patch.
Once merged, the maintainer runs steps 2–3 against the current release base, and the fix reaches
phones without a reinstall. The Feature evolution screen says so on filed app changes. Bots never
build or sign patches.

## Known risks and limits

- **Android 16 / target 36 hidden APIs.** Tinker has no hidden-API bypass. It swaps the class
  loader through `LoadedApk.mClassLoader`, `ContextImpl.mClassLoader`, `Resources.mClassLoader`,
  and patches resources through `AssetManager`/`ResourcesManager` internals. These are on the
  "unsupported" list today; if a future Android blocks one for target 36, loading fails, Tinker
  reports a load failure, disables itself for that base and Hermes runs unpatched (a partial
  class-loader swap could still crash the start — the safe mode and crash guard then remove the
  patch). Resource patching is the most exposed part: prefer code-only fixes. **Untested on a
  device** (none was available when this was written); test every new Android version with a
  trivial patch before relying on it.
- **Loader classes are not patchable** (table above), and neither is anything that changes the
  manifest. `DelegateLastClassLoader` means library classes are patchable too.
- **R8 mapping / resource-id drift.** `-applymapping` is advisory for R8 in full mode (it may still
  rename or merge differently), which only makes the dex diff bigger; persisted class names
  (WorkManager rows) are kept by keep rules either way. Stable ids keep manifest-referenced and
  already-posted resource ids valid; resource shrinking can still drop a resource the fix starts to
  use, which then comes in the resource patch. Never build a patch from a base that was not
  archived — without its mapping and id table the diff is meaningless.
- **Native libraries and patch size.** llama.cpp's ggml backends are `dlopen()`ed from the
  installed `nativeLibraryDir`, which Tinker does not patch, so patched `libllama`/`libggml` next to
  unpatched backends could mix ABIs: the scripts refuse those changes. If the guard trips without a
  llama.cpp change, the native build is not reproducible on that machine — ship a full release.
  Tinker bsdiffs libraries; dex patches are usually tens to hundreds of KB, resources more.
- **Start-up cost.** A loaded patch runs from Tinker's own optimized dex; first start after
  applying includes dex2oat in the `:patch` process.
- **Hilt internals.** `HermesComponentFactory` relies on Hilt's generated root name and
  `ApplicationContextModule`; `HermesComponentFactoryTest` fails if a Hilt upgrade changes them.
- **Google Play policy.** Play forbids apps from downloading executable code outside Play.
  Hermes is sideloaded and updated through its own signed OTA channel, so this does not apply;
  it would if Hermes were ever published on Play.
- **Tinker maintenance.** Tinker 1.9.15.x is the last line; Android 14 read-only-dex and
  Android 15 resource fixes are in it, nothing newer is guaranteed.

## Porting to Jeeves

Jeeves shares the package layout and the OTA code:

1. `gradle/libs.versions.toml`: the `tinker` version and the two libraries; `app/build.gradle.kts`:
   the Tinker glue block (TINKER_ID, `hermes.tinker.base`), the dependencies.
2. Copy `app/src/main/java/com/hermes/agent/tinker/loader/HermesTinkerApplication.java`,
   `app/src/main/kotlin/com/hermes/agent/tinker/`, `data/hotfix/`,
   `ui/settings/HotfixSection.kt`, `HotfixViewModel.kt`, and the tests under
   `app/src/test/.../data/hotfix`, `.../tinker` and `resources/hotfix`.
3. Split Jeeves' `HermesApp` the same way: keep it as the bare `@HiltAndroidApp` root, move its
   `onCreate` into a `HermesAppStartup` with the entry point, and initialize WorkManager there.
   Check Jeeves' root name matches `HermesComponentFactory.COMPONENT`.
4. Manifest: the shell as `android:name`, the `TINKER_ID` meta-data, `HermesPatchResultService`.
   `proguard-rules.pro`: the Tinker block.
5. OTA: `OtaUpdateChecker.checkOffer`, `OtaUpdateWorker`, `UpdateUiState.FixAvailable` and the
   Updates section wiring. Jeeves' release signer replaces `99255c31…` in the scripts.
6. `tools/tinker/` and the CI smoke step; a separate archive per app.

## Verification status

- Unit tests (JVM): `PatchManifestTest` (parsing/validation, TINKER_ID matching),
  `PatchGateTest` (SHA-256, size, TINKER_ID, signed/unsigned/foreign/tampered/partially-signed
  fixtures), `OtaDecisionTest` (patch vs full APK vs nothing), `CrashLoopPolicyTest` (crash guard,
  state store), `TinkerWiringTest` (manifest, loader-class rule, config/proguard agreement, Gradle
  gating), `HermesComponentFactoryTest`.
- CI: compile, debug APK, unit tests, and the unsigned patch smoke check.
- **Not verified:** loading a patch on a device (no device was available), resource patching on
  Android 15/16, the release (R8) path end to end, and the PowerShell script on Windows (parsed,
  not run). Do one full dry run — archive a base, install it, build and apply a trivial patch —
  before the first real fix.
