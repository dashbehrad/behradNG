# ==============================================================================
# Saturn Client - One-Click Installer for Windows
# Repository: https://github.com/dashbehrad/Saturn-core
# ==============================================================================

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "         Saturn Client Windows One-Click Installer        " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. Detect Architecture
$arch = if ([System.Environment]::Is64BitOperatingSystem) {
    if ($env:PROCESSOR_ARCHITECTURE -eq "ARM64") { "arm64" } else { "amd64" }
} else {
    Write-Error "Unsupported 32-bit Windows system."
    exit 1
}

$installDir = "$env:LOCALAPPDATA\Saturn"
$binPath = "$installDir\saturn-client.exe"
if (!(Test-Path $installDir)) {
    New-Item -ItemType Directory -Path $installDir -Force | Out-Null
}

# 2. Download latest binary (with multi-mirror fallback for Iran / restricted networks)
Write-Host "[*] Fetching latest release for windows-$arch..." -ForegroundColor Yellow

$downloadUrls = @(
    "https://github.com/dashbehrad/Saturn-core/releases/latest/download/saturn-windows-$arch.zip",
    "https://ghproxy.net/https://github.com/dashbehrad/Saturn-core/releases/latest/download/saturn-windows-$arch.zip",
    "https://github.moeyy.xyz/https://github.com/dashbehrad/Saturn-core/releases/latest/download/saturn-windows-$arch.zip",
    "https://mirror.ghproxy.com/https://github.com/dashbehrad/Saturn-core/releases/latest/download/saturn-windows-$arch.zip"
)

$downloaded = $false
$tempZip = "$env:TEMP\saturn-windows-$arch.zip"

foreach ($zipUrl in $downloadUrls) {
    try {
        Write-Host "[*] Trying download: $zipUrl" -ForegroundColor DarkCyan
        if (Get-Command curl.exe -ErrorAction SilentlyContinue) {
            & curl.exe -fsSL -m 35 "$zipUrl" -o "$tempZip"
        } else {
            Invoke-WebRequest -Uri $zipUrl -OutFile $tempZip -UseBasicParsing -TimeoutSec 35
        }

        if ((Test-Path $tempZip) -and ((Get-Item $tempZip).Length -gt 100000)) {
            Write-Host "[+] Download complete! Extracting..." -ForegroundColor Green
            $extractDir = "$env:TEMP\saturn_ext_$([System.IO.Path]::GetRandomFileName())"
            Expand-Archive -Path $tempZip -DestinationPath $extractDir -Force

            $extractedExe = Get-ChildItem -Path $extractDir -Filter "saturn-client.exe" -Recurse | Select-Object -First 1
            if ($extractedExe) {
                Copy-Item -Path $extractedExe.FullName -Destination $binPath -Force
                $downloaded = $true
            }
            Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item $tempZip -Force -ErrorAction SilentlyContinue
            if ($downloaded) { break }
        }
    } catch {
        Write-Host "[!] Mirror failed: $_" -ForegroundColor DarkGray
    }
}

if (!(Test-Path $binPath)) {
    # Check if saturn-client.exe exists in current directory or build folder
    if (Test-Path "saturn-client.exe") {
        Copy-Item -Path "saturn-client.exe" -Destination $binPath -Force
    } elseif (Test-Path "..\build\saturn-client-windows-$arch.exe") {
        Copy-Item -Path "..\build\saturn-client-windows-$arch.exe" -Destination $binPath -Force
    } else {
        Write-Error "Failed to locate saturn-client.exe binary."
        exit 1
    }
}

Write-Host "[+] Saturn Client binary installed to: $binPath" -ForegroundColor Green

# 3. Check Clipboard or prompt for Saturn URI
$clipboard = ""
try {
    $clipboard = Get-Clipboard
} catch {}

$saturnUri = ""
if ($clipboard -and $clipboard.StartsWith("saturn://")) {
    Write-Host "[+] Detected saturn:// link in clipboard!" -ForegroundColor Green
    $saturnUri = $clipboard.Trim()
}

if (-not $saturnUri) {
    Write-Host "`nPlease paste your saturn:// connection link (or press Enter to configure later):" -ForegroundColor Cyan
    $saturnUri = Read-Host
}

# 4. Create launcher batch script
$batPath = "$installDir\start-saturn.bat"
if ($saturnUri -and $saturnUri.StartsWith("saturn://")) {
    Set-Content -Path $batPath -Value "@echo off`r`ntitle Saturn Client Proxy`r`necho Starting Saturn Client...`r`nstart `"`" `"$binPath`" -u `"$saturnUri`" -l 127.0.0.1:10808`r`necho Saturn SOCKS5/HTTP Proxy is running on 127.0.0.1:10808`r`npause"
} else {
    Set-Content -Path $batPath -Value "@echo off`r`ntitle Saturn Client Proxy`r`ncd /d `"$installDir`"`r`n`"$binPath`"`r`npause"
}

# 5. Create Desktop Shortcut
$desktopPath = [System.Environment]::GetFolderPath([System.Environment+SpecialFolder]::Desktop)
$shortcutPath = "$desktopPath\🪐 Saturn Client.lnk"

$WScriptShell = New-Object -ComObject WScript.Shell
$Shortcut = $WScriptShell.CreateShortcut($shortcutPath)
$Shortcut.TargetPath = $batPath
$Shortcut.WorkingDirectory = $installDir
$Shortcut.Description = "Start Saturn Stealth Proxy Client"
$Shortcut.Save()

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "       Saturn Client successfully installed on Windows!   " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
Write-Host "1. A shortcut '🪐 Saturn Client' has been placed on your Desktop." -ForegroundColor Cyan
Write-Host "2. Double-click it anytime to start your proxy on 127.0.0.1:10808." -ForegroundColor Cyan
Write-Host "3. Set your browser or Telegram proxy to SOCKS5 127.0.0.1:10808." -ForegroundColor Cyan
Write-Host "==========================================================`n" -ForegroundColor Green
