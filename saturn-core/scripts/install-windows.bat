@echo off
setlocal
echo ========================================================
echo        Saturn Client Windows One-Click Installer
echo ========================================================
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-windows.ps1"
pause
