[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$NdkPath, [string]$AppPath = (Join-Path $PSScriptRoot 'app'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'saturn-core.lock.json') -Raw | ConvertFrom-Json
$workPath = Join-Path $PSScriptRoot '.saturn-native'
$assetsPath = Join-Path $AppPath 'src/main/assets'
$noticePath = Join-Path $assetsPath 'third_party/saturn'
New-Item -ItemType Directory -Path $workPath, $noticePath, (Join-Path $assetsPath 'saturn') -Force | Out-Null
function Invoke-Native([string]$Tool, [string[]]$Arguments) {
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Saturn build tool failed: $Tool" }
}
function Checkout-Source([string]$Url, [string]$Revision, [string]$Destination) {
    if (!(Test-Path -LiteralPath (Join-Path $Destination '.git'))) {
        Invoke-Native 'git.exe' @('clone', '--no-checkout', $Url, $Destination)
    }
    Invoke-Native 'git.exe' @('-C', $Destination, 'fetch', 'origin', $Revision)
    Invoke-Native 'git.exe' @('-C', $Destination, 'checkout', '--detach', $Revision)
    $actual = (& git.exe -C $Destination rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $actual -ne $Revision) { throw 'Saturn source revision mismatch.' }
}
$torSource = Join-Path $workPath 'tor-android'
$obfsSource = Join-Path $workPath 'lyrebird'
$cfSource = Join-Path $workPath 'cloudflared'
Checkout-Source 'https://github.com/guardianproject/tor-android.git' $lock.tor.sourceRevision $torSource
Invoke-Native 'git.exe' @('-C', $torSource, 'submodule', 'update', '--init', '--recursive')
$actualTor = (& git.exe -C (Join-Path $torSource 'external/tor') rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $actualTor -ne $lock.tor.torRevision) { throw 'Embedded Tor source revision mismatch.' }
Checkout-Source $lock.obfs4.repository $lock.obfs4.sourceRevision $obfsSource
Checkout-Source $lock.cloudflared.repository $lock.cloudflared.sourceRevision $cfSource
foreach ($moduleFile in @('go.mod', 'go.sum')) {
    Copy-Item -LiteralPath (Join-Path $obfsSource $moduleFile) -Destination $noticePath -Force
}
$aarPath = Join-Path $workPath 'tor.aar'
$aarUrl = "https://raw.githubusercontent.com/guardianproject/gpmaven/$($lock.tor.mavenRevision)/info/guardianproject/tor-android/$($lock.tor.version)/tor-android-$($lock.tor.version).aar"
Invoke-Native 'curl.exe' @('--fail', '--location', '--retry', '3', $aarUrl, '--output', $aarPath)
if ((Get-FileHash -LiteralPath $aarPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $lock.tor.sha256) { throw 'Tor AAR checksum mismatch.' }
foreach ($geoFile in @('geoip', 'geoip6')) {
    Copy-Item -LiteralPath (Join-Path $torSource "external/tor/src/config/$geoFile") -Destination (Join-Path $assetsPath "saturn/$geoFile") -Force
}
function Copy-Notices([string]$Source, [string]$Destination) {
    New-Item -ItemType Directory -Path $Destination -Force | Out-Null
    Get-ChildItem -LiteralPath $Source -File | Where-Object { $_.Name -match 'LICENSE|COPYING|COPYRIGHT' } |
        ForEach-Object {
            $noticeFile = Join-Path $Destination $_.Name
            Copy-Item -LiteralPath $_.FullName -Destination $noticeFile -Force
            # Do not retain the Go module cache's read-only attribute in generated assets.
            (Get-Item -LiteralPath $noticeFile).IsReadOnly = $false
        }
}
Copy-Notices $torSource (Join-Path $noticePath 'tor-android')
Copy-Notices $obfsSource (Join-Path $noticePath 'lyrebird')
Copy-Notices $cfSource (Join-Path $noticePath 'cloudflared')
Get-ChildItem -LiteralPath (Join-Path $torSource 'external') -Directory | ForEach-Object {
    Copy-Notices $_.FullName (Join-Path $noticePath $_.Name)
}
$toolchain = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$clang = Join-Path $toolchain 'clang.exe'
if (!(Test-Path -LiteralPath $clang)) { throw 'Saturn needs the Windows Android NDK toolchain.' }
$saved = @{}
foreach ($name in @('GOOS', 'GOARCH', 'GOARM', 'CGO_ENABLED', 'CC')) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($aarPath)
try {
    foreach ($abi in $lock.abis) {
        $destination = Join-Path $AppPath "libs/$($abi.abi)"
        New-Item -ItemType Directory -Path $destination -Force | Out-Null
        $entry = $zip.GetEntry("jni/$($abi.abi)/libtor.so")
        if ($null -eq $entry -or $entry.Length -eq 0) { throw "Tor AAR lacks $($abi.abi)" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $destination 'libsaturn-tor.so'), $true)
        $env:GOOS = 'android'; $env:GOARCH = $abi.goarch; $env:CGO_ENABLED = '1'
        $env:GOARM = if ($abi.goarm -eq '-') { $null } else { $abi.goarm }
        $env:CC = '"' + $clang.Replace('\', '/') + '" --target=' + $abi.target + '26'
        Push-Location $obfsSource
        try {
            Invoke-Native 'go.exe' @('mod', 'download')
            $mainPackage = if (Test-Path -LiteralPath 'cmd/lyrebird') { './cmd/lyrebird' } else { './' }
            $packageName = (& go.exe list -f '{{.Name}}' $mainPackage).Trim()
            if ($LASTEXITCODE -ne 0 -or $packageName -ne 'main') { throw 'Lyrebird entry point unavailable.' }
            # Go 1.23+ rejects anet private net.zoneCache linkname; remove the flag when the pinned dependency stops using it.
            Invoke-Native 'go.exe' @('build', '-trimpath', '-buildmode=pie', '-ldflags=-s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384', '-o', (Join-Path $destination 'libsaturn-obfs4.so'), $mainPackage)
            $moduleDirs = @(& go.exe list -m -f '{{.Dir}}' all)
            if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate Lyrebird dependency notices.' }
            $index = 0
            foreach ($module in $moduleDirs) {
                if ($module) { Copy-Notices $module (Join-Path $noticePath "go/$index"); $index++ }
            }
        } finally { Pop-Location }
        Push-Location $cfSource
        try {
            Invoke-Native 'go.exe' @('mod', 'download')
            Invoke-Native 'go.exe' @('build', '-trimpath', '-buildmode=pie', '-ldflags=-s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384', '-o', (Join-Path $destination 'libsaturn-cloudflared.so'), './cmd/cloudflared')
        } finally { Pop-Location }
        Invoke-Native $clang @("--target=$($abi.target)26", '-O2', '-fPIE', '-pie', '-Wl,-z,max-page-size=16384', (Join-Path $PSScriptRoot 'native/saturn-launcher.c'), '-o', (Join-Path $destination 'libsaturn-core.so'))
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'saturn-core.lock.json') -Destination (Join-Path $noticePath 'source-pins.json') -Force
} finally {
    $zip.Dispose()
    foreach ($name in $saved.Keys) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
}
