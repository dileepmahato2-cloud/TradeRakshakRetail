@echo off
setlocal enabledelayedexpansion
title TradeRakshak v11.5 - No Docker Installer

if "%1"=="RUNNING" goto :main
cmd /k "%~f0" RUNNING
exit

:main
cls
echo.
echo  ================================================================
echo   TradeRakshak TM v11.5 - Smart Installer (No Docker Required)
echo   Protecting Capital. Powering Decisions. Creating Wealth.
echo  ================================================================
echo.
echo  For educational purposes only. Not SEBI-registered advice.
echo.

cd /d "%~dp0"
set "APP_DIR=%CD%"
echo  Working folder: %APP_DIR%
echo.

:: ── CREATE FOLDERS ─────────────────────────────────────────────────
if not exist "data"    mkdir data    2>nul
if not exist "uploads" mkdir uploads 2>nul
if not exist "exports" mkdir exports 2>nul
if not exist "backups" mkdir backups 2>nul
if not exist "logs"    mkdir logs    2>nul

:: ── CREATE .env IF MISSING ─────────────────────────────────────────
if exist ".env" goto :env_done
echo  [..] Creating settings file...
set "RAND=%RANDOM%%RANDOM%"
(
echo SPRING_PROFILE=dev
echo DATABASE_URL=jdbc:postgresql://localhost:5432/traderakshak
echo DB_DRIVER=org.postgresql.Driver
echo POSTGRES_USER=postgres
echo POSTGRES_PASSWORD=123456
echo JWT_SECRET=TR2026-%RAND%-SecureKey-TradeRakshak
echo FERNET_KEY=
echo SMTP_HOST=smtp.gmail.com
echo SMTP_PORT=587
echo SMTP_USER=
echo SMTP_PASSWORD=
echo TELEGRAM_BOT_TOKEN=
echo TELEGRAM_CHAT_ID=
echo RAZORPAY_KEY_ID=
echo RAZORPAY_KEY_SECRET=
echo RAZORPAY_WEBHOOK_SECRET=
echo MSG91_API_KEY=
echo MSG91_SENDER_ID=TRDRK
echo UPLOAD_DIR=./uploads
echo EXPORT_DIR=./exports
echo BACKUP_DIR=./backups
echo DATA_DIR=./data
echo MAX_FILE_SIZE_MB=1
echo MAX_BATCH_FILES=250
echo RATE_LIMIT_LOGIN=5
echo RATE_LIMIT_API=100
echo LOG_LEVEL=INFO
echo PORT=8080
echo CORS_ORIGINS=http://localhost:8080
) > ".env"
echo  [OK] Settings file created
echo.
:env_done

:: ── CHECK INTERNET (PowerShell only - avoids ping batch issues) ────
echo  [1/5] Checking internet connection...
powershell -Command "try { (New-Object Net.WebClient).DownloadString('http://www.msftncsi.com/ncsi.txt') | Out-Null; exit 0 } catch { exit 1 }" >nul 2>nul
if !errorlevel!==0 goto :net_ok
powershell -Command "try { Invoke-WebRequest -Uri 'http://connectivitycheck.gstatic.com/generate_204' -TimeoutSec 5 -UseBasicParsing | Out-Null; exit 0 } catch { exit 1 }" >nul 2>nul
if !errorlevel!==0 goto :net_ok
echo  [!!] No internet connection.
echo  Internet needed only for first-time setup.
echo  After first build, app works OFFLINE forever.
echo  Please connect to internet and run INSTALL.bat again.
pause
exit /b 1
:net_ok
echo  [OK] Internet is working
echo.

:: ── STEP 2: CHECK / INSTALL JAVA 21 ───────────────────────────────
echo  [2/5] Checking Java...
set "JAVA_HOME_PORTABLE=%APP_DIR%\jdk21"
set "JAVA_EXE=%JAVA_HOME_PORTABLE%\bin\java.exe"

if exist "%JAVA_EXE%" (
    echo  [OK] Java 21 already installed (portable)
    goto :java_ready
)

