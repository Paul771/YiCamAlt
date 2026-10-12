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

# START_BLOCK_UNINHERIT_STD_HANDLES
# CreateProcess(bInheritHandles=TRUE) hands the child EVERY inheritable handle of this process,
# not only the ones in STARTUPINFO. When this script runs under a harness pipe, the pipe's write
# end lives in this process under std AND stray (inherited-from-parent) handle numbers, so a
# daemon freshly spawned by this run keeps it alive and the caller never sees EOF (observed
# 2026-10-10: build done in 1m30s, caller blocked until `--stop`; clearing only the std handles
# was proven insufficient). Clear HANDLE_FLAG_INHERIT on every handle except stdin, BEFORE
# Start-Process creates the log-file handles; the child still gets those via -Redirect*.
try {
    if (-not ('YiCamAlt.StdHandles' -as [type])) {
        Add-Type -Namespace YiCamAlt -Name StdHandles -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true)] public static extern IntPtr GetStdHandle(int nStdHandle);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool SetHandleInformation(IntPtr hObject, int dwMask, int dwFlags);
[DllImport("kernel32.dll", SetLastError = true)] public static extern bool GetHandleInformation(IntPtr hObject, out int lpdwFlags);
public static int ClearInheritExcept(long keep) {
    int cleared = 0;
    for (long v = 4; v < 0x10000; v += 4) {
        IntPtr h = new IntPtr(v); int flags;
        if (v != keep && GetHandleInformation(h, out flags) && (flags & 1) != 0 && SetHandleInformation(h, 1, 0)) cleared++;
    }
    return cleared;
}
'@
    }
    $stdin = [YiCamAlt.StdHandles]::GetStdHandle(-10).ToInt64()
    [void][YiCamAlt.StdHandles]::ClearInheritExcept($stdin)
} catch {
    Write-Output "--- warning: could not clear std handle inheritance: $($_.Exception.Message) ---"
}
# END_BLOCK_UNINHERIT_STD_HANDLES

# -NoNewWindow + explicit file redirection: the daemon inherits file handles, never our pipe.
$proc = Start-Process -FilePath $java -ArgumentList $argList `
    -WorkingDirectory $repoRoot -NoNewWindow -PassThru `
    -RedirectStandardOutput $outLog -RedirectStandardError $errLog
# Touch Handle now: without a cached handle, Start-Process -PassThru returns a Process whose
# ExitCode is $null after exit, and `exit $null` reports success for a failed build.
$null = $proc.Handle

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
if ($null -eq $code) {
    # Never let an unknown exit code pass as success.
    Write-Output '--- warning: gradle exit code unavailable; reporting 1 ---'
    $code = 1
}
$elapsed = [Math]::Round($sw.Elapsed.TotalSeconds, 1)

Get-Content -LiteralPath $outLog -Tail 60 -ErrorAction SilentlyContinue
if ((Get-Item -LiteralPath $errLog -ErrorAction SilentlyContinue).Length -gt 0) {
    Write-Output '--- stderr ---'
    Get-Content -LiteralPath $errLog -Tail 20 -ErrorAction SilentlyContinue
}

Copy-LastLogs
Write-Output "--- gradle exit=$code elapsed=${elapsed}s log=$outLog ---"
exit $code
