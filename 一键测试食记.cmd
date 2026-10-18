@echo off
setlocal EnableExtensions
title FoodLog PC Test
if not exist "%~dp0tools\start-emulator.ps1" (
    echo Launcher files are missing. Keep this file inside the android-app folder.
    pause
    exit /b 1
)
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start-emulator.ps1" %*
set "FOODLOG_EXIT=%ERRORLEVEL%"
if not "%FOODLOG_EXIT%"=="0" (
    echo.
    echo FoodLog could not start. See the message above and logs.
    pause
)
exit /b %FOODLOG_EXIT%
