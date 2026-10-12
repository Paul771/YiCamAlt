# FILE: tools/test-gradle-args.ps1
# VERSION: 1.0.0
# START_MODULE_CONTRACT
#   PURPOSE: Prove that tools/gradle.ps1 binds every argument shape used in this repo (no Gradle launch).
#   SCOPE: Invokes gradle.ps1 -DryRun through `powershell -File` and compares the printed argument vector.
#   DEPENDS: tools/gradle.ps1
#   LINKS: M-REPO-HYGIENE
#   ROLE: SCRIPT
#   MAP_MODE: LOCALS
# END_MODULE_CONTRACT
#
# START_MODULE_MAP
#   $cases - argument vectors and the exact expected DryRun line
#   loop   - runs each case, prints PASS <n> / FAIL <n> got=<line>; exit 0 only if all pass
# END_MODULE_MAP
#
# USAGE: powershell -NoProfile -ExecutionPolicy Bypass -File tools/test-gradle-args.ps1

$ErrorActionPreference = 'Stop'
$script = Join-Path $PSScriptRoot 'gradle.ps1'

# START_BLOCK_CASES
$cases = @(
    @{ Args = @(':app:testDebugUnitTest');
       Expect = 'GRADLE_ARGS=:app:testDebugUnitTest TIMEOUT=1800' },
    @{ Args = @(':app:testDebugUnitTest', '--tests', 'com.yicamalt.network.*');
       Expect = 'GRADLE_ARGS=:app:testDebugUnitTest|--tests|com.yicamalt.network.* TIMEOUT=1800' },
    @{ Args = @(':app:testDebugUnitTest', '--tests', 'a.B', '--tests', 'c.D', '-PredactionLogFile=E:/x/diag_log.txt');
       Expect = 'GRADLE_ARGS=:app:testDebugUnitTest|--tests|a.B|--tests|c.D|-PredactionLogFile=E:/x/diag_log.txt TIMEOUT=1800' },
    @{ Args = @(':app:testDebugUnitTest', ':app:assembleDebug', '-TimeoutSec', '60');
       Expect = 'GRADLE_ARGS=:app:testDebugUnitTest|:app:assembleDebug TIMEOUT=60' }
)
# END_BLOCK_CASES

# START_BLOCK_RUN_CASES
$failed = 0
$n = 0
foreach ($c in $cases) {
    $n++
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $script @($c.Args) -DryRun 2>&1
    $line = (@($out) | ForEach-Object { "$_" } | Where-Object { $_ -like 'GRADLE_ARGS=*' } | Select-Object -First 1)
    if ($LASTEXITCODE -eq 0 -and $line -ceq $c.Expect) {
        Write-Output "PASS $n"
    } else {
        $got = if ($line) { $line } else { (@($out) -join ' ') }
        Write-Output "FAIL $n got=$got"
        $failed++
    }
}
# END_BLOCK_RUN_CASES

if ($failed -gt 0) { exit 1 }
exit 0
