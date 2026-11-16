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
:: forced kill at 14:50 throws away the entire 14:15 bar for every tracked symbol.
::
:: This used to be "taskkill /PID without /F", on the understanding that this is the polite form
:: and /F is the forced one. It is - but what it actually does is post WM_CLOSE to windows OWNED
:: by that process, and a JVM started as "java -jar" owns none. Measured on a running instance:
::
::     PID 18708   MainWindowHandle : 0   MainWindowTitle : ''
::
:: So the request reached nothing. Every stop waited the full 15 seconds, reported the JVM as
:: wedged and forced the kill - six times out of six in one session - and the @PreDestroy flush
:: never ran. Closing the START window did work, because that sends CTRL_CLOSE_EVENT to the
:: console, which is why only this path was losing candles.
::
:: The application now watches for this file and shuts down in an orderly way when it appears
:: (ShutdownTriggerWatcher). No port, no endpoint, and it works whether the app was started from
:: a console window or headlessly.
set "TRIGGER_DIR=%~dp0runtime-tmp"
if not exist "%TRIGGER_DIR%" mkdir "%TRIGGER_DIR%" 2>nul
echo stop-requested> "%TRIGGER_DIR%\.shutdown"

echo Asking TradeRakshak to close and save open candles...

:: The watcher polls once a second, so detection costs about a second; the rest of this budget is
:: the flush itself. 20 seconds covers a full session's open bars.
set "CLOSED="
for /l %%i in (1,1,20) do (
    if not defined CLOSED (
        ping -n 2 127.0.0.1 >nul 2>&1
        call :checkgone
        if defined GONE set "CLOSED=1"
    )
)

:: Fallback for a build that predates the watcher, or one that is too wedged to poll. Harmless
:: where the trigger already worked, because by then there is no process left to signal.
if not defined CLOSED (
    echo  [..] No response to the stop request - asking the process directly...
    for %%p in (%PIDS%) do taskkill /PID %%p >nul 2>&1
    for /l %%i in (1,1,5) do (
        if not defined CLOSED (
            ping -n 2 127.0.0.1 >nul 2>&1
            call :checkgone
            if defined GONE set "CLOSED=1"
        )
    )
)

if defined CLOSED (
    echo Closed cleanly - open candles were saved.
) else (
    echo  [!] Still running - forcing it. An in-progress candle may be lost.
    for %%p in (%PIDS%) do taskkill /PID %%p /F >nul 2>&1
)

:: Always clear the trigger, whichever way the app went down. A leftover would be ignored as
:: stale by the watcher, but removing it here keeps the runtime directory honest.
del /q "%TRIGGER_DIR%\.shutdown" 2>nul

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
