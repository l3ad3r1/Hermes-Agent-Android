# Manual Test Checklist — v0.8.1

On-device verification for the v0.8.1 changes. None of this was device-tested
before release, so run at least the **P0** items on a real Android 10+ device.

**Priority:** P0 = must pass before trusting the release · P1 = important · P2 = nice to confirm.

## Setup
- [ ] Device on Android 10+ (API 29+); ideally also test one Android 12+ device (install-permission + edge-to-edge behave differently).
- [ ] Install the **published v0.8.1** APK from the GitHub release (not a local debug build — debug uses a different applicationId `.debug`).
- [ ] Have a GitHub **PAT with `gist` scope** ready for the backup tests.
- [ ] Note: the installed build reports version `0.8.1`.

---

## 1. Top bar & bottom "black bar" (P0)
- [ ] Open **Chat** screen. Top bar is visibly **slim** (~52dp) and title/back/actions are vertically centered, not clipped.
- [ ] Status bar area above the top bar is the surface colour — content does **not** draw under the status bar.
- [ ] Scroll a conversation to the bottom: the input bar sits just above the system nav bar with **no black strip** between them.
- [ ] Behind the gesture/3-button nav bar the colour matches the input bar (surface), not black.
- [ ] Tap the message field → keyboard opens: input bar rises with the keyboard, **no double gap**, no black band appears.
- [ ] Dismiss keyboard: layout returns cleanly.
- [ ] Repeat the top-bar check on the **Search** screen.
- [ ] (P1) Toggle device dark/light theme and a couple of in-app themes (Settings) — no stray light/black bars top or bottom.

## 2. Search tab & persistent search bar (P0)
- [ ] Bottom nav shows **Home · Search · Board · Settings** (the old "Chats" label/icon is gone; Search uses a magnifier icon).
- [ ] Tap **Search**: a search field is **always visible** at the top; below it, recent sessions are listed.
- [ ] Type a query that matches an existing chat → results filter to matches (FTS5).
- [ ] Tap the **✕ / Clear** → field empties and the list returns to recent sessions.
- [ ] Empty query shows recent sessions (browse), not an empty/error state.
- [ ] Tap a result → opens that conversation in Chat.
- [ ] Tap the **+ FAB** → starts a new conversation.
- [ ] (P1) Rotate the device mid-search → query text is preserved.

## 3. Backup & restore — the fresh-install round trip (P0)
Pre-conditions on the **source** install: at least one memory, one custom (non-built-in) skill, a non-default Cloud LLM setting (e.g. a model/base URL), and **one cron job**.

**Backup**
- [ ] Settings → Backup & Restore. Paste the PAT. Tap **Backup now**.
- [ ] Success message appears; the **Gist ID** field auto-fills; "Last backup" timestamp shows.
- [ ] (P1) On github.com the secret gist `hermes-backup.json` exists and its JSON contains `settings`, `memories`, `skills`, and `crons` arrays.

**Restore on a clean install** (the actual bug being verified)
- [ ] Uninstall the app (or use a second device / fresh profile) so settings, DB, and Gist ID are wiped.
- [ ] Reinstall v0.8.1. Confirm memories / skills / crons / cloud settings are all **empty/default**.
- [ ] Settings → Backup & Restore. **Paste the PAT and paste the Gist ID** (from the source device / gist URL). Restore button becomes enabled.
- [ ] Tap **Restore**. Success message reports counts including **settings** and **cron jobs** (e.g. "Restored settings, N memories, M skills, K cron jobs").
- [ ] Verify **Cloud LLM settings** came back (model, base URL, keys, aux provider) — Settings shows them and a cloud request works.
- [ ] Verify **memories** are back (Memory screen).
- [ ] Verify **skills** are back (Skills screen); confirm a malicious/guarded skill would be skipped (built-ins are not duplicated).
- [ ] Verify **cron jobs** are back in the Cron/Schedule screen **and are actually scheduled** — enabled jobs should fire on their interval (check logs or wait for a run), confirming WorkManager re-enqueue.
- [ ] (P1) Run Restore a second time → no duplicate crons (upsert by id), settings unchanged.
- [ ] (P1) Restore with a **wrong/blank Gist ID** → clear error ("No backup found" / GitHub 404), no crash.

## 4. In-app OTA updater (P0)
Requires a release **newer** than what's installed, with an `.apk` asset. To test the flow now, sideload a build reporting a **lower** version (e.g. temporarily set versionName lower) so v0.8.1 shows as "available".
- [ ] Settings → Updates → **Check for updates**.
- [ ] When newer exists: card shows "Hermes X.Y.Z is available!" with a **Download & install** button (not a browser link).
- [ ] If "install unknown apps" is **not** yet granted: an **Allow installs** button appears and opens the system unknown-sources screen for Hermes.
- [ ] Tap **Download & install**: a **progress bar** advances 0→100% inside the app (no browser opens).
- [ ] At 100% the **system package installer** launches showing the new version → confirm install → app updates in place (data preserved, since same signer).
- [ ] Trigger the **update notification** (background check): tapping it **opens the app** (Settings/Updates), not a browser.
- [ ] (P1) Airplane-mode mid-download → graceful error state, "Check for updates" available again; retry works.
- [ ] (P2) A release with **no APK asset** → button falls back to "View release" (browser). (Expected only for odd releases.)

## 5. Regression sanity (P1)
- [ ] Existing v0.8.0 install updates to v0.8.1 **without uninstall** (same signer — confirms no signature break).
- [ ] Send a chat message end-to-end (cloud LLM) — nothing regressed from the top-bar/inset refactor.
- [ ] Voice input, tool-call cards, and the Kanban board open normally.
- [ ] No new crashes in Logcat around startup, Settings, Search, or Cron.

---
_If any P0 fails, capture Logcat (tags: `GithubBackup`, `OtaInstaller`, `OtaChecker`, `CronScheduler`) and file before promoting the release._
