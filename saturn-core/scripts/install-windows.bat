@echo off
setlocal
echo ========================================================
echo        Saturn Client Windows One-Click Installer
echo ========================================================

if not exist "%~dp0install-windows.ps1" (
    echo Downloading installer script via CDN...
    curl.exe -fsSL "https://cdn.jsdelivr.net/gh/dashbehrad/Saturn-core@v1.0.2/scripts/install-windows.ps1" -o "%TEMP%\saturn_install.ps1"
    powershell -NoProfile -ExecutionPolicy Bypass -File "%TEMP%\saturn_install.ps1"
    del /f /q "%TEMP%\saturn_install.ps1" 2>nul
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-windows.ps1"
)
pause
