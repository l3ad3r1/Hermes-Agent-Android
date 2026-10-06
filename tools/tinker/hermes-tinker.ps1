<#
.SYNOPSIS
  Hermes hot-fix (Tinker) patch pipeline for the Windows release PC. Same commands as hermes-tinker.sh.
  See docs/TINKER-HOTFIX.md.

.EXAMPLE
  # Every release: build + archive the signed base (run from the repo root).
  .\tools\tinker\hermes-tinker.ps1 archive-base

.EXAMPLE
  # After the fix is merged: build fix #1 against that base, sign it, write hermes-patch.json.
  .\tools\tinker\hermes-tinker.ps1 build-patch -Base tinker-archive\hermes-90-1a2b3c4d5e6f -PatchVersion 1 -Notes "Fixes the calendar crash"

.EXAMPLE
  .\tools\tinker\hermes-tinker.ps1 publish -PatchDir tinker-archive\hermes-90-1a2b3c4d5e6f\patches\1
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0, Mandatory = $true)][ValidateSet('archive-base', 'build-patch', 'publish')][string]$Command,
    [ValidateSet('release', 'debug')][string]$Variant,
    [string]$Archive = $(if ($env:HERMES_TINKER_ARCHIVE) { $env:HERMES_TINKER_ARCHIVE } else { '' }),
    [string]$Base,
    [int]$PatchVersion,
    [string]$Notes = '',
    [bool]$MinRestartPrompt = $true,
    [string]$Out,
    [string]$PatchDir,
    [string]$Tag,
    [string[]]$GradleArg = @(),
    [switch]$SkipBuild,
    [switch]$AllowDirty,
    [switch]$AllowNative,
    [switch]$Unsigned,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Tools = Join-Path $Root 'tools\tinker'
$Gradlew = Join-Path $Root 'gradlew.bat'
$Cli = Join-Path $Tools 'patch-cli\build\install\hermes-tinker-patch-cli\bin\hermes-tinker-patch-cli.bat'
$ReleaseSigner = '99255c31ffba1932e4ab2abc12d99b82bf780874b8c686076497157996cf6d6f'
if (-not $Archive) { $Archive = Join-Path $Root 'tinker-archive' }

