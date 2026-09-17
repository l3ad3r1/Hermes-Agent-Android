#!/usr/bin/env bash
# Builds the embedded Tailscale node into app/libs/tsbridge.aar (gomobile).
# Needs: Go >= 1.26.6, ANDROID_HOME with an NDK, and `go install golang.org/x/mobile/cmd/{gomobile,gobind}@latest`.
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?set ANDROID_HOME}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | tail -1)}"
mkdir -p ../app/libs
gomobile bind -target=android/arm64 -androidapi 29 -o ../app/libs/tsbridge.aar .
ls -la ../app/libs/tsbridge.aar
