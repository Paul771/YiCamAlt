# FILE: tools/clean-clone-build.ps1
# VERSION: 1.0.0
# START_MODULE_CONTRACT
#   PURPOSE: Prove REL-01 from a clean clone: clone the committed HEAD into a temp folder outside the
#     repository, build a signed release APK there with only the local Yi Home APK folder passed by
#     Gradle property, check the APK and the clone hygiene, then remove the clone.
#   SCOPE: worktree precheck, git clone + detached checkout, tracked-vendor/key scan, assembleRelease
#     with --no-build-cache through the clone's own tools/gradle.ps1 (output kept in memory, never
#     printed), Gradle log anchor scan, tools/check-release-apk.ps1 -CompareApk -ExpectSameSigner,
#     post-build git status, guarded clean-up, PASS/FAIL result.
#   DEPENDS: git, JDK (JAVA_HOME), Android SDK (ANDROID_HOME or repo local.properties sdk.dir),
#     tools/gradle.ps1, tools/check-release-apk.ps1, app/build.gradle.kts (extractVendorNatives,
#     verifyReleaseSigning), external release signing file (default location, never copied)
#   LINKS: M-RELEASE-BUILD, M-P2P-NATIVE, V-M-RELEASE-BUILD
#   ROLE: SCRIPT
#   MAP_MODE: LOCALS
# END_MODULE_CONTRACT
#
# START_MODULE_MAP
#   Write-Line        - prints one value-free console line with the [ReleaseBuild][cleanCloneBuild] anchor
#   Invoke-Git        - runs git, returns exit code + output lines in memory
#   Test-UnderPath    - true if a full path is equal to or below a root path
#   Remove-Clone      - guarded removal of the temp clone (temp root, name prefix, not under repo)
#   Finish            - prints the final RESULT line, cleans up and exits
#   BLOCK_PRECHECK / BLOCK_CLONE / BLOCK_CLONE_HYGIENE / BLOCK_CLONE_BUILD / BLOCK_CLONE_CHECK /
#   BLOCK_CLONE_AFTER / BLOCK_CLEANUP / BLOCK_CLEAN_CLONE
# END_MODULE_MAP
#
# START_CHANGE_SUMMARY
#   LAST_CHANGE: [v1.0.0 - initial clean clone release build check, GSD Phase 6 plan 06-03, REL-01]
# END_CHANGE_SUMMARY
#
# USAGE:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/clean-clone-build.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/clean-clone-build.ps1 -Offline
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/clean-clone-build.ps1 -VendorApk <file> -KeepClone
#
# EXIT CODES: 0 PASS, 1 FAIL, 2 precondition (DIRTY_WORKTREE, VENDOR_APK_MISSING)
#
# SECRETS: never prints Gradle output, absolute user paths, passwords or digests; the signing key and
#   its properties file are never copied into the clone (Gradle finds them at the default location).

[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$VendorApkDir = '',
    [string]$VendorApk = '',
    [switch]$KeepClone,
    [switch]$Offline,
    [int]$TimeoutSec = 3600
)

$ErrorActionPreference = 'Stop'
$RepoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
$script:Failures = 0
$script:Clone = $null
$script:Short = 'unknown'

function Write-Line([string]$Block, [string]$Text) {
    Write-Output "[ReleaseBuild][cleanCloneBuild][$Block] $Text"
}

function Invoke-Git([string[]]$GitArgs) {
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = & git @GitArgs 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    return [pscustomobject]@{ Code = $code; Lines = @($lines | Where-Object { $_ -ne '' }) }
}

