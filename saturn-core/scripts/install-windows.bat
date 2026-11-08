@echo off
net session >nul 2>&1
if %errorlevel% neq 0 (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
setlocal
cd /d "%~dp0"
echo ========================================================
echo        Saturn Client Windows One-Click Installer (Admin)
echo ========================================================

if not exist "%~dp0install-windows.ps1" (
    echo Downloading installer script via CDN...
    curl.exe -fsSL "https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@v1.0/scripts/install-windows.ps1" -o "%TEMP%\saturn_install.ps1"
    powershell -NoProfile -ExecutionPolicy Bypass -File "%TEMP%\saturn_install.ps1"
    del /f /q "%TEMP%\saturn_install.ps1" 2>nul
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-windows.ps1"
)
pause
