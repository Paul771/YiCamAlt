# FILE: tools/install-hooks.ps1
# ROLE: SCRIPT
#
# PURPOSE: Install the versioned SEC-03 repo guard (tools/git-hooks/pre-commit) into
#   .git/hooks/pre-commit of this checkout.
#
# WHY A COPY AND NOT core.hooksPath: the repo already uses Git LFS hooks in .git/hooks
#   (post-checkout, post-commit, post-merge, pre-push). Pointing core.hooksPath elsewhere would
#   silently disable them, so this script copies one file and leaves the rest untouched.
#
# USAGE (from anywhere):
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/install-hooks.ps1
#
# Re-run after pulling a new version of tools/git-hooks/pre-commit (the copy is not a link).
# The hook is written with LF line endings and UTF-8 without BOM, as Git's sh requires.

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$source = Join-Path $repoRoot 'tools/git-hooks/pre-commit'
$hooksDir = Join-Path $repoRoot '.git/hooks'
$target = Join-Path $hooksDir 'pre-commit'

if (-not (Test-Path -LiteralPath $source)) { throw "hook source not found: $source" }
if (-not (Test-Path -LiteralPath $hooksDir)) { throw "not a git checkout with .git/hooks: $repoRoot" }

$text = [IO.File]::ReadAllText($source)
$text = $text.Replace("`r`n", "`n")
[IO.File]::WriteAllText($target, $text, (New-Object System.Text.UTF8Encoding($false)))

Write-Output "installed pre-commit; existing LFS hooks untouched ($target)"
