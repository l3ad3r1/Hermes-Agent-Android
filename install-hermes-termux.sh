#!/data/data/com.termux/files/usr/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# Hermes Agent on Android — proot-distro Ubuntu method.
# (Method adapted from mithun50/openclaw-termux: run in a full glibc Ubuntu
# rootfs via proot-distro, instead of native Termux. This avoids the musl/bionic
# wheel-build failures of the native path.)
#
# Run inside Termux:   bash install-hermes-termux.sh     (idempotent)
# ─────────────────────────────────────────────────────────────────────────────
set -eu
say() { printf '\n\033[1;34m▶ %s\033[0m\n' "$*"; }

say "Hermes Agent — Termux install via proot Ubuntu"
termux-wake-lock 2>/dev/null || true

say "Installing proot-distro…"
yes | pkg update 2>/dev/null || pkg update -y || true
pkg install -y proot-distro

ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
if [ ! -d "$ROOTFS" ]; then
  say "Installing Ubuntu rootfs (~500 MB, one time)…"
  proot-distro install ubuntu
else
  say "Ubuntu rootfs already present."
fi

say "Configuring + installing Hermes inside Ubuntu (several minutes)…"
proot-distro login ubuntu --shared-tmp -- bash -lc '
  set -eu
  export DEBIAN_FRONTEND=noninteractive

  # proot-friendly apt/dpkg (prevents sandbox/fsync failures under proot).
  mkdir -p /etc/apt/apt.conf.d /etc/dpkg/dpkg.cfg.d
  printf "APT::Sandbox::User \"root\";\nDpkg::Use-Pty \"0\";\n" > /etc/apt/apt.conf.d/01-hermes-proot
  printf "force-unsafe-io\nforce-overwrite\n" > /etc/dpkg/dpkg.cfg.d/01-hermes-proot

  apt-get update -y
  apt-get install -y --no-install-recommends \
    ca-certificates curl git build-essential pkg-config \
    python3 python3-venv python3-pip python3-dev libffi-dev libssl-dev \
    ripgrep ffmpeg

  # Node.js 22 (NodeSource) for browser/node-backed tools.
  if ! command -v node >/dev/null 2>&1; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y nodejs
  fi

  # Hermes Agent.
  if [ -d "$HOME/hermes-agent/.git" ]; then
    git -C "$HOME/hermes-agent" pull --ff-only || true
  else
    git clone https://github.com/NousResearch/hermes-agent.git "$HOME/hermes-agent"
  fi
  cd "$HOME/hermes-agent"
  python3 -m venv venv
  . venv/bin/activate
  python -m pip install --upgrade pip setuptools wheel
  python -m pip install -e ".[termux]" -c constraints-termux.txt

  mkdir -p "$HOME/.local/bin"
  ln -sf "$PWD/venv/bin/hermes" "$HOME/.local/bin/hermes"
  grep -q ".local/bin" "$HOME/.bashrc" 2>/dev/null || \
    echo "export PATH=\$HOME/.local/bin:\$PATH" >> "$HOME/.bashrc"

  hermes version || true
'

say "Creating a Termux 'hermes' launcher (enters Ubuntu)…"
cat > "$PREFIX/bin/hermes" <<'WRAP'
#!/data/data/com.termux/files/usr/bin/bash
exec proot-distro login ubuntu --shared-tmp -- bash -lc "hermes $*"
WRAP
chmod +x "$PREFIX/bin/hermes"

cat <<'DONE'

✅ Hermes installed inside an Ubuntu (proot) environment.

From Termux:
  hermes model     # configure API key(s)
  hermes           # start the agent
  proot-distro login ubuntu     # drop into the Ubuntu shell directly

Notes:
  • First run downloaded ~500 MB (Ubuntu + Node). Re-running this script updates Hermes.
  • Disable battery optimization for Termux to keep a background gateway alive.
DONE
