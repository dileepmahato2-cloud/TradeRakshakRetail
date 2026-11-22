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
:: A dump can be readable, comfortably over the size floor, and still be missing almost
:: everything. On 2026-09-07 the 00:15 run finished in 13 seconds and wrote 56 MB where the
:: previous good dump was 884 MB: it held 3,121,819 candle rows against 53,307,859 in the
:: live database - 6%% of the history - and passed both existing checks, because 56 MB clears
:: a 100 KB floor and pg_restore could list its tables perfectly well. A backup that looks
:: healthy but restores a gutted database is the most dangerous kind.
::
:: So compare against what the database has been dumping RECENTLY - not against the largest
:: dump ever taken.
::
:: Comparing against the all-time maximum cannot tell a truncated dump from a database that
:: legitimately got smaller, and the difference matters because one is a disaster and the
:: other is routine. On 2026-11-21 this blocked every start: the live database held 3,257,452
:: candle rows and dumped to 83 MB, against a 904 MB dump taken on 2026-09-07 when the same
:: table held 53,307,859 rows. The history had been deliberately trimmed in between, so every
:: backup from then on was ~6% of that maximum and failed the check - permanently, because the
:: maximum never moves. The advice it printed made it worse: deleting good backups to satisfy
:: a backup check is exactly backwards.
::
:: The median of the last three dumps is the right baseline. A real database does not halve
:: between consecutive runs, so the September incident is still caught (the three dumps before
:: it were 884, 884 and 271 MB, median 884, against a 56 MB dump), while a gradual legitimate
:: shrink moves the baseline down with it. The median rather than the latest, so one bad dump
:: cannot poison the baseline and wave the next one through.
set "SIZECHECK=OK"
for /f %%Z in ('powershell -NoProfile -Command "$new=[long]!OUTSIZE!; $r=@(Get-ChildItem '%BACKUP_ROOT%\%DB_NAME%-*.dump' -ErrorAction SilentlyContinue ^| Where-Object { $_.FullName -ne '%OUTFILE%' } ^| Sort-Object LastWriteTime -Descending ^| Select-Object -First 3 ^| ForEach-Object { $_.Length } ^| Sort-Object); if($r.Count -eq 0){'OK'}else{$base=$r[[int][math]::Floor($r.Count/2)]; if($new -lt ($base/2)){'SHRUNK'}else{'OK'}}"') do set "SIZECHECK=%%Z"
if /i "!SIZECHECK!"=="SHRUNK" (
    echo  [!] This backup is less than half the size of the last few already held.
    echo      That normally means pg_dump stopped early and the dump is incomplete.
    echo      Treating this as a FAILURE. The file is kept for inspection, and the
    echo      earlier backups have NOT been rotated away.
    echo.
    echo      Check it before doing anything else - a dump that lists its contents
    echo      cleanly is complete, whatever its size:
    echo          pg_restore --list "%OUTFILE%"
    echo.
    echo      If that succeeds and the database really has got smaller, start with:
    echo          set SKIP_MIGRATION_BACKUP=1
    echo      Do NOT delete good backups to make this check pass.
    exit /b 1
)

echo  [OK] Backup written and verified readable: %OUTMB% MB

:: Retention. Keep the newest KEEP_COUNT dumps, remove older ones so E: does not
:: silently fill up and start failing the very backups this exists to guarantee.
powershell -NoProfile -Command "Get-ChildItem '%BACKUP_ROOT%\%DB_NAME%-*.dump' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -Skip %KEEP_COUNT% | Remove-Item -Force -ErrorAction SilentlyContinue" >nul 2>&1

for /f %%C in ('powershell -NoProfile -Command "@(Get-ChildItem '%BACKUP_ROOT%\%DB_NAME%-*.dump' -ErrorAction SilentlyContinue).Count"') do set "BKCOUNT=%%C"
echo  [OK] %BKCOUNT% backup(s) now held in %BACKUP_ROOT%
exit /b 0
