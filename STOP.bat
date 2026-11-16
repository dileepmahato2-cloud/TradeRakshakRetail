@echo off
echo Stopping TradeRakshak TM...
cd /d "%~dp0"
set "APP_PORT=8080"
if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
        :: .env spells it PORT; APP_PORT is kept for older .env files.
        if /i "%%a"=="PORT"     set "APP_PORT=%%b"
        if /i "%%a"=="APP_PORT" set "APP_PORT=%%b"
    )
)

:: Collect every PID listening on the port before killing any of them - the list
:: cannot be re-read afterwards, and IPv4/IPv6 can both be bound.
set "FOUND="
set "PIDS="
for /f "tokens=5" %%p in ('netstat -ano 2^>nul ^| findstr /R /C:":%APP_PORT% .*LISTENING"') do (
    set "FOUND=1"
    call :addpid %%p
)

if not defined FOUND (
    echo TradeRakshak is not running on port %APP_PORT%.
    pause
    exit /b 0
)

:: Ask the JVM to close before forcing it.
::
:: Why this step exists: LiveCandleAggregator keeps the bar it is currently building in memory and
:: only writes it when the bucket closes. Bars used to be 5 minutes wide, so a taskkill /F cost at
:: most five minutes of ticks. They are now 60 minutes wide (livecandle.interval.minutes), so a
:: forced kill at 14:50 throws away the entire 14:15 bar for every tracked symbol. taskkill WITHOUT
:: /F sends a close request the JVM turns into an orderly shutdown, which runs the @PreDestroy
:: flush in LiveCandleAggregator and writes the open bars out first.
::
:: /F still follows, because a graceful request is a request: a wedged JVM would otherwise leave the
:: port held and the next build unable to delete the JAR.
echo Asking TradeRakshak to close and save open candles...
for %%p in (%PIDS%) do taskkill /PID %%p >nul 2>&1

:: Give the shutdown hook time to finish its write. 15 seconds covers the flush of a full session's
:: open bars; past that, assume it is not coming back.
set "CLOSED="
for /l %%i in (1,1,15) do (
    if not defined CLOSED (
        ping -n 2 127.0.0.1 >nul 2>&1
        call :checkgone
        if defined GONE set "CLOSED=1"
    )
)

if defined CLOSED (
    echo Closed cleanly - open candles were saved.
) else (
    echo  [!] Still running after 15s - forcing it. An in-progress candle may be lost.
    for %%p in (%PIDS%) do taskkill /PID %%p /F >nul 2>&1
)

:: Wait for the PROCESS to exit, not just for the port to close. Windows releases
:: the listening socket before the JVM has finished shutting down, so the old
:: port-only check reported "Stopped" while java still held its files - which then
:: made the next Maven build fail because it could not delete the JAR.
set "GONE="
for /l %%i in (1,1,30) do (
    if not defined GONE (
        ping -n 2 127.0.0.1 >nul 2>&1
        call :checkgone
    )
)

:: START.bat takes an atomic lock while it launches and releases it after Java exits.
:: Killing Java here means START.bat never reaches that line, so the lock would linger
:: for its full staleness window and the next start would refuse. Clear it as part of
:: stopping - this is the moment we know nothing is starting.
rmdir /s /q "%~dp0runtime-tmp\.starting" 2>nul

if defined GONE (
    echo Stopped. Your data is saved.
) else (
    echo  [!] A java process is still running after 30 seconds.
    echo      Close the START window manually, or check Task Manager.
)
pause
exit /b 0

:addpid
:: Skip duplicates so the same PID is not killed twice.
echo %PIDS% | findstr /C:" %1 " >nul 2>&1
if errorlevel 1 set "PIDS=%PIDS% %1 "
exit /b 0

:checkgone
:: Gone means: nothing listening on the port AND none of the killed PIDs alive.
(netstat -ano 2>nul) | findstr /R /C:":%APP_PORT% .*LISTENING" >nul 2>&1
if not errorlevel 1 exit /b 0
for %%q in (%PIDS%) do (
    tasklist /FI "PID eq %%q" 2>nul | findstr /C:"%%q" >nul 2>&1
    if not errorlevel 1 exit /b 0
)
set "GONE=1"
exit /b 0