function Test-UnderPath([string]$Path, [string]$Root) {
    $p = [IO.Path]::GetFullPath($Path).TrimEnd('\') + '\'
    $r = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    return $p.StartsWith($r, [StringComparison]::OrdinalIgnoreCase)
}

# START_BLOCK_CLEANUP
function Remove-Clone {
    if (-not $script:Clone) { return }
    if ($KeepClone) {
        Write-Line 'BLOCK_CLEANUP' ("removed=false kept=true clone=<temp>/{0}" -f (Split-Path -Leaf $script:Clone))
        return
    }
    $full = [IO.Path]::GetFullPath($script:Clone).TrimEnd('\')
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    $leaf = Split-Path -Leaf $full
    $guardOk = (Test-UnderPath $full $tempRoot) -and ($full -ne $tempRoot) -and
        $leaf.StartsWith('yicamalt-clean-') -and -not (Test-UnderPath $full $RepoRoot)
    if (-not $guardOk) {
        Write-Line 'BLOCK_CLEANUP' 'removed=false code=GUARD_REFUSED'
        $script:Failures++
        return
    }
    $removed = $false
    for ($i = 0; $i -lt 2 -and -not $removed; $i++) {
        if ($i -gt 0) { Start-Sleep -Seconds 5 }
        try {
            if (Test-Path -LiteralPath $full) { Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop }
        } catch { }
        $removed = -not (Test-Path -LiteralPath $full)
    }
    Write-Line 'BLOCK_CLEANUP' ("removed={0}" -f $removed.ToString().ToLower())
    if (-not $removed) { $script:Failures++ }
}
# END_BLOCK_CLEANUP

function Finish([int]$PreconditionCode = 0) {
    Remove-Clone
    $result = if ($script:Failures -eq 0 -and $PreconditionCode -eq 0) { 'PASS' } else { 'FAIL' }
    # START_BLOCK_CLEAN_CLONE
    Write-Line 'BLOCK_CLEAN_CLONE' ("RESULT={0} commit={1}" -f $result, $script:Short)
    # END_BLOCK_CLEAN_CLONE
    if ($PreconditionCode -ne 0) { exit $PreconditionCode }
    if ($result -eq 'PASS') { exit 0 } else { exit 1 }
}

# START_BLOCK_PRECHECK
$st = Invoke-Git @('-C', $RepoRoot, 'status', '--porcelain', '--untracked-files=no')
if ($st.Code -ne 0 -or $st.Lines.Count -gt 0) {
    Write-Line 'BLOCK_PRECHECK' ("FAIL code=DIRTY_WORKTREE changed={0}" -f $st.Lines.Count)
    Finish 2
}
$fullSha = ((Invoke-Git @('-C', $RepoRoot, 'rev-parse', 'HEAD')).Lines | Select-Object -First 1).Trim()
$script:Short = ((Invoke-Git @('-C', $RepoRoot, 'rev-parse', '--short', 'HEAD')).Lines | Select-Object -First 1).Trim()

$vendorArg = $null
if ($VendorApk) {
    $va = if ([IO.Path]::IsPathRooted($VendorApk)) { $VendorApk } else { Join-Path $RepoRoot $VendorApk }
    if (-not (Test-Path -LiteralPath $va -PathType Leaf)) {
        Write-Line 'BLOCK_PRECHECK' 'FAIL code=VENDOR_APK_MISSING mode=file'
        Finish 2
    }
    $vendorArg = '-PvendorApk=' + ([IO.Path]::GetFullPath($va) -replace '\\', '/')
    $vendorMode = 'file'
} else {
    $vd = if (-not $VendorApkDir) { Join-Path $RepoRoot '_apk' }
          elseif ([IO.Path]::IsPathRooted($VendorApkDir)) { $VendorApkDir }
          else { Join-Path $RepoRoot $VendorApkDir }
    if (-not (Test-Path -LiteralPath $vd -PathType Container)) {
        Write-Line 'BLOCK_PRECHECK' 'FAIL code=VENDOR_APK_MISSING mode=dir'
        Finish 2
    }
    $vendorArg = '-PvendorApkDir=' + ([IO.Path]::GetFullPath($vd) -replace '\\', '/')
    $vendorMode = 'dir'
}
$repoApk = Join-Path $RepoRoot 'app\build\outputs\apk\release\app-release.apk'
$compare = if (Test-Path -LiteralPath $repoApk -PathType Leaf) { 'repo' } else { 'skipped' }
Write-Line 'BLOCK_PRECHECK' ("worktree=clean commit={0} vendor={1} compare={2}" -f $script:Short, $vendorMode, $compare)
# END_BLOCK_PRECHECK

# START_BLOCK_CLONE
$tempRootPath = [IO.Path]::GetTempPath()
$clone = Join-Path $tempRootPath ("yicamalt-clean-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
$clone = [IO.Path]::GetFullPath($clone).TrimEnd('\')
if (Test-UnderPath $clone $RepoRoot) {
    Write-Line 'BLOCK_CLONE' 'FAIL code=CLONE_UNDER_REPO'
    Finish 1
}
$cloneName = Split-Path -Leaf $clone
$c = Invoke-Git @('clone', '--quiet', '--no-hardlinks', $RepoRoot, $clone)
if (Test-Path -LiteralPath $clone) { $script:Clone = $clone }
if ($c.Code -ne 0) {
    Write-Line 'BLOCK_CLONE' ("FAIL code=CLONE_FAILED exit={0}" -f $c.Code)
    $script:Failures++
    Finish
}
$co = Invoke-Git @('-C', $clone, 'checkout', '--quiet', '--detach', $fullSha)
if ($co.Code -ne 0) {
    Write-Line 'BLOCK_CLONE' ("FAIL code=CHECKOUT_FAILED exit={0}" -f $co.Code)
    $script:Failures++
    Finish
}
Write-Line 'BLOCK_CLONE' ("clone=<temp>/{0} commit={1}" -f $cloneName, $script:Short)
# END_BLOCK_CLONE

# START_BLOCK_CLONE_HYGIENE
$tracked = (Invoke-Git @('-C', $clone, 'ls-files')).Lines
$pattern = '\.(apk|apks|apkm|xapk|aab|so|dex|jks|keystore|p12|pfx)$|(^|/)(release-signing|keystore)\.properties$'
$badTracked = @($tracked | Where-Object { $_ -match $pattern })
$apkDirState = if (Test-Path -LiteralPath (Join-Path $clone '_apk')) { 'present' } else { 'absent' }
$appBuildState = if (Test-Path -LiteralPath (Join-Path $clone 'app\build')) { 'present' } else { 'absent' }
$lpState = if (Test-Path -LiteralPath (Join-Path $clone 'local.properties')) { 'present' } else { 'absent' }
Write-Line 'BLOCK_CLONE_HYGIENE' ("trackedVendorFiles={0} apkDir={1} appBuild={2} localProperties={3}" -f `
    $badTracked.Count, $apkDirState, $appBuildState, $lpState)
if ($badTracked.Count -ne 0 -or $apkDirState -ne 'absent' -or $appBuildState -ne 'absent' -or $lpState -ne 'absent') {
    $script:Failures++
}
# END_BLOCK_CLONE_HYGIENE

# START_BLOCK_CLONE_BUILD
if (-not $env:ANDROID_HOME) {
    $lp = Join-Path $RepoRoot 'local.properties'
    if (Test-Path -LiteralPath $lp) {
        $line = Get-Content -LiteralPath $lp | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1
        if ($line) {
            $val = ($line -replace '^\s*sdk\.dir\s*=\s*', '').Trim().Replace('\:', ':').Replace('\\', '\')
            if (Test-Path -LiteralPath $val) { $env:ANDROID_HOME = $val }
        }
    }
}
$sdkState = if ($env:ANDROID_HOME) { 'set' } else { 'missing' }
$gradleArgs = @(':app:assembleRelease', '--no-daemon', '--no-build-cache', $vendorArg)
if ($Offline) { $gradleArgs += '--offline' }
$cloneGradle = Join-Path $clone 'tools\gradle.ps1'
$old = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    # Output stays in memory: AGP output may contain the keystore path.
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $cloneGradle @gradleArgs -TimeoutSec $TimeoutSec 2>&1 |
        ForEach-Object { "$_" }
    $buildExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $old
}
$exitSeen = [bool]($out | Where-Object { $_ -match '^--- gradle exit=0 ' })
$successSeen = [bool]($out | Where-Object { $_ -match 'BUILD SUCCESSFUL' })
$out = $null
$logDir = Join-Path $clone 'build\logs'
$log = $null
if (Test-Path -LiteralPath $logDir) {
    $log = Get-ChildItem -LiteralPath $logDir -Filter 'gradle-*.log' |
        Where-Object { $_.Name -notmatch '\.err\.log$' -and $_.Name -ne 'gradle-last.log' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}
$vendorNatives = 'missing'
$signing = 'missing'
if ($log) {
    $logText = Get-Content -LiteralPath $log.FullName
    if ($logText | Where-Object { $_ -match 'vendor natives: OK \(8 libs, manifest match\)' }) { $vendorNatives = 'OK' }
    elseif ($logText | Where-Object { $_ -match 'vendor natives: ' }) { $vendorNatives = 'other' }
    if ($logText | Where-Object { $_ -match 'BLOCK_VERIFY_RELEASE_SIGNING\] result=ok' }) { $signing = 'ok' }
    $logText = $null
}
$buildOk = ($buildExit -eq 0) -and $exitSeen -and $successSeen
if (-not $buildOk) {
    $logName = if ($log) { 'build/logs/' + $log.Name } else { 'none' }
    Write-Line 'BLOCK_CLONE_BUILD' ("build=FAIL exit={0} sdk={1} log={2}" -f $buildExit, $sdkState, $logName)
}
Write-Line 'BLOCK_CLONE_BUILD' ("build={0} vendorNatives={1} signing={2} offline={3}" -f `
    $(if ($buildOk) { 'OK' } else { 'FAIL' }), $vendorNatives, $signing, $Offline.IsPresent.ToString().ToLower())
if (-not $buildOk -or $vendorNatives -ne 'OK' -or $signing -ne 'ok') { $script:Failures++ }
# END_BLOCK_CLONE_BUILD

# START_BLOCK_CLONE_CHECK
$cloneApk = Join-Path $clone 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path -LiteralPath $cloneApk -PathType Leaf)) {
    Write-Line 'BLOCK_CLONE_CHECK' 'apkCheck=FAIL code=CLONE_APK_MISSING'
    $script:Failures++
} else {
    $checker = Join-Path $RepoRoot 'tools\check-release-apk.ps1'
    $checkArgs = @('-Apk', $cloneApk)
    if ($compare -eq 'repo') { $checkArgs += @('-CompareApk', $repoApk, '-ExpectSameSigner') }
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $chk = & powershell -NoProfile -ExecutionPolicy Bypass -File $checker @checkArgs 2>&1 | ForEach-Object { "$_" }
        $chkExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $old
    }
    # The checker is value-free by contract; forward only its anchored lines.
    $chk | Where-Object { $_ -match '^\[ReleaseBuild\]\[checkReleaseApk\]' } | ForEach-Object { Write-Output $_ }
    $pass = ($chkExit -eq 0) -and [bool]($chk | Where-Object { $_ -match 'BLOCK_CHECK_RESULT\] RESULT=PASS' })
    $sameLine = $chk | Where-Object { $_ -match 'signerSame=(true|false)' } | Select-Object -First 1
    $same = if ($sameLine -and ($sameLine -match 'signerSame=(true|false)')) { $Matches[1] } else { 'skipped' }
    Write-Line 'BLOCK_CLONE_CHECK' ("apkCheck={0} signerSame={1}" -f $(if ($pass) { 'PASS' } else { 'FAIL' }), $same)
    if (-not $pass) { $script:Failures++ }
    if ($compare -eq 'repo' -and $same -ne 'true') { $script:Failures++ }
}
# END_BLOCK_CLONE_CHECK

# START_BLOCK_CLONE_AFTER
$changed = (Invoke-Git @('-C', $clone, 'status', '--porcelain', '--untracked-files=no')).Lines
$untracked = (Invoke-Git @('-C', $clone, 'ls-files', '--others', '--exclude-standard')).Lines
Write-Line 'BLOCK_CLONE_AFTER' ("trackedChanged={0} untrackedNotIgnored={1}" -f $changed.Count, $untracked.Count)
if ($changed.Count -ne 0 -or $untracked.Count -ne 0) { $script:Failures++ }
# END_BLOCK_CLONE_AFTER

Finish
