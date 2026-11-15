# FILE: tools/check-release-apk.ps1
# VERSION: 1.0.0
# START_MODULE_CONTRACT
#   PURPOSE: Value-free check of a built YiCamAlt APK: signature schemes, signer equality with another
#     APK, ABI directories, pinned vendor native libs, version and the debuggable flag.
#   SCOPE: SDK/build-tools discovery, apksigner verify (output kept in memory only), zip inspection of
#     lib/<abi>/*.so against app/vendor-natives.sha256, aapt2 dump badging, PASS/FAIL result.
#   DEPENDS: JDK java ($env:JAVA_HOME\bin\java.exe), Android build-tools (lib\apksigner.jar, aapt2.exe),
#     app/vendor-natives.sha256
#   LINKS: M-RELEASE-BUILD, V-M-RELEASE-BUILD
#   ROLE: SCRIPT
#   MAP_MODE: LOCALS
# END_MODULE_CONTRACT
#
# START_MODULE_MAP
#   Write-Line         - prints one value-free console line with the [ReleaseBuild][checkReleaseApk] anchor
#   Resolve-RepoPath   - resolves a path relative to the repository root
#   Invoke-Tool        - runs a native tool, returns exit code + output lines in memory (never printed)
#   Get-SignerInfo     - apksigner verify --verbose --print-certs -> verified/v2/v3/signers/digest (digest in memory)
#   BLOCK_FIND_SDK / BLOCK_CHECK_SIGNATURE / BLOCK_CHECK_NATIVE_LIBS / BLOCK_CHECK_VERSION / BLOCK_CHECK_RESULT
# END_MODULE_MAP
#
# START_CHANGE_SUMMARY
#   LAST_CHANGE: [v1.0.1 - auto-detect expected version from app/build.gradle.kts with 1.0.1 fallback, GSD Phase 7 plan 07-04]
#   PREVIOUS: [v1.0.0 - initial APK checker, GSD Phase 6 plan 06-02, decisions D-01/D-02]
# END_CHANGE_SUMMARY
#
# USAGE:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-release-apk.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-release-apk.ps1 -CompareApk app/build/outputs/apk/debug/app-debug.apk
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/check-release-apk.ps1 -Apk <file> -SkipVersion
#
# EXIT CODES: 0 PASS, 1 FAIL, 2 usage / SDK error (APK missing, SDK_NOT_FOUND)
#
# SECRETS: never prints a certificate DN, a certificate digest, a key path or an absolute path.

[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$Apk = 'app/build/outputs/apk/release/app-release.apk',
    [string]$CompareApk = '',
    [switch]$ExpectSameSigner,
    [string]$ExpectVersionName = '',
    [string]$ExpectVersionCode = '',
    [switch]$SkipVersion
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Split-Path -Parent $PSScriptRoot
$script:Failures = 0

if (-not $ExpectVersionName -or -not $ExpectVersionCode) {
    $gradleKts = Join-Path $RepoRoot 'app\build.gradle.kts'
    if (Test-Path -LiteralPath $gradleKts) {
        $content = Get-Content -LiteralPath $gradleKts
        if (-not $ExpectVersionCode) {
            $codeLine = $content | Where-Object { $_ -match '^\s*versionCode\s*=\s*(\d+)' } | Select-Object -First 1
            if ($codeLine -and $codeLine -match 'versionCode\s*=\s*(\d+)') { $ExpectVersionCode = $Matches[1] }
        }
        if (-not $ExpectVersionName) {
            $nameLine = $content | Where-Object { $_ -match '^\s*versionName\s*=\s*"([^"]+)"' } | Select-Object -First 1
            if ($nameLine -and $nameLine -match 'versionName\s*=\s*"([^"]+)"') { $ExpectVersionName = $Matches[1] }
        }
    }
}
if (-not $ExpectVersionName) { $ExpectVersionName = '1.0.1' }
if (-not $ExpectVersionCode) { $ExpectVersionCode = '10001' }

function Write-Line([string]$Block, [string]$Text) {
    Write-Output "[ReleaseBuild][checkReleaseApk][$Block] $Text"
}

function Resolve-RepoPath([string]$Path) {
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path $RepoRoot $Path)
}

function Invoke-Tool([string]$Exe, [string[]]$ToolArgs) {
    # Output stays in memory; stderr is merged as plain strings so PS 5.1 does not throw on it.
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = & $Exe @ToolArgs 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    return [pscustomobject]@{ Code = $code; Lines = @($lines) }
}

