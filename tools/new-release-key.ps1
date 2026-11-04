# FILE: tools/new-release-key.ps1
# VERSION: 1.0.0
# START_MODULE_CONTRACT
#   PURPOSE: Create the single YiCamAlt release signing key (PKCS12) and its signing properties file
#     outside the repository, or check that they are present, without printing any secret value.
#   SCOPE: -Check (value-free presence check), target validation (refuse in-repo folder, refuse
#     overwrite), -DryRun, password generation or SecureString prompt, keytool -genkeypair with
#     passwords passed only via environment variables, properties writing, per-user ACL.
#   DEPENDS: JDK keytool ($env:JAVA_HOME\bin\keytool.exe), icacls
#   LINKS: M-RELEASE-BUILD
#   ROLE: SCRIPT
#   MAP_MODE: LOCALS
# END_MODULE_CONTRACT
#
# START_MODULE_MAP
#   Write-Line           - prints one value-free console line with the [ReleaseBuild] log anchor
#   Test-OwnerOnlyAcl    - true when a path has no inherited ACEs and grants only the current user
#   Set-OwnerOnlyAcl     - icacls /inheritance:r + grant current user full control (output discarded)
#   ConvertTo-PropValue  - escapes a value for java.util.Properties (\ -> \\, non-ASCII -> \uXXXX)
#   New-RandomPassword   - 32 chars [A-Za-z0-9] from RandomNumberGenerator, rejection sampling
#   BLOCK_CHECK / BLOCK_CHECK_TARGET / BLOCK_PASSWORD / BLOCK_GENERATE_KEY / BLOCK_WRITE_PROPERTIES
# END_MODULE_MAP
#
# START_CHANGE_SUMMARY
#   LAST_CHANGE: [v1.0.0 - initial release key generator, GSD Phase 6 plan 06-01, decision D-01]
# END_CHANGE_SUMMARY
#
# USAGE:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/new-release-key.ps1            # generated password
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/new-release-key.ps1 -Prompt    # own password
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/new-release-key.ps1 -Check     # value-free check
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/new-release-key.ps1 -DryRun
#
# EXIT CODES: 0 ok, 1 check failed, 2 RELEASE_KEY_PATH_IN_REPO, 3 RELEASE_KEY_EXISTS,
#   4 KEYTOOL_NOT_FOUND, 5 BAD_PASSWORD, 6 KEYTOOL_FAILED, 7 WRITE_FAILED
#
# SECRETS: this script never prints a password, a certificate digest or an absolute path.

[CmdletBinding(PositionalBinding = $false)]
param(
    [string]$OutDir = (Join-Path $env:USERPROFILE '.yicamalt'),
    [string]$Alias = 'yicamalt-release',
    [switch]$Prompt,
    [switch]$DryRun,
    [switch]$Check
)

$ErrorActionPreference = 'Stop'
$Tag = '[ReleaseBuild][newReleaseKey][BLOCK_NEW_KEY]'
$StoreName = 'yicamalt-release.p12'
$PropsName = 'release-signing.properties'
$PwEnv = 'YICAMALT_KS_PW'

function Write-Line([string]$text) { Write-Output "$Tag $text" }

function Get-CurrentSid {
    return [System.Security.Principal.WindowsIdentity]::GetCurrent().User
}

function Test-OwnerOnlyAcl([string]$path) {
    try {
        $acl = Get-Acl -LiteralPath $path
        if (-not $acl.AreAccessRulesProtected) { return $false }
        $rules = $acl.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier])
        if ($rules.Count -eq 0) { return $false }
        $me = Get-CurrentSid
        foreach ($r in $rules) {
            if ($r.IsInherited) { return $false }
            if ($r.IdentityReference.Value -ne $me.Value) { return $false }
        }
        return $true
    } catch {
        return $false
    }
}