java -version >nul 2>nul
if !errorlevel!==0 (
    set "JAVA_EXE=java"
    echo  [OK] System Java found
    goto :java_ready
)

echo  [..] Downloading Java 21 (one-time, ~180 MB^)...
echo  Please wait - may take 5-15 minutes on slow internet...
echo.
set "JDK_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.4+7/OpenJDK21U-jdk_x64_windows_hotspot_21.0.4_7.zip"
set "JDK_ZIP=%APP_DIR%\jdk21_download.zip"
powershell -Command "& { $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%JDK_URL%' -OutFile '%JDK_ZIP%' -UseBasicParsing -TimeoutSec 600 }" 2>nul
if !errorlevel! neq 0 curl -L -o "%JDK_ZIP%" "%JDK_URL%" --max-time 600 2>nul
if not exist "%JDK_ZIP%" (
    echo  [!!] Java download failed. Check internet and try again.
    pause
    exit /b 1
)
echo  [..] Extracting Java 21...
if exist "%APP_DIR%\jdk21_temp" rmdir /s /q "%APP_DIR%\jdk21_temp" 2>nul
mkdir "%APP_DIR%\jdk21_temp" 2>nul
powershell -Command "Add-Type -Assembly System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::ExtractToDirectory('%JDK_ZIP%', '%APP_DIR%\jdk21_temp')" 2>nul
if !errorlevel! neq 0 tar -xf "%JDK_ZIP%" -C "%APP_DIR%\jdk21_temp" >nul 2>nul
for /d %%i in ("%APP_DIR%\jdk21_temp\*") do move "%%i" "%APP_DIR%\jdk21" >nul 2>nul
rmdir /s /q "%APP_DIR%\jdk21_temp" 2>nul
del /f /q "%JDK_ZIP%" 2>nul
if not exist "%JAVA_EXE%" (
    echo  [!!] Java extraction failed. Install Java 21 from adoptium.net manually.
    pause
    exit /b 1
)
echo  [OK] Java 21 installed
echo.

:java_ready
set "JAVA_HOME=%JAVA_HOME_PORTABLE%"
if not exist "%JAVA_EXE%" set "JAVA_EXE=java"
set "PATH=%JAVA_HOME_PORTABLE%\bin;%PATH%"

