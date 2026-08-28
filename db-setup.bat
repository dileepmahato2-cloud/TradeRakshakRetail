@echo off
::  ===========================================================================
::   Shared PostgreSQL preparation for INSTALL.bat and START.bat.
::  ===========================================================================
::   Both scripts need the same three things before the JVM starts: a running
::   server, the tablespace, and the database. Keeping that in one file is
::   deliberate - the copies used to drift, which is how START.bat got its
::   Flyway fix while INSTALL.bat kept the broken value.
::
::   The caller sets POSTGRES_USER, POSTGRES_PASSWORD and DATABASE_URL first.
::   Exit code 0 = ready to start, 1 = PostgreSQL is not reachable.
::  ===========================================================================

if not defined POSTGRES_USER     set "POSTGRES_USER=postgres"
if not defined POSTGRES_PASSWORD set "POSTGRES_PASSWORD=123456"
if not defined DATABASE_URL      set "DATABASE_URL=jdbc:postgresql://localhost:5432/traderakshak"

:: Where the data lives.
::
:: The database goes in PostgreSQL's own data directory by default. That is the
:: location the server already uses for its system files, so it is the one known
:: to work. Putting the database in a custom tablespace on E: is what destroyed it
:: once: PostgreSQL reported "write error might be permanent", and a later
:: CREATE DATABASE into that tablespace produced 540 zero-byte catalog files and a
:: database that could not even open pg_am.
::
:: To use a separate drive anyway, set TABLESPACE_DIR in .env. It is only adopted
:: if the write probe below actually succeeds - existing is not the same as working.
set "TABLESPACE_NAME=traderakshak_data"
if not defined TABLESPACE_DIR set "TABLESPACE_DIR="
set "TABLESPACE_LOC=%TABLESPACE_DIR:\=/%"

set "DB_NAME=traderakshak"
for /f "delims=" %%d in ('powershell -NoProfile -Command "$u='%DATABASE_URL%'; if ($u -match '/([^/?]+)(?:[?].*)?$') { $matches[1] } else { 'traderakshak' }"') do set "DB_NAME=%%d"

set "PSQL_EXE="
for %%V in (18 17 16 15) do (
    if not defined PSQL_EXE if exist "C:\Program Files\PostgreSQL\%%V\bin\psql.exe" set "PSQL_EXE=C:\Program Files\PostgreSQL\%%V\bin\psql.exe"
)
if not defined PSQL_EXE where psql >nul 2>nul && set "PSQL_EXE=psql"
if not defined PSQL_EXE (
    echo  [!] PostgreSQL command line tools not found - skipping database checks.
    exit /b 0
)

:: pg_isready, not psql: it reports whether the server is up without needing to
:: authenticate, so a wrong POSTGRES_PASSWORD is not misreported as "server down".
set "PGREADY_EXE="
for %%I in ("%PSQL_EXE%") do if exist "%%~dpIpg_isready.exe" set "PGREADY_EXE=%%~dpIpg_isready.exe"
if not defined PGREADY_EXE where pg_isready >nul 2>nul && set "PGREADY_EXE=pg_isready"

set "PG_RETRIED=0"
:pg_check
if defined PGREADY_EXE (
    "%PGREADY_EXE%" -h localhost -p 5432 -q >nul 2>&1
) else (
    set "PGPASSWORD=%POSTGRES_PASSWORD%"
    "%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -tAc "SELECT 1" >nul 2>&1
)
if not errorlevel 1 goto :pg_ready
if "%PG_RETRIED%"=="0" (
    echo  [..] PostgreSQL is not responding - trying to start the service...
    net start postgresql-x64-18 >nul 2>&1
    set "PG_RETRIED=1"
    :: ping, not timeout: timeout aborts when stdin is redirected.
    ping -n 6 127.0.0.1 >nul 2>&1
    goto :pg_check
)
echo.
echo  [!!] PostgreSQL is not running on localhost:5432, and this window does not
echo       have the rights to start it.
echo.
echo       Open PowerShell as Administrator and run:
echo           Start-Service postgresql-x64-18
echo.
echo       Then run this script again.
echo.
exit /b 1

:pg_ready
set "PGPASSWORD=%POSTGRES_PASSWORD%"

:: Default: no custom tablespace. The database lands in PostgreSQL's own data
:: directory, which is the location the server is already proven to write to.
set "DB_TABLESPACE="