function Set-OwnerOnlyAcl([string]$path, [bool]$isDir) {
    $grant = if ($isDir) { "*$((Get-CurrentSid).Value):(OI)(CI)(F)" } else { "*$((Get-CurrentSid).Value):(F)" }
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & icacls $path /inheritance:r /grant:r $grant 2>&1 | Out-Null
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    } finally {
        $ErrorActionPreference = $prev
    }
}

function ConvertTo-PropValue([string]$value) {
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $value.ToCharArray()) {
        if ($ch -eq '\') { [void]$sb.Append('\\') }
        elseif ([int]$ch -gt 126 -or [int]$ch -lt 32) { [void]$sb.Append(('\u{0:x4}' -f [int]$ch)) }
        else { [void]$sb.Append($ch) }
    }
    return $sb.ToString()
}

function New-RandomPassword([int]$length) {
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $sb = New-Object System.Text.StringBuilder
        $buf = New-Object byte[] 1
        while ($sb.Length -lt $length) {
            $rng.GetBytes($buf)
            # 248 = 4 * 62: discarding 248..255 removes modulo bias.
            if ($buf[0] -ge 248) { continue }
            [void]$sb.Append($alphabet[$buf[0] % 62])
        }
        return $sb.ToString()
    } finally {
        $rng.Dispose()
    }
}

