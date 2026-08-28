<#
  MOVE-DATABASE-TO-E.ps1
  ======================
  Relocates the whole PostgreSQL 18 cluster from C: to E:.

  Why the whole cluster and not a tablespace: a tablespace splits one database
  across two drives, so if either side goes missing the database is half-gone.
  That arrangement failed twice on this machine. Moving the entire data directory
  keeps everything together with no cross-drive dependency.

  Run this from an ELEVATED PowerShell (Run as Administrator).

  It is written to be abandonable at every step. Nothing is deleted: the original
  directory is left exactly where it is, and the registry is rolled back if the
  service fails to start on the new path. If anything looks wrong, stop - the old
  cluster is still intact and startable.
#>

$ErrorActionPreference = 'Stop'

$Service    = 'postgresql-x64-18'
$OldData    = 'C:\Program Files\PostgreSQL\18\data'
$NewData    = 'E:\PostgreSQL\18\data'
$BackupDir  = 'E:\TradeRakshak-Backups'
$PgCtl      = 'C:\Program Files\PostgreSQL\18\bin\pg_ctl.exe'
$RegPath    = "HKLM:\SYSTEM\CurrentControlSet\Services\$Service"

function Say  ($m) { Write-Host "  $m" }
function Step ($m) { Write-Host "`n[ $m ]" -ForegroundColor Cyan }
function Die  ($m) { Write-Host "`n  STOPPED: $m" -ForegroundColor Red; Write-Host "  Nothing was changed that cannot be undone. The old cluster is intact.`n"; exit 1 }

Write-Host "`n=======================================================" -ForegroundColor Cyan
Write-Host "  Move PostgreSQL cluster:  C:  ->  E:" -ForegroundColor Cyan
Write-Host "=======================================================" -ForegroundColor Cyan

# ---------------------------------------------------------------- 1. checks
Step '1/9  Preflight'

if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]'Administrator')) {
    Die 'This must run in an elevated PowerShell. Right-click PowerShell and choose "Run as Administrator".'
}
Say 'Elevated: yes'

if (-not (Test-Path $OldData)) { Die "Source data directory not found: $OldData" }
if (-not (Test-Path $PgCtl))   { Die "pg_ctl not found: $PgCtl" }

# A recent, readable dump must exist. This move should never be the only copy.
$recent = Get-ChildItem "$BackupDir\traderakshak-*.dump" -ErrorAction SilentlyContinue |
          Where-Object { $_.LastWriteTime -gt (Get-Date).AddHours(-24) -and $_.Length -gt 1MB } |
          Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $recent) {
    Die "No backup under 24 hours old in $BackupDir. Run BACKUP-DATABASE.bat first."
}
Say ("Backup found: {0}  ({1:N1} MB, {2})" -f $recent.Name, ($recent.Length/1MB), $recent.LastWriteTime)

