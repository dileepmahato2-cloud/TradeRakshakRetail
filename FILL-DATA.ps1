<#
  FILL-DATA.ps1
  =============
  Starts (or resumes) the Dhan historical backfill from the command line, and
  then follows its progress.

  Defaults target what is still missing as of 2026-08-24. The overnight run filled
  Oct 2025 through Jan 2026 before the laptop slept and the watchdog killed it, so
  the remaining hole is Feb 2026 - Jul 2026; every other month is covered.
  Re-running is safe: the unique constraint on broker_market_candles upserts, so
  overlapping days are not duplicated and an interrupted range can simply be redone.

  The stored history is 5-minute INTRADAY. To match it, pass -Mode INTRADAY
  -Interval 5 (about 9 GB for the six months). DAILY is the default because it is
  ~400 MB and is what ranking, RSI, EMA and the 20-day volume average actually read.

  Your password is read with Read-Host -AsSecureString - not echoed, not written
  to disk, not placed on the command line where it would land in shell history.

  NOTE: logging in sends consent=true, which records your acceptance of the SEBI
  declaration. That is why this script is run by you rather than by an assistant.

  Examples
    .\FILL-DATA.ps1                                   # Feb-Jul 2026, DAILY
    .\FILL-DATA.ps1 -Mode INTRADAY -Interval 5        # match stored 5-minute data
    .\FILL-DATA.ps1 -FromDate 2026-01-01 -ToDate 2026-07-31   # redo partial Jan too
    .\FILL-DATA.ps1 -StatusOnly                       # just follow a running job
#>

[CmdletBinding()]
param(
    [string]$BaseUrl   = 'http://localhost:8080/api',
    [string]$Email     = 'superadmin@traderakshak.local',
    [string]$FromDate  = '2026-02-01',
    [string]$ToDate    = '2026-07-31',
    [ValidateSet('DAILY','INTRADAY')]
    [string]$Mode      = 'DAILY',
    [ValidateSet(1,5,15,25,60)]
    [int]$Interval     = 5,
    [ValidateSet('TRACKED','ALL')]
    [string]$Scope     = 'TRACKED',
    [string]$Exchanges = 'NSE',
    [string]$Segments  = 'NSE_EQ',
    [string]$Filter    = 'ES',
    [switch]$StatusOnly
)

$ErrorActionPreference = 'Stop'

# The 2026-08-23 run died at 03:41 after the laptop entered Modern Standby at 03:18:
# the thread and its network were suspended, the server's 10-minute no-progress
# watchdog fired, and the job was marked failed one second before the machine woke.
# Nothing was wrong with the data or the API - it just went to sleep mid-fetch.
# ES_CONTINUOUS | ES_SYSTEM_REQUIRED tells Windows to keep the system up while this
# script runs, and the flag is cleared again on exit.
$KeepAwake = $null
try {
    $KeepAwake = Add-Type -PassThru -Name PowerKeepAwake -Namespace TradeRakshak -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true)]
public static extern uint SetThreadExecutionState(uint esFlags);
'@
    [void]$KeepAwake::SetThreadExecutionState(0x80000001)
} catch {
    Write-Host "  (could not request keep-awake: $($_.Exception.Message))" -ForegroundColor Yellow
}

function Say  ($m) { Write-Host "  $m" }
function Step ($m) { Write-Host "`n[ $m ]" -ForegroundColor Cyan }
function Release { if ($KeepAwake) { try { [void]$KeepAwake::SetThreadExecutionState(0x80000000) } catch {} } }
function Die  ($m) { Release; Write-Host "`n  STOPPED: $m" -ForegroundColor Red; exit 1 }

Write-Host "`n=============================================" -ForegroundColor Cyan
Write-Host "  TradeRakshak - fill historical market data" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

# ------------------------------------------------------------------ 1. app up
Step '1/4  Checking the application'
try {
    $h = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
    if ($h.status -ne 'UP') { Die "Application health is '$($h.status)'. Start it with START.bat first." }
} catch {
    Die "Cannot reach $BaseUrl - is the application running? Start it with START.bat."
}
Say 'Application is UP'

