[CmdletBinding()]
param([string]$NdkPath = "", [string]$AppPath = "")
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
if (-not $AppPath) { $AppPath = Join-Path $root 'app' }
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

Write-Host "Saturn core prepared (Aether + URnetwork proxy chain v3.0.0)."
