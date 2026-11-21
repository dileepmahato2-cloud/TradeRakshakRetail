@echo off
setlocal EnableDelayedExpansion

:: ---------------------------------------------------------------------------
:: Re-invoke once with stderr discarded.
::
:: Windows emits a spurious "The system cannot find the drive specified" on stderr
:: while spawning child processes in this environment. It is cosmetic - the commands
:: succeed, exit codes are correct and the application starts - but it surfaces at
:: unpredictable points and reads like a failure. It was traced to stderr (0 on stdout,
:: 1 on stderr) after silencing every individual spawn failed to remove it.
::
:: Nothing that matters is lost: this script reports through echo (stdout), a failed
:: build is caught by the errorlevel check and reported explicitly, and the application
:: writes its own output to logs\startup.log. Remove TR_STDERR_QUIET to see raw stderr.
:: ---------------------------------------------------------------------------
if not defined TR_STDERR_QUIET (
    set "TR_STDERR_QUIET=1"
    call "%~f0" %* 2>nul
    exit /b %errorlevel%
)

title TradeRakshak TM v11.5
cd /d "%~dp0"
set "APP_DIR=%~dp0"

echo.
echo  ================================================================
echo   TradeRakshak TM v11.5 - Starting...
echo  ================================================================
echo.
echo  CLOSE THIS WINDOW to stop the application.
echo  ================================================================
echo.

:: Find Java (portable first, then system)
set "JAVA_HOME=%APP_DIR%jdk21"
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" if exist "C:\Program Files\Java\jdk-17\bin\java.exe" set "JAVA_EXE=C:\Program Files\Java\jdk-17\bin\java.exe"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"
if not exist "%APP_DIR%jdk21\bin\java.exe" set "JAVA_HOME="

:: Find Maven (downloaded by INSTALL.bat, or on the system PATH)
set "MVN_EXE=%APP_DIR%mvn_home\bin\mvn.cmd"
if not exist "%MVN_EXE%" set "MVN_EXE="
if not defined MVN_EXE where mvn >nul 2>nul && set "MVN_EXE=mvn"

:: Detect whether the code has changed since the last build.
::   exit 0 = JAR is up to date         → run as-is
::   exit 1 = only backend (Java) stale → fast rebuild (reuse Angular build)
::   exit 2 = no JAR / frontend stale   → full rebuild (Angular + backend)
powershell -NoProfile -ExecutionPolicy Bypass -Command "$r='%APP_DIR%'; $jar=Get-ChildItem (Join-Path $r 'backend\target') -Filter 'traderakshak-backend-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*.original' } | Sort-Object Length -Descending | Select-Object -First 1; if(-not $jar){exit 2}; $jt=$jar.LastWriteTime; function Newest($p){$i=foreach($x in $p){if(Test-Path $x){Get-ChildItem $x -Recurse -File -ErrorAction SilentlyContinue}}; if($i){($i | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime}else{[datetime]::MinValue}}; $be=Newest @((Join-Path $r 'backend\src\main\java'),(Join-Path $r 'backend\pom.xml'),(Join-Path $r 'backend\src\main\resources\application.yml'),(Join-Path $r 'backend\src\main\resources\db')); $fe=Newest @((Join-Path $r 'frontend\src'),(Join-Path $r 'frontend\package.json'),(Join-Path $r 'frontend\angular.json')); $dist=Test-Path (Join-Path $r 'frontend\dist\traderakshak-frontend\browser'); if((-not $dist) -or ($fe -gt $jt)){exit 2}; if($be -gt $jt){exit 1}; exit 0" 2>nul
set "BUILD_MODE=%errorlevel%"

if "%BUILD_MODE%"=="0" goto :find_jar

if not defined MVN_EXE (
    if "%BUILD_MODE%"=="2" (
        echo  [!!] Application not built yet and Maven was not found.
        echo       Please run INSTALL.bat first.
    :: Release the start lock on the way out. A failed start used to keep it, so the very
    :: next click was refused with "already in progress" for a fault that had already ended.
    rmdir /s /q "%START_LOCK%" 2>nul
        pause
        exit /b 1
    )
    echo  [!] Code changed but Maven was not found - running the existing build.
    echo      Run INSTALL.bat to rebuild with your latest changes.
    goto :find_jar
)