:: ── STEP 3: CHECK IF ALREADY BUILT ────────────────────────────────
set "JAR_FILE="
for /f "delims=" %%f in ('powershell -NoProfile -Command "Get-ChildItem -Path '%APP_DIR%\backend\target' -Filter 'traderakshak-backend-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName"') do set "JAR_FILE=%%f"
if not defined JAR_FILE goto :run_maven_setup
:: Only skip the build if the JAR is newer than all source. If you changed any
:: Java or Angular code, rebuild so your changes actually take effect.
powershell -NoProfile -ExecutionPolicy Bypass -Command "$r='%APP_DIR%'; $jar=Get-ChildItem (Join-Path $r 'backend\target') -Filter 'traderakshak-backend-*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*.original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1; if(-not $jar){exit 1}; $jt=$jar.LastWriteTime; function Newest($p){$i=foreach($x in $p){if(Test-Path $x){Get-ChildItem $x -Recurse -File -ErrorAction SilentlyContinue}}; if($i){($i|Sort-Object LastWriteTime -Descending|Select-Object -First 1).LastWriteTime}else{[datetime]::MinValue}}; $src=Newest @((Join-Path $r 'backend\src'),(Join-Path $r 'backend\pom.xml'),(Join-Path $r 'frontend\src'),(Join-Path $r 'frontend\package.json'),(Join-Path $r 'frontend\angular.json')); if($src -gt $jt){exit 1}else{exit 0}"
if errorlevel 1 goto :src_changed
echo  [3/5] Already built and up to date
echo  [OK] JAR: !JAR_FILE!
goto :launch
:src_changed
echo  [3/5] Your code changed since the last build - rebuilding now...
echo.
del /f /q "!JAR_FILE!" 2>nul

:run_maven_setup

:: ── STEP 3: DOWNLOAD MAVEN 3.9.8 ─────────────────────────────────
echo  [3/5] Setting up build tools...
set "MVN_HOME=%APP_DIR%\mvn_home"
set "MVN_EXE=%MVN_HOME%\bin\mvn.cmd"

if exist "%MVN_EXE%" (
    echo  [OK] Maven already available
    goto :run_build
)

echo  [..] Downloading Apache Maven 3.9.8 (one-time, ~10 MB^)...
echo  Trying multiple download sources...
set "MVN_ZIP=%APP_DIR%\maven_download.zip"

:: Mirror 1: GitHub release (most reliable globally)
set "MVN_URL1=https://github.com/nickvdyck/webbundle/releases/download/0.0.1/apache-maven-3.9.8-bin.zip"
:: Mirror 1: Apache archive (reliable)
set "MVN_URL1=https://archive.apache.org/dist/maven/maven-3/3.9.8/binaries/apache-maven-3.9.8-bin.zip"
:: Mirror 2: Apache CDN
set "MVN_URL2=https://dlcdn.apache.org/maven/maven-3/3.9.8/binaries/apache-maven-3.9.8-bin.zip"
:: Mirror 3: Apache main
set "MVN_URL3=https://downloads.apache.org/maven/maven-3/3.9.8/binaries/apache-maven-3.9.8-bin.zip"

echo  Trying mirror 1...
powershell -Command "& { $ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%MVN_URL1%' -OutFile '%MVN_ZIP%' -UseBasicParsing -TimeoutSec 300 }" >nul 2>nul
if exist "%MVN_ZIP%" goto :mvn_downloaded

echo  Trying mirror 2...
powershell -Command "& { $ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -Uri '%MVN_URL2%' -OutFile '%MVN_ZIP%' -UseBasicParsing -TimeoutSec 300 }" >nul 2>nul
if exist "%MVN_ZIP%" goto :mvn_downloaded

echo  Trying mirror 3 (curl)...
curl -L --ssl-no-revoke -o "%MVN_ZIP%" "%MVN_URL3%" --connect-timeout 30 --max-time 300 2>nul
if exist "%MVN_ZIP%" goto :mvn_downloaded

echo  Trying mirror 4...
curl -L --ssl-no-revoke -o "%MVN_ZIP%" "%MVN_URL1%" --connect-timeout 30 --max-time 300 2>nul
if exist "%MVN_ZIP%" goto :mvn_downloaded

echo.
echo  [!!] Maven download failed from all mirrors.
echo.
echo  Please download Maven manually:
echo  1. Open this URL in browser:
echo     https://archive.apache.org/dist/maven/maven-3/3.9.8/binaries/apache-maven-3.9.8-bin.zip
echo  2. Save the file as:  %APP_DIR%\maven_download.zip
echo  3. Run INSTALL.bat again
echo.
pause
exit /b 1

:mvn_downloaded

echo  [..] Extracting Maven...
if exist "%APP_DIR%\mvn_temp" rmdir /s /q "%APP_DIR%\mvn_temp" 2>nul
mkdir "%APP_DIR%\mvn_temp" 2>nul
powershell -Command "Add-Type -Assembly System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::ExtractToDirectory('%MVN_ZIP%', '%APP_DIR%\mvn_temp')" 2>nul
if !errorlevel! neq 0 tar -xf "%MVN_ZIP%" -C "%APP_DIR%\mvn_temp" >nul 2>nul
for /d %%i in ("%APP_DIR%\mvn_temp\*") do move "%%i" "%MVN_HOME%" >nul 2>nul
rmdir /s /q "%APP_DIR%\mvn_temp" 2>nul
del /f /q "%MVN_ZIP%" 2>nul

if not exist "%MVN_EXE%" (
    echo  [!!] Maven extraction failed. Check disk space (need 500 MB^).
    pause
    exit /b 1
)
echo  [OK] Maven 3.9.8 ready
echo.

:: ── RUN BUILD ─────────────────────────────────────────────────────
:run_build

:: CRITICAL FIX: Set JAVA_HOME explicitly before Maven
:: mvn.cmd needs JAVA_HOME to find java.exe
set "JAVA_HOME=%JAVA_HOME_PORTABLE%"
if not exist "%JAVA_HOME_PORTABLE%\bin\java.exe" set "JAVA_HOME="
set "PATH=%JAVA_HOME_PORTABLE%\bin;%MVN_HOME%\bin;%PATH%"

echo  [OK] JAVA_HOME: %JAVA_HOME_PORTABLE%
echo  [OK] Maven:     %MVN_HOME%
echo.
echo  ================================================================
echo   BUILDING TradeRakshak (first time only^)
echo  ================================================================
echo.
echo  Maven downloads libraries (~80 MB^). Time estimate:
echo    Fast internet (10 Mbps+^)  :  5-15 minutes
echo    Normal        (2 Mbps^)    : 15-30 minutes
echo    Slow          (under 1^)   : 30-60 minutes
echo.
echo  You will see "Downloading from central..." lines. This is normal.
echo  Do NOT close this window.
echo  ================================================================
echo.
echo  Starting build... watch for BUILD SUCCESS below
echo.

cd /d "%APP_DIR%\backend"
"%MVN_EXE%" clean package -Dmaven.test.skip=true --no-transfer-progress 2>&1
cd /d "%APP_DIR%"

set "JAR_FILE="
for /f "delims=" %%f in ('powershell -NoProfile -Command "Get-ChildItem -Path '%APP_DIR%\backend\target' -Filter 'traderakshak-backend-*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName"') do set "JAR_FILE=%%f"

if not defined JAR_FILE (
    echo.
    echo  ================================================================
    echo   BUILD FAILED
    echo  ================================================================
    echo.
    echo  Run INSTALL.bat again - Maven will resume from where it stopped.
    echo.
    echo  If it keeps failing:
    echo  1. Temporarily disable antivirus and try again
    echo  2. Ensure 2 GB free disk space
    echo  3. Check firewall allows maven.apache.org
    echo.
    pause
    exit /b 1
)
echo.
echo  [OK] BUILD SUCCESSFUL!
echo.

:: ── LAUNCH ────────────────────────────────────────────────────────
:launch
echo  [4/5] Reading settings...
set "APP_PORT=8080"
set "JWT_SECRET=TR2026-DefaultKey"
set "LOG_LEVEL=INFO"
set "DATABASE_URL=jdbc:postgresql://localhost:5432/traderakshak"
set "DB_DRIVER=org.postgresql.Driver"
set "POSTGRES_USER=postgres"
set "POSTGRES_PASSWORD=123456"
for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
    if "%%a"=="PORT"       set "APP_PORT=%%b"
    if "%%a"=="JWT_SECRET" set "JWT_SECRET=%%b"
    if "%%a"=="LOG_LEVEL"  set "LOG_LEVEL=%%b"
    if "%%a"=="DATABASE_URL" set "DATABASE_URL=%%b"
    if "%%a"=="DB_DRIVER" set "DB_DRIVER=%%b"
    if "%%a"=="POSTGRES_USER" set "POSTGRES_USER=%%b"
    if "%%a"=="POSTGRES_PASSWORD" set "POSTGRES_PASSWORD=%%b"
)
echo  [OK] App port: %APP_PORT%
echo.

echo  [..] Checking PostgreSQL...
:: Readiness, tablespace and database all come from db-setup.bat, shared with
:: START.bat so the two cannot drift apart again.
call "%~dp0db-setup.bat"
if errorlevel 1 (
    pause
    exit /b 1
)
echo.

:: Backups. This machine lost its database twice on 2026-08-23 with no backup of
:: any kind on disk, so the schedule is part of installation rather than something
:: to remember later. Dumps go to E:\TradeRakshak-Backups - deliberately outside
:: the application folder, so wiping or resetting the app never removes them.
echo  [..] Setting up automatic database backups...
schtasks /Create /TN "TradeRakshak Database Backup" /TR "\"%~dp0BACKUP-DATABASE.bat\" scheduled" /SC HOURLY /MO 6 /ST 00:15 /F >nul 2>&1
if errorlevel 1 (
    echo  [!] Could not register the backup schedule ^(needs Administrator^).
    echo      Run this later from an elevated prompt, or back up manually with
    echo      BACKUP-DATABASE.bat before any risky change.
) else (
    echo  [OK] Database will be backed up every 6 hours to E:\TradeRakshak-Backups
)
echo.

echo  [5/5] Starting TradeRakshak...
echo.
echo  ================================================================
echo.
echo    TRADERAKSHAK v11.5 IS STARTING...
echo.
echo    Browser will open automatically in 25 seconds, OR
echo    open manually at:  http://localhost:%APP_PORT%
echo.
echo    Login:
echo      Email    : superadmin@traderakshak.local
echo      Password : Admin@123
echo.
echo    DO NOT close this window while using the application.
echo    To stop the app: press Ctrl+C  or  run STOP.bat
echo.
echo  ================================================================
echo.

:: Write START.bat for future use (copy the standalone START.bat as-is)
:: START.bat is already maintained as a standalone file — skip overwrite
echo  [OK] START.bat is ready

:: STOP.bat is maintained as a standalone file, same as START.bat. It used to be
:: regenerated here, which overwrote the maintained copy with an older version that
:: printed "Stopped" without checking the port had actually been released.
echo  [OK] STOP.bat is ready

:: Auto-open browser after 25 seconds
start "" /b cmd /c "timeout /t 25 /nobreak >nul && start http://localhost:%APP_PORT%"

:: Launch application — runs in foreground so closing this window stops the app
if not exist "%APP_DIR%\runtime-tmp" mkdir "%APP_DIR%\runtime-tmp" 2>nul
"%JAVA_EXE%" ^
  -Xms512m -Xmx2048m ^
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
  -DLOG_LEVEL=%LOG_LEVEL% ^
  -jar "%JAR_FILE%"

echo.
echo.
echo  ============================================================
echo    Configuration this install applies
echo  ============================================================
echo.
echo    Candle store: 1 hour per bar, anchored on the 09:15 open,
echo                  so bars start 09:15 10:15 ... 15:15. This is the
echo                  same stamping Dhan uses for its own 60-minute
echo                  candles, so live and backfilled bars share one row.
echo.
echo    Trade legs:   BUY 15:15, SELL 10:15 the next trading day.
echo                  On an existing database the first start moves these
echo                  once, from 15:20/11:00, and records that it has done
echo                  so - it will not overwrite times you set yourself
echo                  afterwards. Watch for this line in the log:
echo                    [Config] Stock Upload trade legs retimed
echo.
echo    Both times are bar starts. If you change either one, keep it on
echo    the hour from 09:15 - the price lookup takes the first candle at
echo    or after the time given, so an off-grid time silently prices off
echo    the next bar, and anything past 15:15 matches no candle at all.
echo  ============================================================
echo.
echo  ============================================================
echo    Two things worth doing once, on this machine
echo  ============================================================
echo.
echo    1. Stop the drive sleeping.
echo       When the disk holding the database goes to sleep,
echo       PostgreSQL keeps file handles that are no longer valid.
echo       It then refuses every connection while still reporting
echo       itself as Running, and any check that cannot connect
echo       reports the database as missing. It is not missing.
echo       In an Administrator PowerShell, run these once:
echo.
echo         powercfg /change standby-timeout-ac 0
echo         powercfg /change standby-timeout-dc 0
echo.
echo    2. If that ever happens anyway, do NOT restore a backup.
echo       Double-click FIX-DATABASE-CONNECTION.bat and choose Yes.
echo       It restarts the database service gracefully, checks the
echo       data is there and prints your row counts back. It never
echo       deletes, resets or restores anything.
echo  ============================================================
echo.
echo  TradeRakshak stopped. To restart: double-click START.bat
echo.
pause
