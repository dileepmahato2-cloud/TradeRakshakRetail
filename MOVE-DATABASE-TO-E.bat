@echo off
::  Double-click this file to move the PostgreSQL database from C: to E:.
::
::  It asks Windows for Administrator rights itself - you only need to click
::  "Yes" on the prompt. Moving a database directory and re-pointing the Windows
::  service both require admin; there is no way around that.

net session >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo  Requesting Administrator rights - click YES on the prompt...
    echo.
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

echo.
echo  Running with Administrator rights.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0MOVE-DATABASE-TO-E.ps1"
echo.
pause