echo.
if "%BUILD_MODE%"=="1" (
    echo  [..] Backend code changed - rebuilding ^(reusing Angular build^)...
    call "%MVN_EXE%" -f "%APP_DIR%backend\pom.xml" clean package -Dmaven.test.skip=true -Dfrontend.skip=true --no-transfer-progress
) else (
    echo  [..] Code changed - rebuilding application ^(this may take a few minutes^)...
    call "%MVN_EXE%" -f "%APP_DIR%backend\pom.xml" clean package -Dmaven.test.skip=true --no-transfer-progress
)
if errorlevel 1 (
    echo.
    echo  [!!] Build failed. See the messages above.
    echo       You can still try the previous build if one exists.
    echo.
)

:find_jar
:: Find runnable JAR. Prefer the largest jar because Maven also leaves a small
:: .original library jar during Spring Boot repackaging.
set "JAR_FILE="
for /f "delims=" %%f in ('powershell -NoProfile -Command "$j=$null; foreach($f in @(Get-ChildItem -Path '%APP_DIR%backend\target' -Filter 'traderakshak-backend-*.jar' -ErrorAction SilentlyContinue)){ if($f.Name -notlike '*.original' -and (-not $j -or $f.Length -gt $j.Length)){$j=$f} }; if($j){$j.FullName}" 2^>nul') do set "JAR_FILE=%%f"

if not defined JAR_FILE (
    echo  [!!] Application not built yet. Please run INSTALL.bat first.
    :: Release the start lock on the way out. A failed start used to keep it, so the very
    :: next click was refused with "already in progress" for a fault that had already ended.
    rmdir /s /q "%START_LOCK%" 2>nul
    pause
    exit /b 1
)

:: Load settings from .env
set "APP_PORT=8080"
set "JWT_SECRET=TR2026-DefaultKey-ChangeMe"
set "LOG_LEVEL=INFO"
set "DATABASE_URL=jdbc:postgresql://localhost:5432/traderakshak"
set "DB_DRIVER=org.postgresql.Driver"
set "POSTGRES_USER=postgres"
set "POSTGRES_PASSWORD=123456"
if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%a in (".env") do set "%%a=%%b"
)

:: .env spells it PORT, and INSTALL.bat maps it the same way. Without this the
:: port configured in .env was ignored and the app always came up on 8080.
if defined PORT set "APP_PORT=%PORT%"

set "SPRING_DATASOURCE_URL=%DATABASE_URL%"
set "SPRING_DATASOURCE_USERNAME=%POSTGRES_USER%"
set "SPRING_DATASOURCE_PASSWORD=%POSTGRES_PASSWORD%"
set "SPRING_DATASOURCE_DRIVER_CLASS_NAME=%DB_DRIVER%"

:: Refuse to start a second copy. The running JVM still holds logs\startup.log
:: open, so the redirect further down fails with "the process cannot access the
:: file", Java never launches, and the window prints "TradeRakshak stopped" - which
:: reads as START.bat being broken rather than as the app already running.
rem stderr is discarded here as well as stdout. This spawn emits a spurious "The system
rem cannot find the drive specified" on this machine - the command itself is correct
rem (errorlevel is right and the app starts), and only the errorlevel is used below, so
rem nothing real is hidden. It is bounded to this line by marker bisection.

:: A port check alone cannot stop two starts launched seconds apart: for the ~15 seconds
:: between launch and Java binding 8080, neither instance is listening yet, so both pass the
:: check and race. mkdir is atomic on NTFS - exactly one caller can create the directory - so
:: it closes that window. A lock older than 15 minutes is treated as stale, which recovers
:: automatically if a previous run was killed before it could clean up.
(netstat -ano 2>nul) | findstr /R /C:":%APP_PORT% .*LISTENING" >nul 2>&1
if not errorlevel 1 (
    echo.
    echo  ============================================================
    echo    TradeRakshak is ALREADY RUNNING on port %APP_PORT%
    echo.
    echo    Open it here:  http://localhost:%APP_PORT%
    echo.
    echo.
    echo    To RESTART instead, close this window, run STOP.bat, then START.bat.
    echo  ============================================================
    echo.
    :: Clicking START and getting only a refusal reads as "Start is broken". The app
    :: is up, so do the thing the click was actually asking for and open it.
    start "" /b powershell -NoProfile -WindowStyle Hidden -Command "Start-Process 'http://localhost:%APP_PORT%'" >nul 2>&1
    echo    Opening it in your browser...
    echo.
    :: Wait, but never indefinitely.
    ::
    :: This was a bare "pause". Double-clicked that is right - the message needs reading - but
    :: started from a script or a scheduler, with a console attached and nothing to type into it,
    :: pause blocks forever: the window sits holding the message while nothing runs, which looks
    :: exactly like a start that hung. timeout returns on its own, so an unattended start always
    :: finishes. When stdin is redirected timeout refuses to run at all, and the fallback pause
    :: reads EOF and returns immediately, which is the behaviour wanted there too.
    timeout /t 15 /nobreak >nul 2>&1
    if errorlevel 1 pause
    exit /b 0
)

