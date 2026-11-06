[CmdletBinding()]
param([string]$NdkPath = "", [string]$AppPath = "")
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
if (-not $AppPath) { $AppPath = Join-Path $root 'V2rayNG/app' }
$lockFile = Join-Path $root 'saturn-core.lock.json'
$lock = Get-Content $lockFile -Raw | ConvertFrom-Json

$assetsPath = Join-Path $AppPath 'src/main/assets'
$noticePath = Join-Path $assetsPath 'third_party/saturn'
New-Item -ItemType Directory -Path $noticePath, (Join-Path $assetsPath 'saturn') -Force | Out-Null
Copy-Item -LiteralPath $lockFile -Destination (Join-Path $noticePath 'source-pins.json') -Force

$workDir = Join-Path $root '.saturn-native'
New-Item -ItemType Directory -Path $workDir -Force | Out-Null

if ($lock.assets) {
    foreach ($asset in $lock.assets) {
        $abi = $asset.abi
        $targetLib = Join-Path $AppPath "libs/$abi/$($asset.library)"
        $targetDir = Join-Path $AppPath "libs/$abi"
        New-Item -ItemType Directory -Path $targetDir -Force | Out-Null

        $alreadyValid = $false
        if (Test-Path -LiteralPath $targetLib) {
            $hash = (Get-FileHash -LiteralPath $targetLib -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($hash -eq $asset.sha256) {
                $alreadyValid = $true
            }
        }

        if (-not $alreadyValid) {
            $cachedLib = Join-Path $workDir $asset.library
            if ((Test-Path -LiteralPath $cachedLib) -and ((Get-FileHash -LiteralPath $cachedLib -Algorithm SHA256).Hash.ToLowerInvariant() -eq $asset.sha256)) {
                Copy-Item -LiteralPath $cachedLib -Destination $targetLib -Force
            } else {
                $archive = Join-Path $workDir $asset.archive
                if (-not (Test-Path -LiteralPath $archive)) {
                    & curl.exe --fail --location --retry 3 --connect-timeout 10 $asset.url --output $archive
                    if ($LASTEXITCODE -ne 0) { throw "Failed to download $($asset.archive)" }
                }
                & tar.exe -xf $archive -C $workDir $asset.entry
                if ($LASTEXITCODE -ne 0) { throw "Failed to extract $($asset.entry) from $($asset.archive)" }
                $extracted = Join-Path $workDir $asset.entry
                Copy-Item -LiteralPath $extracted -Destination $targetLib -Force
            }
        }

        $finalHash = (Get-FileHash -LiteralPath $targetLib -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($finalHash -ne $asset.sha256) {
            throw "Checksum mismatch for $($asset.library): expected $($asset.sha256), got $finalHash"
        }
    }
}

$saturnCoreDir = Join-Path $root 'saturn-core'
$goExe = $null
if (Get-Command go -ErrorAction SilentlyContinue) {
    $goExe = "go"
} elseif (Test-Path -LiteralPath "C:\Program Files\Go\bin\go.exe") {
    $goExe = "C:\Program Files\Go\bin\go.exe"
}

if ($goExe -and (Test-Path -LiteralPath $saturnCoreDir)) {
    Write-Host "Compiling saturn-core native binaries..."
    Push-Location $saturnCoreDir
    try {
        $binDir = Join-Path $root 'bin'
        New-Item -ItemType Directory -Path $binDir -Force | Out-Null
        $assetsSaturn = Join-Path $AppPath 'src/main/assets/saturn'
        New-Item -ItemType Directory -Path $assetsSaturn -Force | Out-Null

        $env:CGO_ENABLED = "0"

        $env:GOOS = "linux"
        $env:GOARCH = "amd64"
        & $goExe build -trimpath -ldflags="-s -w" -o (Join-Path $binDir "saturn-server-linux-amd64") ./cmd/saturn-server
        Copy-Item -LiteralPath (Join-Path $binDir "saturn-server-linux-amd64") -Destination (Join-Path $assetsSaturn "saturn-server-amd64") -Force

        $env:GOOS = "linux"
        $env:GOARCH = "arm64"
        & $goExe build -trimpath -ldflags="-s -w" -o (Join-Path $binDir "saturn-server-linux-arm64") ./cmd/saturn-server
        Copy-Item -LiteralPath (Join-Path $binDir "saturn-server-linux-arm64") -Destination (Join-Path $assetsSaturn "saturn-server-arm64") -Force

        $env:GOOS = "windows"
        $env:GOARCH = "amd64"
        & $goExe build -trimpath -ldflags="-s -w" -o (Join-Path $binDir "saturn-server-windows-amd64.exe") ./cmd/saturn-server
        & $goExe build -trimpath -ldflags="-s -w" -o (Join-Path $binDir "saturn-client-windows-amd64.exe") ./cmd/saturn-client
    } finally {
        Pop-Location
    }
}

$installerSource = Join-Path $root 'install-saturn.sh'
$installerDest = Join-Path $AppPath 'src/main/assets/saturn/install-saturn.sh'
if (Test-Path -LiteralPath $installerSource) {
    Copy-Item -LiteralPath $installerSource -Destination $installerDest -Force
}

Write-Host "Saturn core prepared (Saturn Direct Anti-DPI Protocol)."
