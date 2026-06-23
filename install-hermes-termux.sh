#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Hermes Agent — Termux installer (native, no proot / no glibc shim).
# Installs NousResearch/Hermes-Agent into ~/hermes-agent on Android/Termux using
# Termux's own python/node/rust, then links the `hermes` command onto PATH.
#
# Run inside Termux:   bash install-hermes-termux.sh
# Idempotent: safe to re-run to update.
# ─────────────────────────────────────────────────────────────────────────────
set -eu

say() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }

say "Hermes Agent — Termux install (native)"
termux-wake-lock 2>/dev/null || true

say "Installing system packages…"
yes | pkg update 2>/dev/null || pkg update -y || true
pkg install -y git python clang rust make pkg-config libffi openssl nodejs ripgrep ffmpeg

HERMES_DIR="$HOME/hermes-agent"
if [ -d "$HERMES_DIR/.git" ]; then
  say "Updating existing checkout…"
  git -C "$HERMES_DIR" pull --ff-only || true
else
  say "Cloning Hermes-Agent…"
  git clone https://github.com/NousResearch/hermes-agent.git "$HERMES_DIR"
fi
cd "$HERMES_DIR"

say "Creating Python venv…"
python -m venv venv
# shellcheck disable=SC1091
source venv/bin/activate

# Critical for Rust-backed wheels (e.g. jiter) on Android.
export ANDROID_API_LEVEL="$(getprop ro.build.version.sdk)"

say "Installing Python deps (.[termux]) — this can take several minutes…"
python -m pip install --upgrade pip setuptools wheel
# pip (NOT uv) on Android; constraints avoid Android-incompatible deps.
python -m pip install -e '.[termux]' -c constraints-termux.txt

say "Linking the 'hermes' command onto PATH…"
ln -sf "$PWD/venv/bin/hermes" "$PREFIX/bin/hermes"

say "Verifying…"
hermes version || true
hermes doctor || true

cat <<'DONE'

✅ Hermes Agent installed.

Next steps (in Termux):
  hermes model     # configure your API key(s)  (or edit ~/.hermes/.env)
  hermes           # start the agent

Notes:
  • Browser automation, faster-whisper voice, and Docker are not supported on Termux.
  • Run `termux-wake-lock` to help keep a background gateway alive.
DONE