:: Checked AFTER the already-running test above, deliberately. A lock left behind by a start
:: that never cleaned up (window closed, machine slept, a shell job killed) must not stop us
:: opening a browser at an app that is plainly running - that made START look broken when all
:: the click needed was the browser. The lock guards only the race between two FRESH starts.
set "START_LOCK=%APP_DIR%runtime-tmp\.starting"
if not exist "%APP_DIR%runtime-tmp" mkdir "%APP_DIR%runtime-tmp" 2>nul
mkdir "%START_LOCK%" 2>nul
if errorlevel 1 (
    for /f %%A in ('powershell -NoProfile -Command "$d=Get-Item '%START_LOCK%' -EA SilentlyContinue; if($d -and ((Get-Date)-$d.CreationTime).TotalMinutes -lt 15){'BUSY'}else{'STALE'}" 2^>nul') do set "LOCK_STATE=%%A"
    if "!LOCK_STATE!"=="BUSY" (
        echo.
        echo  ============================================================
        echo    Another TradeRakshak start is already in progress.
        echo.
        echo    Wait for it to finish, then open  http://localhost:%APP_PORT%
        echo    If nothing starts, delete this folder and try again:
        echo      %START_LOCK%
        echo  ============================================================
        echo.
        pause
        exit /b 0
    )
    echo  [..] Clearing a stale start lock from an earlier run.
    rmdir /s /q "%START_LOCK%" 2>nul
    mkdir "%START_LOCK%" 2>nul
)


:: PostgreSQL readiness, tablespace and database all live in db-setup.bat so
:: INSTALL.bat and START.bat cannot drift apart again.
call "%~dp0db-setup.bat"
if errorlevel 1 (
    :: Release the start lock on the way out. A failed start used to keep it, so the very
    :: next click was refused with "already in progress" for a fault that had already ended.
    rmdir /s /q "%START_LOCK%" 2>nul
    pause
    exit /b 1
)

:: Back up before Flyway can alter the schema. Only when a migration file is newer
:: than the newest backup - so an ordinary restart is not slowed by a dump it does
:: not need, but a schema change never runs against an unprotected database.
set "NEED_BACKUP="
for /f %%B in ('powershell -NoProfile -Command "$m=$null; foreach($x in @(Get-ChildItem '%APP_DIR%backend\src\main\resources\db\migration' -Recurse -File -ErrorAction SilentlyContinue)){if(-not $m -or $x.LastWriteTime -gt $m.LastWriteTime){$m=$x}}; $b=$null; foreach($y in @(Get-ChildItem 'E:\TradeRakshak-Backups\traderakshak-*.dump' -ErrorAction SilentlyContinue)){if(-not $b -or $y.LastWriteTime -gt $b.LastWriteTime){$b=$y}}; if($m -and (-not $b -or $m.LastWriteTime -gt $b.LastWriteTime)){'YES'}else{'NO'}"') do set "NEED_BACKUP=%%B"
if "%NEED_BACKUP%"=="YES" (
    echo  [..] Schema change detected - backing up before it is applied.
    call "%~dp0BACKUP-DATABASE.bat" premigration
    if errorlevel 1 (
        echo.
        echo  [!] The pre-migration backup FAILED. Not starting.
        echo      Applying a schema change with no backup is how data gets lost.
        echo      Fix the backup, or start anyway with:  set SKIP_MIGRATION_BACKUP=1
        echo.
        if not "%SKIP_MIGRATION_BACKUP%"=="1" (
            :: Release the start lock on the way out. A failed start used to keep it, so the
            :: very next click was refused with "already in progress" long after the fault ended.
            rmdir /s /q "%START_LOCK%" 2>nul
            pause
            exit /b 1
        )
    )
)