function Die([string]$msg) { throw "hermes-tinker: $msg" }
function Log([string]$msg) { Write-Host "==> $msg" }
function Sha256([string]$path) { (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant() }
function Cap([string]$s) { $s.Substring(0, 1).ToUpperInvariant() + $s.Substring(1) }
# Tool output goes to the host, never the pipeline: Archive-Base/Build-Patch return only their
# directory, and anything a native command printed would otherwise become part of that value.
function Invoke-Checked([string]$exe, [string[]]$argv) {
    # When the caller redirects stderr (`*> build.log`, `2>&1`, a CI step or tool that captures
    # it), Windows PowerShell 5.1 turns every stderr line of a native command into a terminating
    # error under 'Stop' -- Gradle's SDK-version warning, tinker-patch-lib's progress output --
    # even when it succeeds. The exit code is the verdict here, so run it under Quiet.
    Quiet { & $exe @argv 2>&1 | ForEach-Object { "$_" } | Out-Host }
    if ($LASTEXITCODE -ne 0) { Die "$([IO.Path]::GetFileName($exe)) failed (exit $LASTEXITCODE)" }
}
function JavaBin([string]$name) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\$name.exe"))) { return (Join-Path $env:JAVA_HOME "bin\$name.exe") }
    $c = Get-Command $name -ErrorAction SilentlyContinue
    if (-not $c) { Die "$name not found (set JAVA_HOME to Android Studio's jbr)" }
    return $c.Source
}
function Read-Props([string]$path) {
    $h = @{}
    foreach ($line in Get-Content -LiteralPath $path) {
        if ($line -match '^\s*#' -or $line -notmatch '=') { continue }
        $k, $v = $line -split '=', 2
        $h[$k.Trim()] = $v.Trim().Replace('\\', '\').Replace('\:', ':')
    }
    return $h
}
function Build-Cli {
    Log 'building the patch CLI (tinker-patch-lib)'
    Invoke-Checked $Gradlew @('-p', (Join-Path $Tools 'patch-cli'), '--quiet', 'installDist')
    if (-not (Test-Path $Cli)) { Die "patch CLI not built at $Cli" }
}
function Apk-Info([string]$apk) {
    $lines = Quiet { & $Cli info $apk 2>$null }
    if ($LASTEXITCODE -ne 0) { Die "cannot read $apk" }
    $h = @{}
    foreach ($l in $lines) { $k, $v = $l -split '=', 2; $h[$k] = $v }
    return $h
}
function Assert-Clean {
    if ($AllowDirty) { return }
    $st = git -C $Root status --porcelain --untracked-files=no
    if ($st) { $st | ForEach-Object { [Console]::Error.WriteLine($_) }; Die'working tree has uncommitted changes; the TINKER_ID (git sha) would not describe the build. Commit first or pass -AllowDirty.' }
}
function Find-Apk([string]$variant) {
    $apk = Get-ChildItem (Join-Path $Root "app\build\outputs\apk\$variant") -Filter *.apk -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $apk) { Die "no APK under app\build\outputs\apk\$variant" }
    return $apk.FullName
}
function Apk-Signer([string]$apk) {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { return $null }
    $apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } |
        ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } | Where-Object { Test-Path $_ } | Select-Object -Last 1
    if (-not $apksigner) { return $null }
    $out = Quiet { & $apksigner verify --print-certs $apk 2>$null }
    $d = $out | Where-Object { $_ -match 'certificate SHA-256 digest' } | ForEach-Object { ($_ -split '\s+')[-1] } | Sort-Object -Unique
    return ($d | Select-Object -First 1)
}
function Aapt2 {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { Die 'aapt2 not found (set ANDROID_HOME to the Android SDK)' }
    $a = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } |
        ForEach-Object { Join-Path $_.FullName 'aapt2.exe' } | Where-Object { Test-Path $_ } | Select-Object -Last 1
    if (-not $a) { Die 'aapt2 not found under ANDROID_HOME\build-tools' }
    return $a
}
function Dexdump {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if (-not $sdk) { Die 'dexdump not found (set ANDROID_HOME to the Android SDK; a release patch needs it)' }
    $d = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } |
        ForEach-Object { Join-Path $_.FullName 'dexdump.exe' } | Where-Object { Test-Path $_ } | Select-Object -Last 1
    if (-not $d) { Die 'dexdump not found under ANDROID_HOME\build-tools' }
    return $d
}
# The app package's resource ids in aapt2 --stable-ids format, read from the APK itself.
function Resource-Ids([string]$apk) {
    $lines = Quiet { & (Aapt2) dump resources $apk 2>$null }
    if ($LASTEXITCODE -ne 0) { Die "aapt2 dump resources failed for $apk" }
    $pkg = $null
    $ids = foreach ($l in $lines) {
        if ($l -match '^Package name=(\S+)') { $pkg = $Matches[1]; continue }
        if ($l -match '^\s+resource (0x7f[0-9a-fA-F]+) (\S+)') { "${pkg}:$($Matches[2]) = $($Matches[1])" }
    }
    return ($ids | Sort-Object)
}
# Dies if a resource of the base has a different id in the fix (--stable-ids did not take effect).
function Assert-IdsStable([string[]]$baseIds, [string[]]$fixIds) {
    $b = @{}
    foreach ($l in $baseIds) { $k, $v = $l -split ' = ', 2; $b[$k] = $v }
    $moved = foreach ($l in $fixIds) { $k, $v = $l -split ' = ', 2; if ($b.ContainsKey($k) -and $b[$k] -ne $v) { "  ${k}: $($b[$k]) -> $v" } }
    if ($moved) { Die ("resource ids moved between the base and the fix (stable ids not applied):`n" + (($moved | Select-Object -First 20) -join "`n")) }
}
function Entry-Hashes([string]$zipPath, [string]$prefix) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $h = @{}
    $zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        foreach ($e in $zip.Entries) {
            if (-not $e.FullName.StartsWith($prefix) -or -not $e.FullName.EndsWith('.so')) { continue }
            $s = $e.Open()
            try {
                $sha = [Security.Cryptography.SHA256]::Create()
                $h[$e.FullName] = ([BitConverter]::ToString($sha.ComputeHash($s)) -replace '-', '').ToLowerInvariant()
            } finally { $s.Dispose() }
        }
    } finally { $zip.Dispose() }
    return $h
}
# Native commands whose stderr/exit code we inspect ourselves: Windows PowerShell 5.1 turns their
# stderr into terminating errors under $ErrorActionPreference = 'Stop'.
function Quiet([scriptblock]$block) {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $block } finally { $ErrorActionPreference = $saved }
}
function Json-String([string]$s) {
    return '"' + ($s.Replace('\', '\\').Replace('"', '\"').Replace("`r", '').Replace("`n", '\n').Replace("`t", '\t')) + '"'
}

# ---------------------------------------------------------------------------------------------
function Archive-Base {
    $variant = if ($Variant) { $Variant } else { 'release' }
    $V = Cap $variant
    Assert-Clean
    Build-Cli
    if (-not $SkipBuild) {
        Log "building the $variant base"
        Invoke-Checked $Gradlew @(":app:assemble${V}")
    }
    $apk = Find-Apk $variant
    if ($apk -like '*-unsigned.apk' -and -not $Unsigned) { Die "$apk is unsigned; release bases must be the signed APK you publish (hermes.local.properties)." }
    if ($variant -eq 'release' -and -not $Unsigned) {
        $signer = Apk-Signer $apk
        if (-not $signer) { Die 'cannot check the APK signer (set ANDROID_HOME for apksigner)' }
        if ($signer -ne $ReleaseSigner) { Die "APK signer $signer is not the release key 99255c31..." }
    }
    $info = Apk-Info $apk
    $tid = $info['tinkerId']
    if (-not $tid -or $tid -eq 'null') { Die 'the APK has no TINKER_ID meta-data' }

    $dir = Join-Path $Archive $tid
    if (Test-Path $dir) { Die "$dir already exists; an archived base is never overwritten" }
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Copy-Item $apk (Join-Path $dir 'base.apk')

    $ids = Resource-Ids (Join-Path $dir 'base.apk')
    if (-not $ids) { Die "could not read resource ids from $apk (aapt2 dump resources)" }
    $ids | Set-Content -Encoding ASCII -LiteralPath (Join-Path $dir 'stable-ids.txt')

    $mapping = Join-Path $Root "app\build\outputs\mapping\$variant\mapping.txt"
    if (Test-Path $mapping) { Copy-Item $mapping (Join-Path $dir 'mapping.txt') }
    elseif ($variant -eq 'release') { Die "missing $mapping; a release base must archive R8's mapping" }

    $rtxt = Get-ChildItem (Join-Path $Root 'app\build\intermediates') -Recurse -Filter R.txt -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like "*$variant*" } | Select-Object -First 1
    if ($rtxt) { Copy-Item $rtxt.FullName (Join-Path $dir 'R.txt') }

    $sha = git -C $Root rev-parse HEAD
    @(
        '# Archived Tinker base for hot-fix patches. Do not edit.'
        "tinkerId=$tid"
        "versionCode=$($info['versionCode'])"
        "versionName=$($info['versionName'])"
        "packageName=$($info['packageName'])"
        "variant=$variant"
        "gitSha=$sha"
        "apkSha256=$(Sha256 (Join-Path $dir 'base.apk'))"
        "archivedAt=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))"
    ) | Set-Content -Encoding ASCII -LiteralPath (Join-Path $dir 'tinker-base.properties')
    Get-ChildItem $dir -File | ForEach-Object { "$(Sha256 $_.FullName)  $($_.Name)" } | Set-Content -Encoding ASCII (Join-Path $dir 'SHA256SUMS')
    Log "archived base $tid ($($info['versionName']), $variant) in $dir"
    return $dir
}

# ---------------------------------------------------------------------------------------------
function Build-Patch {
    if (-not $Base) { Die '-Base is required' }
    $basePath = (Resolve-Path $Base).Path
    $propsPath = Join-Path $basePath 'tinker-base.properties'
    if (-not (Test-Path $propsPath)) { Die "$basePath is not an archived base" }
    if ($PatchVersion -lt 1) { Die '-PatchVersion must be a positive integer' }
    if ($Notes.Length -gt 2000) { Die '-Notes is longer than 2000 characters' }
    $p = Read-Props $propsPath
    $variant = if ($Variant) { $Variant } else { $p['variant'] }
    if ($variant -ne $p['variant']) { Die "base is a $($p['variant']) build; build the patch from the same variant" }
    if ((Sha256 (Join-Path $basePath 'base.apk')) -ne $p['apkSha256']) { Die 'base.apk no longer matches its recorded SHA-256' }
    $V = Cap $variant

    Assert-Clean
    Quiet { git -C $Root merge-base --is-ancestor $p['gitSha'] HEAD 2>$null }
    if ($LASTEXITCODE -eq 1) { Log "WARNING: the base commit $($p['gitSha']) is not an ancestor of HEAD; is this the right branch?" }

    Build-Cli
    if (-not $SkipBuild) {
        Log "building the fix against $($p['tinkerId'])"
        Invoke-Checked $Gradlew (@("-Phermes.tinker.base=$basePath") + $GradleArg + @(":app:assemble${V}"))
    }
    $newApk = Find-Apk $variant
    $info = Apk-Info $newApk
    if ($info['versionCode'] -ne $p['versionCode']) { Die "fix build versionCode $($info['versionCode']) differs from the base's $($p['versionCode']) (was it built with -Phermes.tinker.base?)" }
    if ($info['versionName'] -ne $p['versionName']) { Die "fix build versionName '$($info['versionName'])' differs from the base's '$($p['versionName'])'" }
    if ($info['packageName'] -ne $p['packageName']) { Die 'fix build package differs from the base' }
    if ($info['tinkerId'] -eq $p['tinkerId']) { Die "fix build has the base's TINKER_ID; commit the fix first" }
    Assert-IdsStable (Get-Content -LiteralPath (Join-Path $basePath 'stable-ids.txt')) (Resource-Ids $newApk)

    $outDir = if ($Out) { $Out } else { Join-Path $basePath "patches\$PatchVersion" }
    if (Test-Path $outDir) { Die "$outDir already exists" }
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    # llama.cpp's ggml backends are dlopen()ed from the installed nativeLibraryDir, which Tinker does
    # not patch: a llama.cpp/ggml change must ship as a full release.
    $old = Entry-Hashes (Join-Path $basePath 'base.apk') 'lib/'
    $new = Entry-Hashes $newApk 'lib/'
    $changed = @($new.Keys | Where-Object { $old[$_] -ne $new[$_] })
    if ($changed.Count -gt 0) {
        Log "native libraries changed: $($changed -join ', ')"
        if (($changed | Where-Object { $_ -match 'lib(ggml|llama|mtmd)[^/]*\.so$' }) -and -not $AllowNative) {
            Die 'llama.cpp/ggml libraries changed; ship a full release (or -AllowNative if you have verified the ggml ABI is unchanged).'
        }
    }

    (Get-Content -Raw (Join-Path $Tools 'tinker_config.xml')).Replace('@PATCH_VERSION@', "$PatchVersion") |
        Set-Content -Encoding UTF8 (Join-Path $outDir 'tinker_config.xml')
    Copy-Item $newApk (Join-Path $outDir 'fix.apk')
    Log 'diffing with tinker-patch-lib'
    # A release (R8) build cannot pass tinker-patch-lib's own loader-class and manifest checks, so the CLI
    # runs R8-aware ones given both mappings and dexdump (docs/TINKER-HOTFIX.md, "R8 and loader classes").
    $r8 = @()
    $baseMapping = Join-Path $basePath 'mapping.txt'
    if (Test-Path $baseMapping) {
        $newMapping = Join-Path $Root "app\build\outputs\mapping\$variant\mapping.txt"
        if (-not (Test-Path $newMapping)) { Die "missing $newMapping (the fix build's R8 mapping)" }
        $r8 = @('--old-mapping', $baseMapping, '--new-mapping', $newMapping, '--dexdump', (Dexdump))
    }
    Invoke-Checked $Cli (@('patch', '--old', (Join-Path $basePath 'base.apk'), '--new', (Join-Path $outDir 'fix.apk'),
        '--config', (Join-Path $outDir 'tinker_config.xml'), '--out', (Join-Path $outDir 'tinker-out')) + $r8)
    $unsignedPatch = Join-Path $outDir 'tinker-out\patch_unsigned.apk'
    if (-not (Test-Path $unsignedPatch)) { Die "tinker-patch-lib produced no patch (no changes?) - see $outDir\tinker-out\log.txt" }

    $baseId = $p['tinkerId']
    $asset = "hermes-patch-$baseId-$PatchVersion.apk"
    if ($Unsigned) {
        $u = Join-Path $outDir ($asset -replace '\.apk$', '-unsigned.apk')
        Copy-Item $unsignedPatch $u
        Log "UNSIGNED smoke patch: $u. Not publishable."
        return $outDir
    }

    $lp = Join-Path $Root 'hermes.local.properties'
    if (-not (Test-Path $lp)) { Die "missing $lp (release signing config)" }
    $s = Read-Props $lp
    $store = $s['hermes.signing.storeFile']
    if (-not (Test-Path $store)) { Die "keystore $store not found" }
    $signed = Join-Path $outDir $asset
    # Passwords go through the environment, not the command line.
    $env:HERMES_TINKER_STOREPASS = $s['hermes.signing.storePassword']
    $env:HERMES_TINKER_KEYPASS = $s['hermes.signing.keyPassword']
    try {
        Invoke-Checked (JavaBin 'jarsigner') @('-keystore', $store, '-storepass:env', 'HERMES_TINKER_STOREPASS', '-keypass:env', 'HERMES_TINKER_KEYPASS',
            '-digestalg', 'SHA-256', '-signedjar', $signed, $unsignedPatch, $s['hermes.signing.keyAlias'])
    } finally {
        Remove-Item Env:HERMES_TINKER_STOREPASS, Env:HERMES_TINKER_KEYPASS -ErrorAction SilentlyContinue
    }
    Invoke-Checked (JavaBin 'jarsigner') @('-verify', $signed) | Out-Null
    $certLine = Quiet { & (JavaBin 'keytool') -printcert -jarfile $signed 2>$null } | Where-Object { $_ -match 'SHA256:' } | Select-Object -First 1
    $signer = (($certLine -replace '.*SHA256:\s*', '') -replace ':', '').ToLowerInvariant()
    if ($variant -eq 'release' -and $signer -ne $ReleaseSigner) { Die "patch signer $signer is not the release key 99255c31..." }

    $sha = Sha256 $signed
    $size = (Get-Item $signed).Length
    $json = @"
{
  "schema": 1,
  "baseTinkerId": "$baseId",
  "baseVersionName": $(Json-String $p['versionName']),
  "newTinkerId": "$($info['tinkerId'])",
  "patchVersion": $PatchVersion,
  "asset": "$asset",
  "sha256": "$sha",
  "size": $size,
  "notes": $(Json-String $Notes),
  "minRestartPrompt": $($MinRestartPrompt.ToString().ToLowerInvariant())
}
"@
    [IO.File]::WriteAllText((Join-Path $outDir 'hermes-patch.json'), $json, (New-Object Text.UTF8Encoding($false)))
    Log "patch #$PatchVersion for ${baseId}: $signed ($size bytes, sha256 $sha)"
    Log "next: .\tools\tinker\hermes-tinker.ps1 publish -PatchDir $outDir"
    return $outDir
}

# ---------------------------------------------------------------------------------------------
function Publish-Patch {
    if (-not $PatchDir) { Die '-PatchDir is required' }
    $jsonPath = Join-Path $PatchDir 'hermes-patch.json'
    if (-not (Test-Path $jsonPath)) { Die "$PatchDir has no hermes-patch.json (unsigned patches cannot be published)" }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { Die 'gh (GitHub CLI) not found' }
    $m = Get-Content -Raw $jsonPath | ConvertFrom-Json
    $assetPath = Join-Path $PatchDir $m.asset
    if (-not (Test-Path $assetPath)) { Die "missing $assetPath" }
    if ((Sha256 $assetPath) -ne $m.sha256) { Die "$($m.asset) does not match the manifest's SHA-256" }
    $releaseTag = if ($Tag) { $Tag } else { 'v' + ($m.baseVersionName -split '-')[0] }
    Quiet { gh release view $releaseTag *> $null }
    if ($LASTEXITCODE -ne 0) { Die "release $releaseTag not found; patches are attached to the base's own release" }

    $existing = Quiet { gh release download $releaseTag -p hermes-patch.json -O - 2>$null }
    if ($LASTEXITCODE -eq 0 -and $existing) {
        $prev = ($existing | Out-String | ConvertFrom-Json).patchVersion
        if ($prev -ge $m.patchVersion) { Die "release $releaseTag already carries patch #$prev; patch versions must increase" }
    }
    if (-not $Yes) {
        $a = Read-Host "Publish fix #$($m.patchVersion) ($($m.asset)) to release $releaseTag? [y/N]"
        if ($a -ne 'y' -and $a -ne 'Y') { Die 'aborted' }
    }
    Invoke-Checked 'gh' @('release', 'upload', $releaseTag, $assetPath, $jsonPath, '--clobber')
    Log "published fix #$($m.patchVersion) on $releaseTag; phones on that build see ""Apply fix"" at their next update check."
}

switch ($Command) {
    'archive-base' { Archive-Base }
    'build-patch' { Build-Patch }
    'publish' { Publish-Patch }
}
