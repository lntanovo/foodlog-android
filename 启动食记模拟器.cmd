@echo off
setlocal EnableExtensions
title FoodLog PC Test
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start-emulator.ps1" %*
set "FOODLOG_EXIT=%ERRORLEVEL%"
if not "%FOODLOG_EXIT%"=="0" pause
exit /b %FOODLOG_EXIT%
