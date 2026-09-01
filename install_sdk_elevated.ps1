# Elevate: install Android SDK into Program Files (AppLocker-allowed location).
$ErrorActionPreference = 'Stop'
$src = 'C:\Windows\Temp\AndroidSdk'
$dst = 'C:\Program Files\Android\Sdk'
Write-Output "Copying SDK from $src to $dst ..."
if (Test-Path $dst) { Remove-Item $dst -Recurse -Force }
New-Item -ItemType Directory -Path $dst -Force | Out-Null
Copy-Item -Path "$src\*" -Destination $dst -Recurse -Force
# Grant current user full control so builds can read/write.
$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls $dst /grant "$user`:(OI)(CI)F" /T | Out-Null
Write-Output "DONE. SDK installed at $dst"
