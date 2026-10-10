# FILE: tools/gradle.ps1
# ROLE: SCRIPT
#
# PURPOSE: Run Gradle from a harness (agent tool call, CI step) WITHOUT ever hanging.
#
# WHY THIS EXISTS — two independent hang mechanisms, both observed:
#
#   1. Daemon stdout inheritance. A Gradle daemon inherits file descriptors from the client
#      that started it. If those descriptors are the harness's stdout PIPE, the pipe's write
#      end stays open for the daemon's whole (idle) lifetime, so the harness never sees EOF
#      and blocks long after the build printed BUILD SUCCESSFUL. Observed: build done in 1m29s,
#      caller still blocked ~48 minutes.
#      FIX: never hand the daemon the harness pipe. Redirect the child to real FILES with
#      Start-Process -RedirectStandardOutput/-RedirectStandardError. A file handle cannot
#      wedge a pipe reader.
#
#   2. cmd.exe layer. `cmd /c "gradlew.bat ... > log 2>&1"` looks correct but still routes
#      through the batch wrapper's own console handles. Calling java.exe directly removes that
#      layer entirely — and matches HANDOFF.md §4.1 for machines where AppLocker blocks .bat.
#
#   Also: a hard timeout with a kill, so a wedged build can never occupy a harness slot again.
#
# USAGE (always via -File from the repo root; AppLocker blocks .bat/.cmd):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.event.*" -PredactionLogFile=E:/x/diag_log.txt
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/gradle.ps1 :app:testDebugUnitTest :app:assembleDebug -TimeoutSec 3600
#
#   Named parameters of this script: -TimeoutSec, -DryRun; everything else goes to Gradle.
#   -DryRun prints "GRADLE_ARGS=<args joined with '|'> TIMEOUT=<n>" and exits 0 without
#   starting Java (used by tools/test-gradle-args.ps1).
#
# LOGS: each run writes build/logs/gradle-<PID>.log and gradle-<PID>.err.log (PID of this
#   wrapper process), so parallel runs cannot clobber each other; when the run ends they are
#   copied to build/logs/gradle-last.log and gradle-last.err.log. Judge a run only by its own
#   per-PID log and the "--- gradle exit=" line.
#
# EXIT CODE: Gradle's exit code. 124 if the timeout fired and the process tree was killed.

# PositionalBinding=$false: with -File, a plain positional token must never bind to
# -TimeoutSec ("cannot convert ':app:testDebugUnitTest' to Int32"). Only GradleArgs is positional.
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Mandatory = $true, Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs,
    [int]$TimeoutSec = 1800,
    [switch]$DryRun
)

if ($DryRun) {
    Write-Output ("GRADLE_ARGS=" + ($GradleArgs -join '|') + " TIMEOUT=$TimeoutSec")
    exit 0
}

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repoRoot 'build\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$outLog = Join-Path $logDir "gradle-$PID.log"
$errLog = Join-Path $logDir "gradle-$PID.err.log"
$lastOutLog = Join-Path $logDir 'gradle-last.log'
$lastErrLog = Join-Path $logDir 'gradle-last.err.log'
Write-Output "--- gradle log=$outLog ---"

function Copy-LastLogs {
    Copy-Item -LiteralPath $outLog -Destination $lastOutLog -Force -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $errLog -Destination $lastErrLog -Force -ErrorAction SilentlyContinue
}

# HANDOFF.md §4.1: invoke the wrapper's Java entry point directly, no .bat/.cmd layer.
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) {
    $java = (Get-Command java -ErrorAction SilentlyContinue).Source
}
if (-not $java -or -not (Test-Path -LiteralPath $java)) {
    Write-Error 'java.exe not found. Set JAVA_HOME to a JDK 17 installation.'
    exit 1
}

$argList = @(
    '-cp', (Join-Path $repoRoot 'gradle\wrapper\gradle-wrapper.jar'),
    'org.gradle.wrapper.GradleWrapperMain'
) + $GradleArgs

$sw = [Diagnostics.Stopwatch]::StartNew()

# -NoNewWindow + explicit file redirection: the daemon inherits file handles, never our pipe.
$proc = Start-Process -FilePath $java -ArgumentList $argList `
    -WorkingDirectory $repoRoot -NoNewWindow -PassThru `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog

$finished = $proc.WaitForExit($TimeoutSec * 1000)

if (-not $finished) {
    Write-Output "TIMEOUT after ${TimeoutSec}s - killing process tree and stopping daemon."
    try { & $java '-cp' (Join-Path $repoRoot 'gradle\wrapper\gradle-wrapper.jar') `
            'org.gradle.wrapper.GradleWrapperMain' '--stop' 2>&1 | Out-Null } catch { }
    try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { }
    Get-Content -LiteralPath $outLog -Tail 40 -ErrorAction SilentlyContinue
    Copy-LastLogs
    exit 124
}

$code = $proc.ExitCode
$elapsed = [Math]::Round($sw.Elapsed.TotalSeconds, 1)

Get-Content -LiteralPath $outLog -Tail 60 -ErrorAction SilentlyContinue
if ((Get-Item -LiteralPath $errLog -ErrorAction SilentlyContinue).Length -gt 0) {
    Write-Output '--- stderr ---'
    Get-Content -LiteralPath $errLog -Tail 20 -ErrorAction SilentlyContinue
}

Copy-LastLogs
Write-Output "--- gradle exit=$code elapsed=${elapsed}s log=$outLog ---"
exit $code
