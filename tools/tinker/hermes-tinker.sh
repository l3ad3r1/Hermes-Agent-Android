#!/usr/bin/env bash
# Hermes hot-fix (Tinker) patch pipeline — Linux/macOS/Git-Bash and CI. Windows: hermes-tinker.ps1.
# See docs/TINKER-HOTFIX.md for the whole flow and the trust model.
#
#   hermes-tinker.sh archive-base [--variant release|debug] [--archive DIR] [--skip-build] [--allow-dirty] [--unsigned]
#       Build the base (unless --skip-build) and archive exactly what a later patch needs:
#       base.apk, mapping.txt (release), stable-ids.txt, R.txt, tinker-base.properties.
#       Run it for EVERY release you publish: a patch can only target an archived base.
#
#   hermes-tinker.sh build-patch --base DIR --patch-version N [--notes TEXT] [--min-restart-prompt true|false]
#                                [--variant release|debug] [--skip-build] [--allow-dirty] [--allow-native] [--unsigned]
#                                [--gradle-arg ARG]...
#       Build the fix against the base (-Phermes.tinker.base: same version, stable resource ids,
#       R8 -applymapping), diff it with tinker-patch-lib, sign the patch with the release key
#       (SHA-256 JAR signature) and write hermes-patch-<baseTinkerId>-<N>.apk + hermes-patch.json.
#       --unsigned is for CI smoke checks only: no key, no manifest, nothing a phone would accept.
#
#   hermes-tinker.sh publish --patch-dir DIR [--tag vX.Y.Z] [--yes]
#       Upload the signed patch and hermes-patch.json to the base's GitHub release with `gh`.
#
# Environment: HERMES_TINKER_ARCHIVE (default <repo>/tinker-archive), JAVA_HOME (jarsigner/keytool),
# ANDROID_HOME (aapt2 for the resource-id table, apksigner for the release signer check).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TOOLS="$ROOT/tools/tinker"
GRADLEW="$ROOT/gradlew"
CLI="$TOOLS/patch-cli/build/install/hermes-tinker-patch-cli/bin/hermes-tinker-patch-cli"
RELEASE_SIGNER="99255c31ffba1932e4ab2abc12d99b82bf780874b8c686076497157996cf6d6f"

