<#
  FIX-POSTGRES-SERVICE.ps1
  ========================
  Puts PostgreSQL back under Windows service control.

  The problem this solves: on this machine the postgresql-x64-18 service reads
  "Stopped" while PostgreSQL is actually running as an orphaned postmaster whose
  parent shell exited long ago. An orphan never receives a clean shutdown - on
  logoff or power-off Windows kills it abruptly. That is the direct cause of the
  2026-08-22 corruption and it contributed to the 2026-08-23 loss.

  This does NOT move any data and does NOT touch the registry. It stops the
  orphan gracefully and starts the real service on the same directory.

  Run from an ELEVATED PowerShell (Run as Administrator).
#>

$ErrorActionPreference = 'Stop'

$Service = 'postgresql-x64-18'
$DataDir = 'C:\Program Files\PostgreSQL\18\data'
$PgCtl   = 'C:\Program Files\PostgreSQL\18\bin\pg_ctl.exe'

function Say  ($m) { Write-Host "  $m" }
function Step ($m) { Write-Host "`n[ $m ]" -ForegroundColor Cyan }
function Die  ($m) { Write-Host "`n  STOPPED: $m" -ForegroundColor Red; exit 1 }

Write-Host "`n===============================================" -ForegroundColor Cyan
Write-Host "  Return PostgreSQL to Windows service control" -ForegroundColor Cyan
Write-Host "===============================================" -ForegroundColor Cyan

Step '1/5  Preflight'
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]'Administrator')) {
    Die 'Must run elevated. Right-click PowerShell -> Run as Administrator.'
}
Say 'Elevated: yes'

# The application holds connections and may be mid-fetch. Refuse rather than decide.
if (@(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue).Count -gt 0) {
    Die @'
TradeRakshak is still running on port 8080.

Restarting PostgreSQL underneath a running application will break whatever it is
doing - including a historical backfill that may be hours from finishing.

Wait for any fetch to complete, run STOP.bat, then run this again.
'@
}
Say 'Application is not running'

$appDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$recent = Get-ChildItem 'E:\TradeRakshak-Backups\traderakshak-*.dump' -ErrorAction SilentlyContinue |
          Where-Object { $_.LastWriteTime -gt (Get-Date).AddHours(-24) -and $_.Length -gt 1MB } |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $recent) { Die 'No verified backup under 24 hours old. Run BACKUP-DATABASE.bat first.' }
Say ("Backup present: {0} ({1:N1} MB)" -f $recent.Name, ($recent.Length/1MB))

Step '2/5  Stopping the orphan gracefully'
Say ("postgres processes before: {0}" -f @(Get-Process postgres -ErrorAction SilentlyContinue).Count)

# Graceful shutdown only. Stop-Process -Force is what destroyed this database on
# 2026-08-22 and must never be used here - a fast shutdown still checkpoints.
& $PgCtl stop -D $DataDir -m fast 2>&1 | Out-Null

if (Test-Path "$DataDir\postmaster.pid") {
    $orphan = (Get-Content "$DataDir\postmaster.pid" -TotalCount 1).Trim()
    if ($orphan -and (Get-Process -Id $orphan -ErrorAction SilentlyContinue)) {
        Say "pg_ctl stop did not take; sending fast-shutdown signal to $orphan"
        & $PgCtl kill INT $orphan 2>&1 | Out-Null
    }
}

for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    if (@(Get-Process postgres -ErrorAction SilentlyContinue).Count -eq 0 -and
        @(Get-NetTCPConnection -LocalPort 5432 -State Listen -ErrorAction SilentlyContinue).Count -eq 0) { break }
}

$left = @(Get-Process postgres -ErrorAction SilentlyContinue).Count
if ($left -gt 0) {
    Die "$left postgres process(es) still running after 2 minutes. Do NOT force-kill them - that caused the 22 Aug loss. Investigate before retrying."
}
Say 'Orphan stopped cleanly, port 5432 free'

Step '3/5  Clearing any stale pid file'
if (Test-Path "$DataDir\postmaster.pid") {
    Remove-Item "$DataDir\postmaster.pid" -Force
    Say 'Removed stale postmaster.pid'
} else { Say 'No stale pid file' }

Step '4/5  Starting the service'
Set-Service $Service -StartupType Automatic
Start-Service $Service
Start-Sleep -Seconds 6
$st = (Get-Service $Service).Status
if ($st -ne 'Running') { Die "Service status is $st. Check $DataDir\log for the reason." }
Say 'Service status: Running'

Step '5/5  Verifying'
$psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
$env:PGPASSWORD = '123456'
$envFile = Join-Path $appDir '.env'
if (Test-Path $envFile) {
    $line = Select-String -Path $envFile -Pattern '^POSTGRES_PASSWORD=' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($line) { $env:PGPASSWORD = ($line.Line -split '=',2)[1].Trim() }
}
$rows = (& $psql -h localhost -U postgres -d traderakshak -tAc "SELECT sum(n_live_tup) FROM pg_stat_user_tables;" 2>&1)
Say "rows visible: $rows"
& $PgCtl status -D $DataDir

Write-Host @"

  PostgreSQL is now running as the Windows service.

  What changed: it will receive a proper shutdown when Windows restarts, instead
  of being killed mid-write. Start it from now on with:  Start-Service $Service
  and never by launching postgres.exe or pg_ctl start from a shell - that is how
  the orphan came back each time.

  You can now start the application with START.bat.

"@ -ForegroundColor Green