function ConvertFrom-Secure([System.Security.SecureString]$s) {
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

$fullOut = [IO.Path]::GetFullPath($OutDir)
$storePath = Join-Path $fullOut $StoreName
$propsPath = Join-Path $fullOut $PropsName

# START_BLOCK_CHECK
if ($Check) {
    $keys = 0
    $store = 'missing'
    $aclState = 'n/a'
    if (Test-Path -LiteralPath $propsPath -PathType Leaf) {
        $map = @{}
        foreach ($line in [IO.File]::ReadAllLines($propsPath)) {
            $t = $line.Trim()
            if ($t.Length -eq 0 -or $t.StartsWith('#') -or $t.StartsWith('!')) { continue }
            $i = $t.IndexOfAny([char[]]@('=', ':'))
            if ($i -lt 1) { continue }
            $map[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
        }
        foreach ($k in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
            if ($map.ContainsKey($k) -and $map[$k].Length -gt 0) { $keys++ }
        }
        if ($map.ContainsKey('storeFile') -and $map['storeFile'].Length -gt 0) {
            $sf = $map['storeFile'].Replace('\\', '\')
            if (Test-Path -LiteralPath $sf -PathType Leaf) {
                $store = 'found'
                $aclState = if ((Test-OwnerOnlyAcl $propsPath) -and (Test-OwnerOnlyAcl $sf)) { 'ok' } else { 'open' }
            } else {
                $aclState = if (Test-OwnerOnlyAcl $propsPath) { 'ok' } else { 'open' }
            }
        }
        $map = $null
    }
    Write-Line "keys=$keys/4 store=$store acl=$aclState"
    if ($keys -eq 4 -and $store -eq 'found') { exit 0 } else { exit 1 }
}
# END_BLOCK_CHECK

# START_BLOCK_CHECK_TARGET
$repoRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\', '/')
$outNorm = $fullOut.TrimEnd('\', '/')
if ($outNorm.Equals($repoRoot, [StringComparison]::OrdinalIgnoreCase) -or
    $outNorm.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    Write-Line 'result=refused code=RELEASE_KEY_PATH_IN_REPO'
    exit 2
}
if ((Test-Path -LiteralPath $storePath) -or (Test-Path -LiteralPath $propsPath)) {
    Write-Line 'result=refused code=RELEASE_KEY_EXISTS hint=back-up-and-reuse'
    exit 3
}
$keytool = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\keytool.exe' } else { $null }
if (-not $keytool -or -not (Test-Path -LiteralPath $keytool -PathType Leaf)) {
    Write-Line 'result=refused code=KEYTOOL_NOT_FOUND'
    exit 4
}
if ($Alias -notmatch '^[A-Za-z0-9._-]+$') {
    Write-Line 'result=refused code=BAD_ALIAS'
    exit 5
}
# END_BLOCK_CHECK_TARGET

if ($DryRun) {
    $kind = if ($env:USERPROFILE -and $outNorm.StartsWith($env:USERPROFILE.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)) { 'userprofile-relative' } else { 'outside-repo' }
    Write-Line "result=dry-run target=$kind keytool=found alias=$Alias"
    exit 0
}

# START_BLOCK_PASSWORD
$password = $null
if ($Prompt) {
    $a = Read-Host -AsSecureString -Prompt 'Release key password (min 12 chars)'
    $b = Read-Host -AsSecureString -Prompt 'Repeat password'
    $pa = ConvertFrom-Secure $a
    $pb = ConvertFrom-Secure $b
    $bad = ($pa -cne $pb) -or ($pa.Length -lt 12) -or ($pa -match '[\\#!=:]') -or ($pa -ne $pa.Trim())
    $pb = $null
    if ($bad) {
        $pa = $null
        Write-Line 'result=refused code=BAD_PASSWORD hint=min-12-chars-match-no-backslash-hash-bang-equals-colon-no-edge-spaces'
        exit 5
    }
    $password = $pa
    $pa = $null
} else {
    $password = New-RandomPassword 32
}
# END_BLOCK_PASSWORD

# START_BLOCK_GENERATE_KEY
New-Item -ItemType Directory -Force -Path $fullOut | Out-Null
$ktArgs = @('-genkeypair', '-keystore', $storePath, '-storetype', 'PKCS12', '-alias', $Alias,
    '-keyalg', 'RSA', '-keysize', '4096', '-validity', '10000', '-dname', 'CN=YiCamAlt Release',
    '-storepass:env', $PwEnv, '-keypass:env', $PwEnv, '-noprompt')
$ktCode = -1
$prevEap = $ErrorActionPreference
try {
    [Environment]::SetEnvironmentVariable($PwEnv, $password, 'Process')
    $ErrorActionPreference = 'Continue'
    $ktOut = & $keytool @ktArgs 2>&1
    $ktCode = $LASTEXITCODE
    $ktOut = $null
} finally {
    $ErrorActionPreference = $prevEap
    [Environment]::SetEnvironmentVariable($PwEnv, $null, 'Process')
}
if ($ktCode -ne 0 -or -not (Test-Path -LiteralPath $storePath -PathType Leaf)) {
    if (Test-Path -LiteralPath $storePath) { Remove-Item -LiteralPath $storePath -Force }
    $password = $null
    Write-Line "result=failed code=KEYTOOL_FAILED keytool_exit=$ktCode"
    exit 6
}
# END_BLOCK_GENERATE_KEY

# START_BLOCK_WRITE_PROPERTIES
try {
    $pwValue = ConvertTo-PropValue $password
    $lines = @(
        '# YiCamAlt release signing (outside Git). Back up this file together with the keystore.',
        ('storeFile=' + (ConvertTo-PropValue ($storePath.Replace('\', '/')))),
        ('storePassword=' + $pwValue),
        ('keyAlias=' + $Alias),
        ('keyPassword=' + $pwValue)
    )
    [IO.File]::WriteAllText($propsPath, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
} catch {
    Write-Line 'result=failed code=WRITE_FAILED hint=keystore-kept-delete-it-manually-before-retry'
    exit 7
} finally {
    $pwValue = $null
    $lines = $null
    $password = $null
}

$aclOk = (Set-OwnerOnlyAcl $storePath $false) -and (Set-OwnerOnlyAcl $propsPath $false) -and (Set-OwnerOnlyAcl $fullOut $true)
$aclState = if ($aclOk) { 'ok' } else { 'skipped' }
# END_BLOCK_WRITE_PROPERTIES

Write-Line "result=created alias=$Alias files=2 acl=$aclState backup=REQUIRED"
Write-Output 'Сделайте резервную копию ОБОИХ файлов (ключ .p12 и release-signing.properties) на внешний носитель, см. docs/RELEASE.md.'
exit 0