die() { echo "hermes-tinker: $*" >&2; exit 1; }
log() { echo "==> $*" >&2; }

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | awk '{print $1}'; else shasum -a 256 "$1" | awk '{print $1}'; fi; }
filesize() { wc -c <"$1" | tr -d ' '; }
cap() { printf '%s' "${1:0:1}" | tr '[:lower:]' '[:upper:]'; printf '%s' "${1:1}"; }
jbin() { if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/$1" ]; then echo "$JAVA_HOME/bin/$1"; else command -v "$1" || die "$1 not found (set JAVA_HOME)"; fi; }
prop() { grep -E "^$2=" "$1" | head -1 | cut -d= -f2- | sed 's/\\\\/\\/g; s/\\:/:/g'; }

build_cli() {
    log "building the patch CLI (tinker-patch-lib)"
    "$GRADLEW" -p "$TOOLS/patch-cli" --quiet installDist >&2
    [ -x "$CLI" ] || die "patch CLI not built at $CLI"
}

apk_info() { "$CLI" info "$1"; }

git_clean_or_die() {
    [ "${ALLOW_DIRTY:-false}" = true ] && return 0
    local dirty; dirty=$(git -C "$ROOT" status --porcelain --untracked-files=no)
    if [ -n "$dirty" ]; then
        echo "$dirty" >&2
        die "working tree has uncommitted changes; the TINKER_ID (git sha) would not describe the build. Commit first or pass --allow-dirty."
    fi
}

find_apk() { # variant
    local dir="$ROOT/app/build/outputs/apk/$1"
    local apk
    apk=$(ls "$dir"/*.apk 2>/dev/null | grep -v -- '-unaligned' | head -1 || true)
    [ -n "$apk" ] || die "no APK under $dir"
    echo "$apk"
}

signer_of_apk() { # apk -> sha256 of signer (needs apksigner) or empty
    local apksigner=""
    if [ -n "${ANDROID_HOME:-}" ]; then apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true); fi
    [ -n "$apksigner" ] || return 0
    "$apksigner" verify --print-certs "$1" 2>/dev/null | grep -iE 'certificate SHA-256 digest' | awk '{print $NF}' | sort -u | head -1
}

aapt2_bin() {
    local a="" sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -n "$sdk" ]; then a=$(ls -d "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1 || true); fi
    [ -n "$a" ] || a=$(command -v aapt2 || true)
    [ -n "$a" ] || die "aapt2 not found (set ANDROID_HOME to the Android SDK)"
    echo "$a"
}

dexdump_bin() {
    local d="" sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    if [ -n "$sdk" ]; then d=$(ls -d "$sdk"/build-tools/*/dexdump 2>/dev/null | sort -V | tail -1 || true); fi
    [ -n "$d" ] || d=$(command -v dexdump || true)
    [ -n "$d" ] || die "dexdump not found (set ANDROID_HOME to the Android SDK; a release patch needs it)"
    echo "$d"
}

# apk -> the app package's resource ids in aapt2 --stable-ids format ("pkg:type/name = 0x7f......"),
# read from the APK itself so the table is exactly what the phone has installed.
resource_ids() {
    "$(aapt2_bin)" dump resources "$1" | awk '
        /^Package name=/ { split($2, a, "="); pkg = a[2]; next }
        /^[ \t]+resource 0x7f[0-9a-fA-F]+ / { print pkg ":" $3 " = " $2 }' | sort
}

# base ids file, fix ids file -> die if a resource of the base has a different id in the fix
# (i.e. --stable-ids did not take effect); new resources are fine.
check_ids_stable() {
    local moved
    moved=$(awk 'NR == FNR { b[$1] = $3; next } ($1 in b) && b[$1] != $3 { print "  " $1 ": " b[$1] " -> " $3 }' "$1" "$2" | head -20)
    [ -z "$moved" ] || die "resource ids moved between the base and the fix (stable ids not applied):
$moved"
}

# ---------------------------------------------------------------------------------------------
archive_base() {
    local variant=release archive="${HERMES_TINKER_ARCHIVE:-$ROOT/tinker-archive}" skip_build=false unsigned=false
    while [ $# -gt 0 ]; do case "$1" in
        --variant) variant="$2"; shift 2;;
        --archive) archive="$2"; shift 2;;
        --skip-build) skip_build=true; shift;;
        --allow-dirty) ALLOW_DIRTY=true; shift;;
        --unsigned) unsigned=true; shift;;
        *) die "archive-base: unknown option $1";; esac; done
    local V; V=$(cap "$variant")

    git_clean_or_die
    build_cli
    if [ "$skip_build" = false ]; then
        log "building the $variant base"
        "$GRADLEW" ":app:assemble${V}" >&2
    fi

    local apk; apk=$(find_apk "$variant")
    case "$apk" in *-unsigned.apk) [ "$unsigned" = true ] || die "$apk is unsigned; release bases must be the signed APK you publish (set up hermes.local.properties).";; esac
    if [ "$variant" = release ] && [ "$unsigned" = false ]; then
        local signer; signer=$(signer_of_apk "$apk")
        [ -n "$signer" ] || die "cannot check the APK signer (set ANDROID_HOME for apksigner)"
        [ "$signer" = "$RELEASE_SIGNER" ] || die "APK signer $signer is not the release key 99255c31…"
    fi

    local info; info=$(apk_info "$apk")
    local tinker_id version_code version_name package
    tinker_id=$(echo "$info" | sed -n 's/^tinkerId=//p'); version_code=$(echo "$info" | sed -n 's/^versionCode=//p')
    version_name=$(echo "$info" | sed -n 's/^versionName=//p'); package=$(echo "$info" | sed -n 's/^packageName=//p')
    [ -n "$tinker_id" ] && [ "$tinker_id" != null ] || die "the APK has no TINKER_ID meta-data"

    local dir="$archive/$tinker_id"
    [ ! -e "$dir" ] || die "$dir already exists; an archived base is never overwritten"
    mkdir -p "$dir"
    cp "$apk" "$dir/base.apk"

    resource_ids "$dir/base.apk" >"$dir/stable-ids.txt"
    [ -s "$dir/stable-ids.txt" ] || die "could not read resource ids from $apk (aapt2 dump resources)"

    local mapping="$ROOT/app/build/outputs/mapping/$variant/mapping.txt"
    if [ -f "$mapping" ]; then cp "$mapping" "$dir/mapping.txt"
    elif [ "$variant" = release ]; then die "missing $mapping; a release base must archive R8's mapping"; fi

    local rtxt; rtxt=$(find "$ROOT/app/build/intermediates" -path "*$variant*" -name R.txt 2>/dev/null | head -1 || true)
    [ -n "$rtxt" ] && cp "$rtxt" "$dir/R.txt"

    {
        echo "# Archived Tinker base for hot-fix patches. Do not edit."
        echo "tinkerId=$tinker_id"
        echo "versionCode=$version_code"
        echo "versionName=$version_name"
        echo "packageName=$package"
        echo "variant=$variant"
        echo "gitSha=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
        echo "apkSha256=$(sha256 "$dir/base.apk")"
        echo "archivedAt=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } >"$dir/tinker-base.properties"
    (cd "$dir" && for f in *; do [ -f "$f" ] && echo "$(sha256 "$f")  $f"; done) >"$dir/SHA256SUMS" || true

    log "archived base $tinker_id ($version_name, $variant) in $dir"
    echo "$dir"
}

