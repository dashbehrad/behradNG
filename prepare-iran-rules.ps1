[CmdletBinding()]
param([string]$AppPath = "")
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
if (-not $AppPath) { $AppPath = Join-Path $root 'V2rayNG/app' }
$lock = Get-Content -Raw -LiteralPath (Join-Path $root 'iran-rules.lock.json') | ConvertFrom-Json
$taskTemp = Join-Path ([IO.Path]::GetTempPath()) ('behradng-geo-' + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $taskTemp | Out-Null
try {
    foreach ($name in @('geoip.dat', 'geosite.dat')) {
        $download = Join-Path $taskTemp $name
        $url = "https://raw.githubusercontent.com/$($lock.repository)/$($lock.revision)/$name"
        & curl.exe --fail --location --retry 3 $url --output $download
        if ($LASTEXITCODE -ne 0) { throw "Iran rules download failed: $name" }
        $expected = $lock.files.PSObject.Properties[$name].Value
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $download).Hash.ToLowerInvariant() -ne $expected) {
            throw "Iran rules checksum mismatch: $name"
        }
    }
    $license = Join-Path $root 'V2rayNG/app/src/main/assets/third_party/IRAN_RULES_LICENSE.txt'
    if ((Get-FileHash -Algorithm SHA256 -LiteralPath $license).Hash.ToLowerInvariant() -ne $lock.licenseSha256) {
        throw 'Iran rules license checksum mismatch'
    }
    $assets = Join-Path $AppPath 'src/main/assets'
    $attribution = Join-Path $assets 'third_party'
    New-Item -ItemType Directory -Path $attribution -Force | Out-Null
    foreach ($name in @('geoip.dat', 'geosite.dat')) {
        Copy-Item -LiteralPath (Join-Path $taskTemp $name) -Destination (Join-Path $assets $name) -Force
    }
    $targetLicense = Join-Path $attribution 'IRAN_RULES_LICENSE.txt'
    if ([IO.Path]::GetFullPath($license) -ne [IO.Path]::GetFullPath($targetLicense)) {
        Copy-Item -LiteralPath $license -Destination $targetLicense -Force
    }
} finally {
    # Only remove the uniquely created temporary directory, after checking its parent.
    $resolved = [IO.Path]::GetFullPath($taskTemp)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if ([IO.Path]::GetDirectoryName($resolved).TrimEnd([IO.Path]::DirectorySeparatorChar) -ne $expectedParent) {
        throw 'Unexpected Iran rules temporary directory'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