# START_BLOCK_FIND_SDK
$sdk = $null
if ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) { $sdk = $env:ANDROID_HOME }
if (-not $sdk) {
    $lp = Join-Path $RepoRoot 'local.properties'
    if (Test-Path -LiteralPath $lp) {
        $line = Get-Content -LiteralPath $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $val = ($line -replace '^\s*sdk\.dir\s*=\s*', '').Trim()
            $val = $val.Replace('\:', ':').Replace('\\', '\')
            if (Test-Path -LiteralPath $val) { $sdk = $val }
        }
    }
}
$buildTools = $null
if ($sdk) {
    $btRoot = Join-Path $sdk 'build-tools'
    if (Test-Path -LiteralPath $btRoot) {
        $buildTools = Get-ChildItem -LiteralPath $btRoot -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'lib\apksigner.jar') } |
            Sort-Object { try { [version]($_.Name -replace '[^0-9.].*$', '') } catch { [version]'0.0' } } -Descending |
            Select-Object -First 1
    }
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $null }
if (-not $buildTools -or -not $java -or -not (Test-Path -LiteralPath $java)) {
    Write-Line 'BLOCK_FIND_SDK' ("FAIL code=SDK_NOT_FOUND sdk={0} buildTools={1} java={2}" -f `
        ($(if ($sdk) { 'found' } else { 'missing' })), ($(if ($buildTools) { 'found' } else { 'missing' })),
        ($(if ($java -and (Test-Path -LiteralPath $java)) { 'found' } else { 'missing' })))
    exit 2
}
$apksignerJar = Join-Path $buildTools.FullName 'lib\apksigner.jar'
$aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
Write-Line 'BLOCK_FIND_SDK' ("buildTools={0} aapt2={1}" -f $buildTools.Name, ($(if (Test-Path -LiteralPath $aapt2) { 'found' } else { 'missing' })))

$apkPath = Resolve-RepoPath $Apk
if (-not (Test-Path -LiteralPath $apkPath -PathType Leaf)) {
    Write-Line 'BLOCK_CHECK_RESULT' 'RESULT=FAIL failures=1 code=APK_NOT_FOUND'
    exit 2
}
$comparePath = $null
if ($CompareApk) {
    $comparePath = Resolve-RepoPath $CompareApk
    if (-not (Test-Path -LiteralPath $comparePath -PathType Leaf)) {
        Write-Line 'BLOCK_CHECK_RESULT' 'RESULT=FAIL failures=1 code=COMPARE_APK_NOT_FOUND'
        exit 2
    }
}
# END_BLOCK_FIND_SDK

# START_BLOCK_CHECK_SIGNATURE
function Get-SignerInfo([string]$Path) {
    $r = Invoke-Tool $java @('-jar', $apksignerJar, 'verify', '--verbose', '--print-certs', $Path)
    $text = $r.Lines
    $verified = ($r.Code -eq 0) -and [bool]($text | Where-Object { $_ -match '^Verifies\s*$' })
    $v2 = [bool]($text | Where-Object { $_ -match '^Verified using v2 scheme .*:\s*true\s*$' })
    $v3 = [bool]($text | Where-Object { $_ -match '^Verified using v3 scheme .*:\s*true\s*$' })
    $signersLine = $text | Where-Object { $_ -match '^Number of signers:\s*(\d+)' } | Select-Object -First 1
    $signers = if ($signersLine -and ($signersLine -match '(\d+)\s*$')) { [int]$Matches[1] } else { 0 }
    $digestLine = $text | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest:' } | Select-Object -First 1
    $digest = if ($digestLine) { ($digestLine -split ':', 2)[1].Trim().ToLowerInvariant() } else { '' }
    return [pscustomobject]@{ Verified = $verified; V2 = $v2; V3 = $v3; Signers = $signers; Digest = $digest }
}

$sig = Get-SignerInfo $apkPath
Write-Line 'BLOCK_CHECK_SIGNATURE' ("verified={0} v2={1} v3={2} signers={3}" -f `
    $sig.Verified.ToString().ToLower(), $sig.V2.ToString().ToLower(), $sig.V3.ToString().ToLower(), $sig.Signers)
if (-not $sig.Verified -or -not ($sig.V2 -or $sig.V3) -or $sig.Signers -lt 1) { $script:Failures++ }

if ($comparePath) {
    $other = Get-SignerInfo $comparePath
    $same = ($sig.Digest -ne '') -and ($other.Digest -ne '') -and ($sig.Digest -eq $other.Digest)
    Write-Line 'BLOCK_CHECK_SIGNATURE' ("signerSame={0} expectSame={1}" -f $same.ToString().ToLower(), $ExpectSameSigner.IsPresent.ToString().ToLower())
    if ($ExpectSameSigner -and -not $same) { $script:Failures++ }
}
$sig = $null; $other = $null
# END_BLOCK_CHECK_SIGNATURE

# START_BLOCK_CHECK_NATIVE_LIBS
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$expectedAbis = @('arm64-v8a', 'armeabi-v7a')
$manifestPath = Join-Path $RepoRoot 'app/vendor-natives.sha256'
$pins = @{}
foreach ($l in (Get-Content -LiteralPath $manifestPath)) {
    $t = $l.Trim()
    if (-not $t -or $t.StartsWith('#')) { continue }
    $parts = $t -split '\s+'
    $pins[$parts[1]] = [pscustomobject]@{ Hash = $parts[0].ToUpperInvariant(); Size = $(if ($parts.Count -gt 2) { [long]$parts[2] } else { -1 }) }
}
$zip = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
try {
    $soEntries = @($zip.Entries | Where-Object { $_.FullName -match '^lib/[^/]+/[^/]+\.so$' })
    $abis = @($zip.Entries | Where-Object { $_.FullName -match '^lib/([^/]+)/' } |
        ForEach-Object { ($_.FullName -split '/')[1] } | Sort-Object -Unique)
    Write-Line 'BLOCK_CHECK_NATIVE_LIBS' ("abis={0}" -f ($abis -join ','))
    if ((($abis | Sort-Object) -join ',') -ne (($expectedAbis | Sort-Object) -join ',')) { $script:Failures++ }

    $matched = 0
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        foreach ($key in $pins.Keys) {
            $entry = $zip.GetEntry("lib/$key")
            if (-not $entry) { continue }
            $s = $entry.Open()
            try { $bytes = $sha.ComputeHash($s) } finally { $s.Dispose() }
            $hex = ($bytes | ForEach-Object { $_.ToString('X2') }) -join ''
            $pin = $pins[$key]
            if ($hex -eq $pin.Hash -and ($pin.Size -lt 0 -or $pin.Size -eq $entry.Length)) { $matched++ }
        }
    } finally { $sha.Dispose() }
    $vendorNames = @($pins.Keys | ForEach-Object { "lib/$_" })
    $others = @($soEntries | Where-Object { $vendorNames -notcontains $_.FullName })
    $otherNames = @($others | ForEach-Object { ($_.FullName -split '/')[-1] } | Sort-Object -Unique)
    Write-Line 'BLOCK_CHECK_NATIVE_LIBS' ("vendorLibs={0}/{1} otherLibs={2}{3}" -f $matched, $pins.Count, $others.Count,
        $(if ($otherNames.Count -gt 0) { ' names=' + ($otherNames -join ',') } else { '' }))
    if ($matched -lt 8 -or $matched -ne $pins.Count) { $script:Failures++ }
} finally {
    $zip.Dispose()
}
# END_BLOCK_CHECK_NATIVE_LIBS