:: JVM heap. Defaults suit a desktop; on a small server set JAVA_HEAP_OPTS in .env,
:: e.g. JAVA_HEAP_OPTS=-Xms160m -Xmx320m -XX:+UseSerialGC -Xss512k
if not defined JAVA_HEAP_OPTS set "JAVA_HEAP_OPTS=-Xms512m -Xmx2048m"

if not exist "%APP_DIR%logs" mkdir "%APP_DIR%logs"
if not exist "%APP_DIR%runtime-tmp" mkdir "%APP_DIR%runtime-tmp"

:: Open the browser once the app has had time to come up.
::
:: This was removed for a while when a stray "The system cannot find the drive specified"
:: was being hunted. That message turned out to be netstat's stderr inside a pipeline
:: (where "2>&1" binds to findstr, not netstat), which is fixed at the source now - and
:: this script also discards stderr wholesale via TR_STDERR_QUIET. So the launcher is
:: safe to run again, and clicking START does what it looks like it should do.
start "" /b powershell -NoProfile -WindowStyle Hidden -Command "Start-Sleep -Seconds 25; Start-Process 'http://localhost:%APP_PORT%'" >nul 2>&1

:: Launch ??? Java runs in foreground so closing this window stops the app
echo.
echo  Open browser at:  http://localhost:%APP_PORT%
echo  Login: superadmin@traderakshak.local / Admin@123
echo  Startup log: %APP_DIR%logs\startup.log
echo.
echo  Candles:    1 hour, bars start 09:15 10:15 ... 15:15 ^(livecandle.interval.minutes^)
echo  Trade legs: BUY 15:15  ^|  SELL 10:15 the next trading day
echo              Both times are bar starts - they must stay on the hour from 09:15,
echo              or the price lookup finds no candle and the trade is skipped.
echo  Stopping:   use STOP.bat, or close this window. Either way the open candle is
echo              saved on the way out; Task Manager End-task loses up to an hour.
echo.
echo  ================================================================
echo   CLOSE THIS WINDOW = STOP THE APPLICATION
echo  ================================================================
echo.
"%JAVA_EXE%" %JAVA_HEAP_OPTS% ^
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED ^
  -Djava.net.preferIPv4Stack=true ^
  -Djava.io.tmpdir="%APP_DIR%runtime-tmp" ^
  -Dspring.profiles.active=dev ^
  -DPORT=%APP_PORT% ^
  -DJWT_SECRET=%JWT_SECRET% ^
  -DDATABASE_URL=%DATABASE_URL% ^
  -DPOSTGRES_USER=%POSTGRES_USER% ^
  -DPOSTGRES_PASSWORD=%POSTGRES_PASSWORD% ^
  -DDB_DRIVER=%DB_DRIVER% ^
  "-Dspring.datasource.url=%DATABASE_URL%" ^
  "-Dspring.datasource.username=%POSTGRES_USER%" ^
  "-Dspring.datasource.password=%POSTGRES_PASSWORD%" ^
  "-Dspring.datasource.driver-class-name=%DB_DRIVER%" ^
  -Dspring.flyway.enabled=true ^
  -DCORS_ORIGINS=http://localhost:%APP_PORT% ^
  -jar "%JAR_FILE%" > "%APP_DIR%logs\startup.log" 2>&1

if errorlevel 1 (
    echo.
    echo  [!!] TradeRakshak could not start. See logs\startup.log
    echo.
    echo  Last startup details:
    powershell -NoProfile -Command "Get-Content -Path '%APP_DIR%logs\startup.log' -Tail 40"
    echo.
    echo  If the log says "Unable to establish loopback connection",
    echo  Windows networking/Winsock is blocking Java local server startup.
    echo  Open Command Prompt as Administrator, run:
    echo      netsh winsock reset
    echo  Then restart Windows and run START.bat again.
)

echo.
rmdir /s /q "%START_LOCK%" 2>nul
echo  ================================================================
echo   TradeRakshak stopped. Your data is saved.
echo  ================================================================
echo.
pause
