# ==============================================================================
# Saturn Client - One-Click Installer for Windows
# Repository: https://github.com/dashbehrad/Saturn-core
# ==============================================================================

try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
} catch {}

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "    Saturn Client Windows One-Click Installer (Admin)     " -ForegroundColor Cyan
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
    "https://github.com/dashbehrad/Saturn-core/releases/download/v1.0.0/saturn-windows-$arch.zip",
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
    if (Test-Path "saturn-client.exe") {
        Copy-Item -Path "saturn-client.exe" -Destination $binPath -Force
    } elseif (Test-Path "..\build\saturn-client-windows-$arch.exe") {
        Copy-Item -Path "..\build\saturn-client-windows-$arch.exe" -Destination $binPath -Force
    } elseif (Test-Path "..\bin\saturn-client.exe") {
        Copy-Item -Path "..\bin\saturn-client.exe" -Destination $binPath -Force
    } else {
        Write-Error "Failed to locate saturn-client.exe binary."
        exit 1
    }
}

Write-Host "[+] Saturn Client binary installed to: $binPath" -ForegroundColor Green

# 3. Check Clipboard or prompt for Saturn URI
$clipboard = ""
try {
    $clipboard = (Get-Clipboard -Raw)
} catch {}

$saturnUri = ""
if ($clipboard -and ($clipboard -match "(saturn://[^\s`"'\r\n]+)")) {
    $saturnUri = $Matches[1].Trim()
    Write-Host "[+] Detected saturn:// link in clipboard!" -ForegroundColor Green
}

if (-not $saturnUri) {
    Write-Host "`nPlease paste your saturn:// connection link (or press Enter to configure later):" -ForegroundColor Cyan
    $rawInput = Read-Host
    if ($rawInput -and ($rawInput -match "(saturn://[^\s`"'\r\n]+)")) {
        $saturnUri = $Matches[1].Trim()
    }
}

# 4. Create launcher batch script with Admin Self-Elevation
$batPath = "$installDir\start-saturn.bat"
$uriPath = "$installDir\connection.uri"

$elevateHeader = "@echo off`r`nnet session >nul 2>&1`r`nif %errorlevel% neq 0 (`r`n    powershell -NoProfile -ExecutionPolicy Bypass -Command `"Start-Process -FilePath '%~f0' -Verb RunAs`"`r`n    exit /b`r`n)`r`ntitle Saturn Client Proxy (Admin)`r`ncd /d `"%~dp0`"`r`ntaskkill /f /im saturn-client.exe >nul 2>&1`r`necho ==========================================================`r`necho        Saturn Client SOCKS5/HTTP Proxy (Admin)`r`necho ==========================================================`r`necho Local Proxy Address: 127.0.0.1:10809`r`necho Windows System Proxy is automatically managed by Saturn.`r`necho Starting Saturn Client Proxy...`r`necho.`r`n"

if ($saturnUri -and $saturnUri.StartsWith("saturn://")) {
    Set-Content -Path $uriPath -Value $saturnUri -Encoding ASCII
    $batContent = $elevateHeader + "`"$binPath`" -u `"$saturnUri`" -l 127.0.0.1:10809`r`nreg add `"HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings`" /v ProxyEnable /t REG_DWORD /d 0 /f >nul"
    Set-Content -Path $batPath -Value $batContent -Encoding ASCII
} else {
    $batContent = $elevateHeader + "`"$binPath`" -l 127.0.0.1:10809`r`nreg add `"HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings`" /v ProxyEnable /t REG_DWORD /d 0 /f >nul"
    Set-Content -Path $batPath -Value $batContent -Encoding ASCII
}

# 5. Create Desktop Shortcut (With Run as Administrator Flag SLDF_RUNAS_USER 0x20)
$desktopPath = [System.Environment]::GetFolderPath([System.Environment+SpecialFolder]::Desktop)
if (-not $desktopPath -or -not (Test-Path $desktopPath)) {
    $desktopPath = [System.IO.Path]::Combine($env:USERPROFILE, "Desktop")
}

$shortcutCreated = $false
if (Test-Path $desktopPath) {
    try {
        $shortcutPath = [System.IO.Path]::Combine($desktopPath, "Saturn Client.lnk")
        $WScriptShell = New-Object -ComObject WScript.Shell
        $Shortcut = $WScriptShell.CreateShortcut($shortcutPath)
        $Shortcut.TargetPath = $batPath
        $Shortcut.WorkingDirectory = $installDir
        $Shortcut.Description = "Start Saturn Stealth Proxy Client (Admin)"
        $Shortcut.Save()

        # Set Run as Administrator flag (0x20 bit at byte offset 0x15 of .lnk)
        try {
            $bytes = [System.IO.File]::ReadAllBytes($shortcutPath)
            $bytes[0x15] = $bytes[0x15] -bor 0x20
            [System.IO.File]::WriteAllBytes($shortcutPath, $bytes)
        } catch {}

        $shortcutCreated = $true
        Write-Host "[+] Desktop shortcut created (Run As Admin): $shortcutPath" -ForegroundColor Green
    } catch {
        Write-Host "[!] Note: Could not create desktop shortcut automatically: $_" -ForegroundColor Yellow
    }
}

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "       Saturn Client successfully installed on Windows!   " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
if ($shortcutCreated) {
    Write-Host "1. A shortcut 'Saturn Client' (Admin) has been placed on your Desktop." -ForegroundColor Cyan
} else {
    Write-Host "1. Launcher script created at: $batPath" -ForegroundColor Cyan
}
Write-Host "2. Double-click it anytime to start your proxy." -ForegroundColor Cyan
Write-Host "3. It will automatically Run as Administrator and enable Windows System Proxy." -ForegroundColor Cyan
Write-Host "4. Proxy is active on 127.0.0.1:10809 (System Proxy automatically managed)." -ForegroundColor Cyan
Write-Host "==========================================================`n" -ForegroundColor Green