# START_BLOCK_CHECK_VERSION
if (-not (Test-Path -LiteralPath $aapt2)) {
    Write-Line 'BLOCK_CHECK_VERSION' 'FAIL code=AAPT2_NOT_FOUND'
    $script:Failures++
} else {
    $b = Invoke-Tool $aapt2 @('dump', 'badging', $apkPath)
    $pkgLine = $b.Lines | Where-Object { $_ -match "^package: " } | Select-Object -First 1
    $pkg = if ($pkgLine -match "name='([^']*)'") { $Matches[1] } else { '' }
    $vCode = if ($pkgLine -match "versionCode='([^']*)'") { $Matches[1] } else { '' }
    $vName = if ($pkgLine -match "versionName='([^']*)'") { $Matches[1] } else { '' }
    $debuggable = [bool]($b.Lines | Where-Object { $_ -match '^application-debuggable' })
    Write-Line 'BLOCK_CHECK_VERSION' ("package={0} versionName={1} versionCode={2} debuggable={3}" -f `
        $pkg, $vName, $vCode, $debuggable.ToString().ToLower())
    if ($b.Code -ne 0 -or $pkg -ne 'com.yicamalt') { $script:Failures++ }
    if ($debuggable) { $script:Failures++ }
    if (-not $SkipVersion -and ($vName -ne $ExpectVersionName -or $vCode -ne $ExpectVersionCode)) {
        Write-Line 'BLOCK_CHECK_VERSION' ("versionMismatch expectedName={0} expectedCode={1}" -f $ExpectVersionName, $ExpectVersionCode)
        $script:Failures++
    }
}
# END_BLOCK_CHECK_VERSION

# START_BLOCK_CHECK_RESULT
$result = if ($script:Failures -eq 0) { 'PASS' } else { 'FAIL' }
Write-Line 'BLOCK_CHECK_RESULT' ("RESULT={0} failures={1}" -f $result, $script:Failures)
if ($script:Failures -eq 0) { exit 0 } else { exit 1 }
# END_BLOCK_CHECK_RESULT
