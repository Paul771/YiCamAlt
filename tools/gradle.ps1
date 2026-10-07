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
# USAGE:
#   .\tools\gradle.ps1 :app:testDebugUnitTest
#   .\tools\gradle.ps1 :app:testDebugUnitTest --tests "com.yicamalt.event.*"
#   .\tools\gradle.ps1 :app:assembleDebug -TimeoutSec 3600
#
# EXIT CODE: Gradle's exit code. 124 if the timeout fired and the process tree was killed.

param(
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs,
    [int]$TimeoutSec = 1800
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$logDir = Join-Path $repoRoot 'build\logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$outLog = Join-Path $logDir 'gradle-last.log'
$errLog = Join-Path $logDir 'gradle-last.err.log'

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
    exit 124
}

$code = $proc.ExitCode
$elapsed = [Math]::Round($sw.Elapsed.TotalSeconds, 1)

Get-Content -LiteralPath $outLog -Tail 60 -ErrorAction SilentlyContinue
if ((Get-Item -LiteralPath $errLog -ErrorAction SilentlyContinue).Length -gt 0) {
    Write-Output '--- stderr ---'
    Get-Content -LiteralPath $errLog -Tail 20 -ErrorAction SilentlyContinue
}

Write-Output "--- gradle exit=$code elapsed=${elapsed}s log=$outLog ---"
exit $code
