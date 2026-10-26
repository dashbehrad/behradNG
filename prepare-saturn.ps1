[CmdletBinding()]
param([string]$NdkPath = "", [string]$AppPath = "")
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
if (-not $AppPath) { $AppPath = Join-Path $root 'app' }
$assetsPath = Join-Path $AppPath 'src/main/assets'
$noticePath = Join-Path $assetsPath 'third_party/saturn'
New-Item -ItemType Directory -Path $noticePath, (Join-Path $assetsPath 'saturn') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'saturn-core.lock.json') -Destination (Join-Path $noticePath 'source-pins.json') -Force
Write-Host "Saturn core prepared (Aether + URnetwork proxy chain v3.0.0)."
