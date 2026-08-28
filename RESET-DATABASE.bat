@echo off
setlocal enabledelayedexpansion
::  ===========================================================================
::   TradeRakshak - clean database reset
::  ===========================================================================
::   Wipes the TradeRakshak database and the tablespace it used, so START.bat
::   can build a fresh one from scratch.
::
::   WHEN TO USE THIS
::   Only when you intend to lose every row and re-fetch from the broker. It is
::   the recovery path after the database became unreadable ("could not open
::   file pg_tblspc/...").
::
::   WHAT IT DOES NOT TOUCH
::   Your source code, your .env, and the files under backups\. Those are safe.
::
::   AFTER RUNNING THIS
::     1. START.bat            - recreates the database and the schema
::     2. Broker screen        - re-enter credentials, generate the token
::     3. Stock Upload screen  - upload your stock list again
::     4. Data Warehouse       - fetch history from Dhan
::  ===========================================================================

cd /d "%~dp0"
title TradeRakshak - Reset Database

set "DB_NAME=traderakshak"
set "POSTGRES_USER=postgres"
set "POSTGRES_PASSWORD=123456"
if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%a in (".env") do (
        if /I "%%a"=="DB_NAME"           set "DB_NAME=%%b"
        if /I "%%a"=="POSTGRES_USER"     set "POSTGRES_USER=%%b"
        if /I "%%a"=="POSTGRES_PASSWORD" set "POSTGRES_PASSWORD=%%b"
    )
)

set "PSQL_EXE="
for %%V in (18 17 16 15) do (
    if not defined PSQL_EXE if exist "C:\Program Files\PostgreSQL\%%V\bin\psql.exe" set "PSQL_EXE=C:\Program Files\PostgreSQL\%%V\bin\psql.exe"
)
if not defined PSQL_EXE where psql >nul 2>nul && set "PSQL_EXE=psql"
if not defined PSQL_EXE (
    echo  PostgreSQL command line tool not found. Install PostgreSQL first, then run INSTALL.bat.
    pause
    exit /b 1
)

echo.
echo  ============================================================
echo    This deletes EVERY row in the "%DB_NAME%" database.
echo    Your stock list, price history, and settings will be gone.
echo    Source code, .env, and the backups folder are NOT touched.
echo  ============================================================
echo.
set /p CONFIRM="  Type YES to continue: "
if /I not "%CONFIRM%"=="YES" (
    echo  Cancelled. Nothing was changed.
    pause
    exit /b 0
)

echo.
echo  [1/5] Stopping the application so it releases the database...
call "%~dp0STOP.bat" >nul 2>&1

set "PGPASSWORD=%POSTGRES_PASSWORD%"

echo  [2/5] Disconnecting anything still attached to %DB_NAME%...
"%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -c ^
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='%DB_NAME%' AND pid<>pg_backend_pid();" >nul 2>&1

echo  [3/5] Backing up %DB_NAME% first...
:: A reset used to be unrecoverable. It now always dumps to E:\TradeRakshak-Backups
:: first - outside this folder, so wiping the app never takes the backup with it.
:: Written as gotos rather than a parenthesised block on purpose: %SKIPBK% inside a
:: block expands at parse time, before the user has typed anything.
call "%~dp0BACKUP-DATABASE.bat" predrop
if not errorlevel 1 goto :backup_ok

echo.
echo  [!] The backup FAILED - the database may already be unreadable.
echo      Dropping now means the data is gone for good.
echo.
set /p SKIPBK="  Type SKIPBACKUP to wipe anyway, or press Enter to cancel: "
if /i "%SKIPBK%"=="SKIPBACKUP" goto :backup_ok
echo  Cancelled. Nothing was changed.
pause
exit /b 1

:backup_ok

echo  [4/5] Dropping the database...
"%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -c "DROP DATABASE IF EXISTS \"%DB_NAME%\";"
if errorlevel 1 (
    echo.
    echo  Could not drop the database. Close any open pgAdmin or psql windows and run this again.
    pause
    exit /b 1
)

echo  [5/5] Removing the tablespace it used...
:: The tablespace only drops once no database references it, which is why it comes last.
"%PSQL_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -d postgres -c "DROP TABLESPACE IF EXISTS traderakshak_data;" >nul 2>&1

:: Clear the directory so a future CREATE TABLESPACE on the same path succeeds.
if defined TABLESPACE_DIR if exist "%TABLESPACE_DIR%" rd /s /q "%TABLESPACE_DIR%" >nul 2>&1

echo.
echo  ============================================================
echo    Done. The database is gone and the slate is clean.
echo.
echo    Next:  START.bat   - it recreates the database and schema
echo  ============================================================
echo.
pause
endlocal
