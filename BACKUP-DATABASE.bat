@echo off
::  ===========================================================================
::   BACKUP-DATABASE.bat  -  compressed dump of the TradeRakshak database.
::  ===========================================================================
::   Backups go to E:\TradeRakshak-Backups, deliberately OUTSIDE the application
::   folder. On 2026-08-23 a reset deleted the database directory and there was
::   nothing anywhere on the machine to restore from; a backup that lives inside
::   the thing it protects is not a backup.
::
::   Usage:  BACKUP-DATABASE.bat [label]
::             label - optional tag in the filename, e.g. predrop, daily
::
::   Exit code 0 = a verified dump exists, 1 = no usable backup was written.
::   Callers that are about to destroy data MUST check this and stop on 1.
::  ===========================================================================

setlocal EnableDelayedExpansion
cd /d "%~dp0"

set "BACKUP_ROOT=E:\TradeRakshak-Backups"
set "KEEP_COUNT=20"
set "LABEL=%~1"
if not defined LABEL set "LABEL=manual"

:: Same defaults and .env contract as START.bat and db-setup.bat.
set "POSTGRES_USER=postgres"
set "POSTGRES_PASSWORD=123456"
if exist ".env" (
    for /f "usebackq tokens=1,* delims==" %%a in (".env") do set "%%a=%%b"
)
set "DB_NAME=traderakshak"

set "PGDUMP_EXE="
for %%V in (18 17 16 15) do (
    if not defined PGDUMP_EXE if exist "C:\Program Files\PostgreSQL\%%V\bin\pg_dump.exe" set "PGDUMP_EXE=C:\Program Files\PostgreSQL\%%V\bin\pg_dump.exe"
)
if not defined PGDUMP_EXE where pg_dump >nul 2>nul && set "PGDUMP_EXE=pg_dump"
if not defined PGDUMP_EXE (
    echo  [!] pg_dump not found - cannot back up. Nothing was changed.
    exit /b 1
)

if not exist "%BACKUP_ROOT%" mkdir "%BACKUP_ROOT%" 2>nul
if not exist "%BACKUP_ROOT%" (
    echo  [!] Cannot create %BACKUP_ROOT% - is the E: drive available?
    exit /b 1
)

for /f %%T in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmmss"') do set "STAMP=%%T"
set "OUTFILE=%BACKUP_ROOT%\%DB_NAME%-%LABEL%-%STAMP%.dump"

echo  [..] Backing up %DB_NAME% to %OUTFILE%
set "PGPASSWORD=%POSTGRES_PASSWORD%"
"%PGDUMP_EXE%" -h localhost -p 5432 -U "%POSTGRES_USER%" -Fc -f "%OUTFILE%" "%DB_NAME%"
if errorlevel 1 (
    echo  [!] pg_dump FAILED. No backup was written.
    if exist "%OUTFILE%" del "%OUTFILE%" >nul 2>&1
    exit /b 1
)

:: A dump that exists but is tiny is worse than none, because it looks like safety.
set "OUTSIZE=0"
for %%F in ("%OUTFILE%") do set "OUTSIZE=%%~zF"
if !OUTSIZE! LSS 100000 (
    echo  [!] Backup is only !OUTSIZE! bytes - too small to be a real database.
    echo      Treating this as a FAILURE. The file is kept for inspection.
    exit /b 1
)

for /f %%S in ('powershell -NoProfile -Command "'{0:N1}' -f (!OUTSIZE!/1MB)"') do set "OUTMB=%%S"

:: Size alone does not prove a dump is readable. Ask pg_restore to list its table
:: of contents; if that fails the archive is corrupt and must not count as safety.
set "PGRESTORE_EXE="
for %%I in ("%PGDUMP_EXE%") do if exist "%%~dpIpg_restore.exe" set "PGRESTORE_EXE=%%~dpIpg_restore.exe"
if defined PGRESTORE_EXE (
    "%PGRESTORE_EXE%" --list "%OUTFILE%" >nul 2>&1
    if errorlevel 1 (
        echo  [!] The dump cannot be read back by pg_restore - it is corrupt.
        echo      Treating this as a FAILURE.
        exit /b 1
    )
)
echo  [OK] Backup written and verified readable: %OUTMB% MB

:: Retention. Keep the newest KEEP_COUNT dumps, remove older ones so E: does not
:: silently fill up and start failing the very backups this exists to guarantee.
powershell -NoProfile -Command "Get-ChildItem '%BACKUP_ROOT%\%DB_NAME%-*.dump' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -Skip %KEEP_COUNT% | Remove-Item -Force -ErrorAction SilentlyContinue" >nul 2>&1

for /f %%C in ('powershell -NoProfile -Command "@(Get-ChildItem '%BACKUP_ROOT%\%DB_NAME%-*.dump' -ErrorAction SilentlyContinue).Count"') do set "BKCOUNT=%%C"
echo  [OK] %BKCOUNT% backup(s) now held in %BACKUP_ROOT%
exit /b 0