# ------------------------------------------------------------------ 2. login
Step '2/4  Signing in'
Say "Email: $Email"
Write-Host "  Logging in sends consent=true (SEBI declaration acceptance)." -ForegroundColor Yellow
$sec = Read-Host '  Password' -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
try   { $plain = [Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }

try {
    $login = Invoke-RestMethod -Uri "$BaseUrl/auth/login" -Method Post -ContentType 'application/json' `
             -Body (@{ email = $Email; password = $plain; consent = $true } | ConvertTo-Json)
} catch {
    $msg = $_.ErrorDetails.Message
    if (-not $msg) { $msg = $_.Exception.Message }
    Die "Login failed: $msg"
} finally {
    $plain = $null; [GC]::Collect()      # do not leave the password in memory longer than needed
}

$token = $login.data.token
if (-not $token) { $token = $login.data.accessToken }
if (-not $token) { Die 'Login succeeded but no token was returned - cannot continue.' }
$auth = @{ Authorization = "Bearer $token" }
Say 'Signed in'

# ------------------------------------------------------------------ 3. start
if (-not $StatusOnly) {
    Step '3/4  Starting the backfill'
    $body = @{
        brokerCredentialId    = $null
        mode                  = $Mode
        intervalMinutes       = $Interval
        fromDate              = $FromDate
        toDate                = $ToDate
        exchanges             = $Exchanges
        segments              = $Segments
        instrumentFilter      = $Filter
        scope                 = $Scope
        continueOnMissingData = $true
        dataType              = 'Dhan Historical Candles'
    }
    Say "$Mode  $FromDate -> $ToDate   scope=$Scope$(if($Mode -eq 'INTRADAY'){"  interval=${Interval}m"})"
    try {
        $r = Invoke-RestMethod -Uri "$BaseUrl/data/dhan/historical-backfill" -Method Post `
             -Headers $auth -ContentType 'application/json' -Body ($body | ConvertTo-Json)
        Say ($r.message)
        if ($r.data) { Say ("job range: {0} -> {1}" -f $r.data.fromDate, $r.data.toDate) }
    } catch {
        $msg = $_.ErrorDetails.Message; if (-not $msg) { $msg = $_.Exception.Message }
        # A job already running is not an error worth aborting on - fall through and follow it.
        if ("$msg" -match 'DATA-ACTIVE|current activity') {
            Say 'A job is already running - following that one instead.'
        } else {
            Die "Could not start the backfill: $msg"
        }
    }
} else {
    Step '3/4  Skipping start (-StatusOnly)'
}

# ------------------------------------------------------------------ 4. follow
Step '4/4  Progress  (Ctrl+C to stop watching - the job keeps running)'
$last = ''
while ($true) {
    Start-Sleep -Seconds 20
    try {
        $j = (Invoke-RestMethod -Uri "$BaseUrl/data/dhan/historical-backfill/latest" -Headers $auth -TimeoutSec 20).data
    } catch { Say 'status unavailable, retrying...'; continue }
    if (-not $j) { Say 'no job found'; break }

    $line = "{0,-9} {1,6}/{2,-6} instruments   {3,9} candles   failed:{4}  skipped:{5}  {6}" -f `
            $j.status, $j.processedInstruments, $j.totalInstruments,
            $j.importedCandles, $j.failedChunks, $j.skippedInstruments, $j.currentSymbol
    if ($line -ne $last) { Say $line; $last = $line }

    if ($j.status -in @('completed','failed','stopped')) {
        Write-Host ""
        if ($j.status -eq 'completed') {
            Write-Host "  Finished: $($j.importedCandles) candles imported." -ForegroundColor Green
        } else {
            Write-Host "  Job ended with status '$($j.status)': $($j.message)" -ForegroundColor Yellow
            Write-Host "  Failed chunks and skipped symbols are downloadable from the Data Warehouse screen." -ForegroundColor Yellow
        }
        break
    }
}
Write-Host ""

Release
Write-Host '  Sleep suppression released.' -ForegroundColor DarkGray