# ---------------------------------------------------------------------------------------------
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e 's/\t/\\t/g' -e 's/\r//g' | awk 'BEGIN{ORS=""} NR>1{print "\\n"} {print}'; }

build_patch() {
    local base="" patch_version="" notes="" restart_prompt=true variant="" skip_build=false allow_native=false unsigned=false out=""
    local -a gradle_args=()
    while [ $# -gt 0 ]; do case "$1" in
        --base) base="$2"; shift 2;;
        --patch-version) patch_version="$2"; shift 2;;
        --notes) notes="$2"; shift 2;;
        --min-restart-prompt) restart_prompt="$2"; shift 2;;
        --variant) variant="$2"; shift 2;;
        --out) out="$2"; shift 2;;
        --skip-build) skip_build=true; shift;;
        --allow-dirty) ALLOW_DIRTY=true; shift;;
        --allow-native) allow_native=true; shift;;
        --unsigned) unsigned=true; shift;;
        --gradle-arg) gradle_args+=("$2"); shift 2;;
        *) die "build-patch: unknown option $1";; esac; done
    [ -n "$base" ] || die "--base is required"
    base=$(cd "$base" && pwd)
    local props="$base/tinker-base.properties"
    [ -f "$props" ] || die "$base is not an archived base"
    [[ "$patch_version" =~ ^[1-9][0-9]*$ ]] || die "--patch-version must be a positive integer"
    case "$restart_prompt" in true|false) ;; *) die "--min-restart-prompt must be true or false";; esac
    [ ${#notes} -le 2000 ] || die "--notes is longer than 2000 characters"

    local base_id base_vc base_vn base_pkg base_variant
    base_id=$(prop "$props" tinkerId); base_vc=$(prop "$props" versionCode); base_vn=$(prop "$props" versionName)
    base_pkg=$(prop "$props" packageName); base_variant=$(prop "$props" variant)
    variant="${variant:-$base_variant}"
    [ "$variant" = "$base_variant" ] || die "base is a $base_variant build; build the patch from the same variant"
    [ "$(sha256 "$base/base.apk")" = "$(prop "$props" apkSha256)" ] || die "base.apk no longer matches its recorded SHA-256"
    local V; V=$(cap "$variant")

    git_clean_or_die
    local base_sha; base_sha=$(prop "$props" gitSha)
    if git -C "$ROOT" cat-file -e "$base_sha^{commit}" 2>/dev/null && ! git -C "$ROOT" merge-base --is-ancestor "$base_sha" HEAD; then
        log "WARNING: the base commit $base_sha is not an ancestor of HEAD; is this the right branch?"
    fi

    build_cli
    if [ "$skip_build" = false ]; then
        log "building the fix against $base_id"
        "$GRADLEW" "-Phermes.tinker.base=$base" ${gradle_args[@]+"${gradle_args[@]}"} ":app:assemble${V}" >&2
    fi
    local new_apk; new_apk=$(find_apk "$variant")
    local info; info=$(apk_info "$new_apk")
    local new_id; new_id=$(echo "$info" | sed -n 's/^tinkerId=//p')
    local new_vc new_vn
    new_vc=$(echo "$info" | sed -n 's/^versionCode=//p'); new_vn=$(echo "$info" | sed -n 's/^versionName=//p')
    [ "$new_vc" = "$base_vc" ] || die "fix build versionCode $new_vc differs from the base's $base_vc (was it built with -Phermes.tinker.base?)"
    [ "$new_vn" = "$base_vn" ] || die "fix build versionName '$new_vn' differs from the base's '$base_vn'"
    [ "$(echo "$info" | sed -n 's/^packageName=//p')" = "$base_pkg" ] || die "fix build package differs from the base"
    [ "$new_id" != "$base_id" ] || die "fix build has the base's TINKER_ID; commit the fix first"
    local fix_ids; fix_ids=$(mktemp)
    resource_ids "$new_apk" >"$fix_ids"
    check_ids_stable "$base/stable-ids.txt" "$fix_ids"
    rm -f "$fix_ids"

    out="${out:-$base/patches/$patch_version}"
    [ ! -e "$out" ] || die "$out already exists"
    mkdir -p "$out"

    # Native libraries: llama.cpp's ggml backends are dlopen()ed from the installed nativeLibraryDir,
    # which Tinker does not patch, so a llama.cpp/ggml change must ship as a full release.
    local changed_libs=""
    for lib in $(unzip -Z1 "$new_apk" 'lib/*' 2>/dev/null | grep '\.so$' || true); do
        local a b
        a=$(unzip -p "$base/base.apk" "$lib" 2>/dev/null | sha256sum 2>/dev/null | awk '{print $1}' || true)
        b=$(unzip -p "$new_apk" "$lib" | sha256sum | awk '{print $1}')
        [ "$a" = "$b" ] || changed_libs="$changed_libs $lib"
    done
    if [ -n "$changed_libs" ]; then
        log "native libraries changed:$changed_libs"
        if echo "$changed_libs" | grep -Eq 'lib(ggml|llama|mtmd)[^/]*\.so'; then
            [ "$allow_native" = true ] || die "llama.cpp/ggml libraries changed; ship a full release (or --allow-native if you have verified the ggml ABI is unchanged)."
        fi
    fi

    sed "s/@PATCH_VERSION@/$patch_version/" "$TOOLS/tinker_config.xml" >"$out/tinker_config.xml"
    cp "$new_apk" "$out/fix.apk"
    log "diffing with tinker-patch-lib"
    # A release (R8) build cannot pass tinker-patch-lib's own loader-class and manifest checks, so the CLI
    # runs R8-aware ones given both mappings and dexdump (docs/TINKER-HOTFIX.md, "R8 and loader classes").
    local r8_args=()
    if [ -f "$base/mapping.txt" ]; then
        local new_mapping="$ROOT/app/build/outputs/mapping/$variant/mapping.txt"
        [ -f "$new_mapping" ] || die "missing $new_mapping (the fix build's R8 mapping)"
        r8_args=(--old-mapping "$base/mapping.txt" --new-mapping "$new_mapping" --dexdump "$(dexdump_bin)")
    fi
    "$CLI" patch --old "$base/base.apk" --new "$out/fix.apk" --config "$out/tinker_config.xml" --out "$out/tinker-out" ${r8_args[@]+"${r8_args[@]}"} >&2
    local unsigned_patch="$out/tinker-out/patch_unsigned.apk"
    [ -s "$unsigned_patch" ] || die "tinker-patch-lib produced no patch (no changes?) — see $out/tinker-out/log.txt"
    unzip -l "$unsigned_patch" | grep -q 'assets/package_meta.txt' || die "patch has no package_meta.txt"

    local asset="hermes-patch-$base_id-$patch_version.apk"
    if [ "$unsigned" = true ]; then
        cp "$unsigned_patch" "$out/${asset%.apk}-unsigned.apk"
        log "UNSIGNED smoke patch: $out/${asset%.apk}-unsigned.apk ($(filesize "$unsigned_patch") bytes). Not publishable."
        echo "$out"
        return 0
    fi

    local lp="$ROOT/hermes.local.properties"
    [ -f "$lp" ] || die "missing $lp (release signing config)"
    local store alias
    store=$(prop "$lp" hermes.signing.storeFile); alias=$(prop "$lp" hermes.signing.keyAlias)
    [ -f "$store" ] || die "keystore $store not found"
    HERMES_TINKER_STOREPASS=$(prop "$lp" hermes.signing.storePassword) \
    HERMES_TINKER_KEYPASS=$(prop "$lp" hermes.signing.keyPassword) \
        "$(jbin jarsigner)" -keystore "$store" -storepass:env HERMES_TINKER_STOREPASS -keypass:env HERMES_TINKER_KEYPASS \
        -digestalg SHA-256 -signedjar "$out/$asset" "$unsigned_patch" "$alias" >/dev/null
    "$(jbin jarsigner)" -verify "$out/$asset" >/dev/null || die "signed patch does not verify"
    local signer
    signer=$("$(jbin keytool)" -printcert -jarfile "$out/$asset" | grep -E 'SHA256:' | head -1 | sed 's/.*SHA256: *//; s/://g' | tr '[:upper:]' '[:lower:]')
    if [ "$variant" = release ] && [ "$signer" != "$RELEASE_SIGNER" ]; then
        die "patch signer $signer is not the release key 99255c31…"
    fi

    local sha size
    sha=$(sha256 "$out/$asset"); size=$(filesize "$out/$asset")
    cat >"$out/hermes-patch.json" <<EOF
{
  "schema": 1,
  "baseTinkerId": "$base_id",
  "baseVersionName": "$(json_escape "$base_vn")",
  "newTinkerId": "$new_id",
  "patchVersion": $patch_version,
  "asset": "$asset",
  "sha256": "$sha",
  "size": $size,
  "notes": "$(json_escape "$notes")",
  "minRestartPrompt": $restart_prompt
}
EOF
    log "patch #$patch_version for $base_id: $out/$asset ($size bytes, sha256 $sha)"
    log "next: $0 publish --patch-dir $out"
    echo "$out"
}

# ---------------------------------------------------------------------------------------------
publish() {
    local dir="" tag="" yes=false
    while [ $# -gt 0 ]; do case "$1" in
        --patch-dir) dir="$2"; shift 2;;
        --tag) tag="$2"; shift 2;;
        --yes) yes=true; shift;;
        *) die "publish: unknown option $1";; esac; done
    [ -f "$dir/hermes-patch.json" ] || die "$dir has no hermes-patch.json (unsigned patches cannot be published)"
    command -v gh >/dev/null || die "gh (GitHub CLI) not found"
    local json="$dir/hermes-patch.json"
    local asset sha version base_vn
    asset=$(sed -n 's/.*"asset": "\([^"]*\)".*/\1/p' "$json"); sha=$(sed -n 's/.*"sha256": "\([^"]*\)".*/\1/p' "$json")
    version=$(sed -n 's/.*"patchVersion": \([0-9]*\).*/\1/p' "$json"); base_vn=$(sed -n 's/.*"baseVersionName": "\([^"]*\)".*/\1/p' "$json")
    [ -f "$dir/$asset" ] || die "missing $dir/$asset"
    [ "$(sha256 "$dir/$asset")" = "$sha" ] || die "$asset does not match the manifest's SHA-256"
    tag="${tag:-v${base_vn%%-*}}"
    gh release view "$tag" >/dev/null 2>&1 || die "release $tag not found; patches are attached to the base's own release"

    local existing
    existing=$(gh release download "$tag" -p hermes-patch.json -O - 2>/dev/null | sed -n 's/.*"patchVersion": *\([0-9]*\).*/\1/p' || true)
    if [ -n "$existing" ] && [ "$existing" -ge "$version" ]; then
        die "release $tag already carries patch #$existing; patch versions must increase"
    fi
    if [ "$yes" = false ]; then
        read -r -p "Publish fix #$version ($asset) to release $tag? [y/N] " answer
        [ "$answer" = y ] || [ "$answer" = Y ] || die "aborted"
    fi
    gh release upload "$tag" "$dir/$asset" "$json" --clobber
    log "published fix #$version on $tag; phones on that build see \"Apply fix\" at their next update check."
}

cmd="${1:-}"; [ $# -gt 0 ] && shift
case "$cmd" in
    archive-base) archive_base "$@";;
    build-patch) build_patch "$@";;
    publish) publish "$@";;
    *) sed -n '2,22p' "$0"; exit 2;;
esac
