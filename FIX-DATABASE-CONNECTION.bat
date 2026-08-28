@echo off
setlocal EnableExtensions EnableDelayedExpansion
title TradeRakshak - Fix Database Connection

::  FIX-DATABASE-CONNECTION.bat
::  ===========================
::  Recovers PostgreSQL when it is running but refuses every connection with
::  "could not read blocks 0..0 ... Invalid argument".
::
::  What that fault actually is: when the drive holding the data briefly goes
::  away - Modern Standby, the disk sleeping, a USB/external volume dropping -
::  PostgreSQL keeps file handles that are now dead. The files are perfectly
::  readable; the handles are not. So the server stays "Running" while failing
::  every single connection, and any check that cannot connect reports the
::  database as missing. It is not missing. Nothing is corrupted.
::
::  A graceful service restart reopens every handle and fixes it. That is a
::  clean shutdown with a checkpoint - NOT the hard kill of an orphan process
::  that destroyed this database once before.
::
::  This script does not delete, restore, reset or create anything.

:: ---------------------------------------------------------------- elevate
:: Restarting a service needs Administrator. Rather than asking the user to
:: find an admin PowerShell, re-launch this file elevated and let Windows show
:: the normal consent prompt.
net session >nul 2>&1
if errorlevel 1 (
    echo.
    echo  Administrator rights are needed to restart the database service.
    echo  Windows will now ask for permission - choose Yes.
    echo.
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs" >nul 2>&1
    exit /b 0
)

cd /d "%~dp0"
set "SVC=postgresql-x64-18"
set "PSQL="
for %%V in (18 17 16 15) do (
    if not defined PSQL if exist "C:\Program Files\PostgreSQL\%%V\bin\psql.exe" set "PSQL=C:\Program Files\PostgreSQL\%%V\bin\psql.exe"
)

echo.
echo  ================================================================
echo   TradeRakshak - Fix Database Connection
echo  ================================================================
echo.

:: ------------------------------------------------------- before state
echo  [1/4] Checking the current state...
for /f "delims=" %%S in ('powershell -NoProfile -Command "(Get-Service '%SVC%' -EA SilentlyContinue).Status"') do set "BEFORE=%%S"
if "%BEFORE%"=="" (
    echo        Service "%SVC%" not found on this machine.
    echo        Nothing was changed.
    echo.
    pause
    exit /b 1
)
echo        Service is: %BEFORE%

:: ------------------------------------------------------- restart
echo.
echo  [2/4] Restarting the database service gracefully...
echo        (a clean shutdown with a checkpoint - no data is touched)
powershell -NoProfile -Command "try { Restart-Service -Name '%SVC%' -Force -EA Stop; exit 0 } catch { Write-Host ('       ' + $_.Exception.Message); exit 1 }"
if errorlevel 1 (
    echo.
    echo        The restart did not succeed.
    echo        Next step is a REBOOT - that forces every handle closed.
    echo        Do NOT restore a backup to fix this; the data is fine.
    echo.
    pause
    exit /b 1
)
echo        Restart issued.

:: ------------------------------------------------------- wait for ready
echo.
echo  [3/4] Waiting for the database to accept connections...
set "OK="
for /l %%i in (1,1,20) do (
    if not defined OK (
        >nul 2>&1 timeout /t 2 /nobreak
        "%PSQL%" -h localhost -p 5432 -U postgres -d postgres -tAc "SELECT 1" >nul 2>&1
        if not errorlevel 1 set "OK=1"
    )
)
if not defined OK (
    echo        Still refusing connections after 40 seconds.
    echo        REBOOT next. The files are fine - do not restore.
    echo.
    pause
    exit /b 1
)
echo        Connections accepted.

:: ------------------------------------------------------- verify the data
echo.
echo  [4/4] Confirming your data is there...
set "DBFOUND="
for /f "delims=" %%D in ('"%PSQL%" -h localhost -p 5432 -U postgres -d postgres -tAc "SELECT datname FROM pg_database WHERE datname='traderakshak'" 2^>nul') do set "DBFOUND=%%D"
if /i not "%DBFOUND%"=="traderakshak" (
    echo        Connected, but the traderakshak database is NOT listed.
    echo        Stop here and do not start the app. Report this exactly.
    echo.
    pause
    exit /b 1
)
for /f "delims=" %%C in ('"%PSQL%" -h localhost -p 5432 -U postgres -d traderakshak -tAc "SELECT count(*) FROM broker_market_candles" 2^>nul') do set "CANDLES=%%C"
for /f "delims=" %%T in ('"%PSQL%" -h localhost -p 5432 -U postgres -d traderakshak -tAc "SELECT count(*) FROM tracked_stocks" 2^>nul') do set "STOCKS=%%T"
echo        Database  : traderakshak
echo        Candles   : %CANDLES%
echo        Stocks    : %STOCKS%

echo.
echo  ================================================================
echo   FIXED - the database is answering again.
echo  ================================================================
echo.
echo   Your data was never lost. PostgreSQL was holding dead file
echo   handles after the drive slept; restarting reopened them.
echo.
echo   NEXT, in this order:
echo     1. Run BACKUP-DATABASE.bat   (the newest backup is old)
echo     2. Run START.bat
echo.
echo   To stop this happening again, run these two once:
echo     powercfg /change standby-timeout-ac 0
echo     powercfg /change standby-timeout-dc 0
echo.
pause
exit /b 0
