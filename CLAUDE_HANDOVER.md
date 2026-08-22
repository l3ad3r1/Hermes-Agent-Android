# Hermes / Jeeves Handover

**Date:** 2026-08-23  
**Audience:** Claude (next maintainer)  
**Scope:** Hermes Android app, private Jeeves Android app, shared `agent-core`, and the public module catalog.

## Executive status

The shared module-download foundation is released and pushed:

| Repository | Remote | Current pushed branch | Release/status |
|---|---|---|---|
| Hermes | [Hermes-Agent-Android](https://github.com/l3ad3r1/Hermes-Agent-Android) | `codex/release-modules` | `v0.9.4` published APK |
| Jeeves | [Jeeves](https://github.com/l3ad3r1/Jeeves) | `port/hermes-0.9.x` | `v0.16.1` published APK |
| Shared engine | [agent-core](https://github.com/l3ad3r1/agent-core) | `codex/release-modules`; `main` published | Shared contracts and implementations |
| Public modules | [hermes-jeeves-modules](https://github.com/l3ad3r1/hermes-jeeves-modules) | `main` | Catalog and authoring guide |

Release links:

- [Hermes v0.9.4](https://github.com/l3ad3r1/Hermes-Agent-Android/releases/tag/v0.9.4)
- [Jeeves v0.16.1](https://github.com/l3ad3r1/Jeeves/releases/tag/v0.16.1)

Both release APKs were built successfully and signature-checked. Hermes was installed and
the Modules settings screen was visually verified on the connected Android device during
the previous handoff.

## Important: work currently in progress

The working trees are **not clean as of this handover**. These changes are present locally
but are not part of the release commits and must be reviewed before committing:

- Hermes has uncommitted Room schema/database changes through version 17, repository
  implementations, Hilt bindings, agent capability changes, persona changes, and tests for
  Notes, Todo, Calendar, Bookmarks, and Mood.
- `agent-core` has the matching domain models/repository interfaces, persistence DAOs/entities,
  tool rewrites, policy changes, and tests.
- `hermes-jeeves-modules` has untracked `modules/` source trees for `notes`, `todo`, `calendar`,
  `bookmarks`, and `mood`, plus README edits.
- Jeeves currently has no corresponding uncommitted productivity-module port.

Do not use `git reset --hard` or bulk cleanup. First inspect the diffs, decide whether the
productivity work is ready, then port and test it in Jeeves before making a coordinated commit.
The release branches and release tags above should remain reproducible.

## What was completed in the release foundation

The shared module-download contract is implemented in `agent-core` and consumed by both apps.
The app-specific UI lives in each app under:

```text
app/src/main/kotlin/com/hermes/agent/ui/settings/ModulesSettingsScreen.kt
app/src/main/kotlin/com/hermes/agent/ui/settings/ModulesSettingsViewModel.kt
```

Navigation is exposed at **Settings → Features → Modules**. The screen lets the user enter a
catalog URL, load the catalog, view module entries, and download a selected artifact. The
download coordinator validates the catalog and APK before saving it to the app-private plugin
staging directory.

The current flow stops at verified staging. It does **not** silently install or execute an
APK. A future installer/approval flow must remain a separate, explicit security decision.

## How the Hermes module system works

The starter catalog is:

`https://raw.githubusercontent.com/l3ad3r1/hermes-jeeves-modules/main/catalog-v1.json`

Catalog schema v1 contains `schemaVersion`, `generatedAtEpochSeconds`, and a `plugins` array.
Each plugin entry carries a manifest and artifact metadata:

- stable reverse-DNS plugin ID (also the Android package ID);
- display name, author, version code/name, capabilities, and permission rationales;
- minimum host version and protocol version;
- exported service class and service contract metadata;
- immutable HTTPS APK URL, exact byte size, SHA-256 digest, and signer fingerprint.

At load/download time Hermes and Jeeves reject invalid or unsafe entries. The checks include
HTTPS URL, schema/version validity, size limits, exact byte count, SHA-256, package identity,
manifest metadata, required exported service, and signing-certificate fingerprint. Transport
integrity (HTTPS/checksum) is not publisher trust: approval must remain bound to the exact
plugin ID, version, digest, signer, and permission list.

### Creating and publishing a module

Use the detailed guide in the public repository:

<https://github.com/l3ad3r1/hermes-jeeves-modules#creating-a-module>

The short sequence is:

1. Build an Android library/application that exposes one safe plugin service using protocol v1.
2. Keep the package/plugin ID stable and add the
   `com.hermes.agent.PLUGIN_MANIFEST_V1` JSON metadata.
3. Declare only required permissions and explain each one to the user.
4. Sign the release APK with a stable publisher key; put its SHA-256 fingerprint in the manifest.
5. Publish the APK at an immutable HTTPS URL and calculate the final size and SHA-256.
6. Add the complete entry to `catalog-v1.json` and publish the catalog from `main`.
7. Test the catalog in both apps from Settings → Features → Modules.

Do not reuse a version path for different APK bytes. Prefer GitHub Releases for large APKs and
keep signing credentials out of Git.

## Build and test procedure

The composite builds use the local sibling `../agent-core` checkout. To avoid stale generated
classes when switching between app builds, clean the shared core first:

```powershell
cd E:\claude-projects\agent-core
.\gradlew.bat clean --no-daemon --max-workers=1

cd 'E:\claude-projects\Hermes Agent Android App'
.\gradlew.bat :app:assembleRelease --no-daemon --max-workers=1

cd E:\claude-projects\jeeves
.\gradlew.bat :app:assembleRelease --no-daemon --max-workers=1
```

Expected APK paths:

```text
Hermes: app/build/outputs/apk/release/app-release.apk
Jeeves: app/build/outputs/apk/release/app-release.apk
```

For a focused test pass, run the app unit tests and Room migration tests, then verify:

1. Settings → Features → Modules is reachable in both apps.
2. The starter empty catalog loads and shows “no modules yet.”
3. A fixture catalog rejects bad HTTPS, digest, size, package, signer, and service metadata.
4. A valid artifact is downloaded to staging but is not auto-installed.
5. Room migrations upgrade existing databases without data loss.

## Known issues / next fixes

1. **Coordinate the productivity port.** The current uncommitted Hermes/core work adds five
   productivity modules and Room migrations 13→17, but Jeeves has not yet received the same
   source and migration set. Port only after reviewing the diffs and keep database schemas,
   tool names, capability grants, and permission behavior identical.
2. **Run both app test suites after the port.** Pay special attention to migration 16→17,
   which creates both Bookmarks and Mood tables, and to Calendar runtime permissions.
3. **Preserve the shared clarification bus.** Jeeves previously had a duplicate local
   `ClarificationBus`; it was removed so both builds use the shared core implementation.
4. **Module installation is intentionally unfinished.** Add an explicit user approval and
   installer handoff only after threat-model review; do not turn verified staging into silent
   installation.
5. **Production signing/certificate pins remain deployment-specific.** Release signing keys
   and production certificate hashes are not committed.
6. **The public catalog is intentionally empty.** Do not add an entry until its immutable APK,
   digest, signer, service class, and permission review are complete.

## Safe handoff rules

- Preserve uncommitted user work and inspect it before editing.
- Keep Hermes public-facing and Jeeves private; share contracts and engine code through
  `agent-core`, not by copying divergent implementations.
- Commit app/core/module changes in logically related, reviewable commits.
- Build Hermes and Jeeves sequentially after cleaning `agent-core`.
- Update READMEs/catalog documentation whenever the module contract changes.
- Never publish API keys, signing keys, or private Jeeves artifacts in the public modules repo.