if (Test-Path $NewData) {
    if ((Get-ChildItem $NewData -Force -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0) {
        Die "$NewData already exists and is not empty. Move or remove it first."
    }
}

$srcSize = (Get-ChildItem $OldData -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
$free    = (Get-PSDrive E).Free
Say ("Cluster size: {0:N2} GB    E: free: {1:N2} GB" -f ($srcSize/1GB), ($free/1GB))
if ($free -lt ($srcSize * 1.5)) { Die 'Not enough free space on E: (want 1.5x the cluster size).' }

# ---------------------------------------------------------------- 2. app must be down
Step '2/9  Checking the application is not running'
$appDir = Split-Path -Parent $MyInvocation.MyCommand.Path

# Deliberately refuses instead of stopping the app itself. A historical backfill
# can run for hours, and killing it midway leaves the fetch half-finished with no
# clean resume point. Whether it is safe to stop is the operator's call, not this
# script's.
if (@(Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue).Count -gt 0) {
    Die @'
TradeRakshak is still running on port 8080.

This script will NOT stop it for you - a data fetch may be in progress.

Wait until any backfill has finished, run STOP.bat yourself, then run this again.
'@
}
Say 'Application is not running'

# ---------------------------------------------------------------- 3. stop pg
Step '3/9  Stopping PostgreSQL cleanly'

# Graceful first. The orphan on this machine does not always answer pg_ctl stop,
# so fall back to a fast-shutdown SIGINT, which is still a clean shutdown - never
# Stop-Process -Force, which is what destroyed this database on 2026-08-22.
try { Stop-Service $Service -Force -ErrorAction Stop; Say 'Service stopped' } catch { Say 'Service was not running' }

& $PgCtl stop -D $OldData -m fast 2>&1 | Out-Null

if (Test-Path "$OldData\postmaster.pid") {
    $pid0 = (Get-Content "$OldData\postmaster.pid" -TotalCount 1).Trim()
    if ($pid0 -and (Get-Process -Id $pid0 -ErrorAction SilentlyContinue)) {
        Say "Orphan postmaster $pid0 still alive - sending fast-shutdown signal"
        & $PgCtl kill INT $pid0 2>&1 | Out-Null
    }
}

for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 2
    $running = @(Get-Process postgres -ErrorAction SilentlyContinue).Count
    $port    = @(Get-NetTCPConnection -LocalPort 5432 -State Listen -ErrorAction SilentlyContinue).Count
    if ($running -eq 0 -and $port -eq 0) { break }
}
if (@(Get-Process postgres -ErrorAction SilentlyContinue).Count -gt 0) {
    Die 'PostgreSQL is still running after 2 minutes. Do NOT force-kill it - that is what caused the 22 Aug loss. Investigate first.'
}
Say 'PostgreSQL fully stopped, port 5432 free'

# ---------------------------------------------------------------- 4. copy
Step '4/9  Copying the data directory (this takes a few minutes)'
New-Item -ItemType Directory -Path $NewData -Force | Out-Null

# /COPYALL keeps ACLs and ownership, /B uses backup mode for files normally locked,
# /DCOPY:DAT keeps directory timestamps. PostgreSQL cares about all of these.
& robocopy $OldData $NewData /MIR /COPYALL /B /DCOPY:DAT /R:2 /W:2 /NFL /NDL /NP | Out-Null
$rc = $LASTEXITCODE
if ($rc -ge 8) { Die "robocopy failed with exit code $rc. $NewData may be incomplete - delete it and retry." }
Say "robocopy finished (exit $rc - anything under 8 is success)"

# ---------------------------------------------------------------- 5. verify
Step '5/9  Verifying the copy'
foreach ($f in @('PG_VERSION','postgresql.conf','pg_hba.conf','base','global')) {
    if (-not (Test-Path (Join-Path $NewData $f))) { Die "Copy is missing $f - aborting before any registry change." }
}
$srcCount = (Get-ChildItem $OldData -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object).Count
$dstCount = (Get-ChildItem $NewData -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object).Count
Say "Files: source $srcCount, copy $dstCount"
if ($dstCount -lt ($srcCount * 0.98)) { Die 'Copy has noticeably fewer files than the source - aborting.' }

# A stale pid file would make the new cluster think it is already running.
Remove-Item (Join-Path $NewData 'postmaster.pid') -Force -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- 6. registry
Step '6/9  Pointing the service at the new location'
$oldImage = (Get-ItemProperty $RegPath).ImagePath
Say "Current: $oldImage"
$newImage = $oldImage -replace [regex]::Escape($OldData), $NewData
if ($newImage -eq $oldImage) { Die 'Could not rewrite the -D path in the service ImagePath. Change it by hand.' }
Set-ItemProperty $RegPath -Name ImagePath -Value $newImage
Say "Updated: $newImage"

# ---------------------------------------------------------------- 7. start
Step '7/9  Starting the service on the new path'
$ok = $true
try {
    Start-Service $Service -ErrorAction Stop
    Start-Sleep -Seconds 5
    if ((Get-Service $Service).Status -ne 'Running') { $ok = $false }
} catch { $ok = $false }

if (-not $ok) {
    Write-Host "`n  Service did not start. Rolling the registry back to C:." -ForegroundColor Yellow
    Set-ItemProperty $RegPath -Name ImagePath -Value $oldImage
    try { Start-Service $Service -ErrorAction Stop } catch {}
    Die "Service failed to start from $NewData. Reverted to the original path; the old cluster is untouched. Check the PostgreSQL log in $NewData\log."
}
Say 'Service is Running'

# ---------------------------------------------------------------- 8. prove it
Step '8/9  Checking the data is really there'
$psql = 'C:\Program Files\PostgreSQL\18\bin\psql.exe'
$env:PGPASSWORD = '123456'
$envFile = Join-Path $appDir '.env'
if (Test-Path $envFile) {
    $line = Select-String -Path $envFile -Pattern '^POSTGRES_PASSWORD=' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($line) { $env:PGPASSWORD = ($line.Line -split '=',2)[1].Trim() }
}
$dir = (& $psql -h localhost -U postgres -d traderakshak -tAc "SELECT current_setting('data_directory');" 2>&1)
$rows = (& $psql -h localhost -U postgres -d traderakshak -tAc "SELECT sum(n_live_tup) FROM pg_stat_user_tables;" 2>&1)
Say "data_directory reports: $dir"
Say "rows visible: $rows"
if ("$dir" -notmatch 'E:') { Die "The server is still serving from C:. Registry change did not take effect." }

# ---------------------------------------------------------------- 9. done
Step '9/9  Done'
Write-Host @"

  The cluster now runs from:  $NewData
  The old directory is STILL AT:  $OldData

  It has deliberately not been deleted. Use the application for a day or two,
  confirm everything behaves, then reclaim the space with:

      Rename-Item '$OldData' 'data.old'

  and delete 'data.old' once you are certain. Keeping it costs disk space and
  nothing else; deleting it early costs you your fallback.

"@ -ForegroundColor Green