if defined TABLESPACE_DIR (
    "%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -tAc "SELECT 1 FROM pg_tablespace WHERE spcname='%TABLESPACE_NAME%'" 2>nul | findstr /C:"1" >nul
    if errorlevel 1 (
        echo  [..] Creating tablespace %TABLESPACE_NAME% at %TABLESPACE_DIR%
        if not exist "%TABLESPACE_DIR%" mkdir "%TABLESPACE_DIR%" 2>nul
        "%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -c "CREATE TABLESPACE %TABLESPACE_NAME% LOCATION '%TABLESPACE_LOC%'" >nul 2>&1
    )

    :: Prove it works before trusting it. A tablespace can exist and still be
    :: unwritable, which is exactly how the previous database was lost - the old
    :: check only asked whether the tablespace existed, so a broken one passed.
    :: This creates a real table in it, writes a row, reads it back, and drops it.
    echo  [..] Testing whether %TABLESPACE_NAME% can actually be written to
    "%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -q -c ^
      "CREATE TABLE _ts_probe(id int) TABLESPACE %TABLESPACE_NAME%; INSERT INTO _ts_probe VALUES(1); CHECKPOINT; SELECT count(*) FROM _ts_probe; DROP TABLE _ts_probe;" >nul 2>&1
    if errorlevel 1 (
        echo  [!] %TABLESPACE_NAME% exists but cannot be written to reliably.
        echo      Using PostgreSQL's default location instead so your data stays safe.
        "%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -c "DROP TABLE IF EXISTS _ts_probe;" >nul 2>&1
    ) else (
        echo  [OK] Tablespace write test passed.
        set "DB_TABLESPACE= TABLESPACE %TABLESPACE_NAME%"
    )
)

:: A missing database is only normal on a first install. After that it means the
:: database was dropped or lost, and silently building an empty one hides that:
:: the app comes up looking healthy with every row gone. That is exactly how the
:: 2026-08-23 loss went unnoticed - a drive fault, a reset, then a clean start on
:: an empty database. So we create it only once, and refuse ever after.
set "DB_MARKER=%~dp0.db-initialized"

:: Before deciding anything is missing, prove the server can actually answer a query.
::
:: The existence check below discards stderr, so a server that refuses every connection
:: produced exactly the same "no rows" as a database that genuinely is not there - and the
:: message that followed told the user to pg_restore over the top. On 2026-08-27 PostgreSQL
:: held stale file handles after the E: volume blipped and failed every connection with
:: "could not read blocks 0..0"; the database was intact, 25 GB of it, and restoring a
:: two-day-old dump over it would have destroyed real data to fix broken handles.
::
:: So: a connection problem and an absent database are now reported as the different
:: things they are.
"%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -tAc "SELECT 1" >nul 2>"%TEMP%\tr-pgcheck.txt"
if errorlevel 1 (
    echo.
    echo  ============================================================
    echo    STOPPED - cannot reach PostgreSQL.
    echo.
    echo    The server refused the connection, so nothing could be
    echo    checked. This is NOT the same as your data being gone -
    echo    do not restore or reset on the strength of this message.
    echo.
    echo    PostgreSQL said:
    for /f "usebackq delims=" %%E in ("%TEMP%\tr-pgcheck.txt") do echo      %%E
    echo.
    echo    If that mentions "could not read blocks" or "Invalid
    echo    argument", the files are fine and PostgreSQL is holding
    echo    stale handles after the drive slept. Reopen them with an
    echo    Administrator PowerShell:
    echo.
    echo      Restart-Service -Name postgresql-x64-18 -Force
    echo.
    echo    Then run START.bat again. If it persists, reboot - that
    echo    forces every handle closed. A reboot is the fix; a restore
    echo    is not.
    echo.
    echo    Stop the drive sleeping and causing this again:
    echo      powercfg /change standby-timeout-dc 0
    echo  ============================================================
    echo.
    del "%TEMP%\tr-pgcheck.txt" >nul 2>&1
    pause
    exit /b 1
)
del "%TEMP%\tr-pgcheck.txt" >nul 2>&1

"%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='%DB_NAME%'" 2>nul | findstr /C:"1" >nul
if not errorlevel 1 goto :db_present

if not exist "%DB_MARKER%" goto :db_create
if "%ALLOW_DB_CREATE%"=="1" goto :db_create

echo.
echo  ============================================================
echo    STOPPED - the "%DB_NAME%" database is missing.
echo.
echo    PostgreSQL answered normally, and the database is not
echo    listed - so this is a genuinely absent database, not a
echo    connection problem.
echo.
echo    This machine has set it up before, so it should be there.
echo    Creating a new empty one now would start the application
echo    with no stock list, no price history and no rankings, and
echo    would look like everything is fine.
echo.
echo    Nothing has been changed. The application was not started.
echo.
echo    If you have a backup, restore it first:
echo      pg_restore -U postgres -d %DB_NAME% "backups\your-file.dump"
echo.
echo    To deliberately start over with an empty database, run
echo    RESET-DATABASE.bat - it takes a backup before it wipes.
echo  ============================================================
echo.
pause
exit /b 1

:db_create
echo  [..] Creating database %DB_NAME%
"%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -c "CREATE DATABASE ""%DB_NAME%""%DB_TABLESPACE%" >nul 2>&1

:db_present
if not exist "%DB_MARKER%" >"%DB_MARKER%" echo %DB_NAME% has been set up on this machine. While this file exists, START.bat will refuse to silently recreate the database if it goes missing.

echo  [OK] Database ready: %DB_NAME%
exit /b 0
