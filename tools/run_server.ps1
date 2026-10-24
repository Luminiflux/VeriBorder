# ---------------------------------------------------------------------------
# VeriBorder dedicated-server launcher + smoke test
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run_server.ps1
#       Start the Forge dedicated server in run-server/ and stay attached
#       (type "stop" to shut it down). Uses the cached JDK 17 automatically.
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\run_server.ps1 -Smoke
#       Same start-up, but the script waits for "Done (", lets the mod log its
#       ready line, stops the server, then fails if the log contains a missing
#       class / client-class leak / fatal exception. This is the dedicated-server
#       acceptance gate for "no client classes on a server".
#
#   -JavaHome <dir>     force a JDK 17 installation
#   -SmokeSeconds <n>   readiness timeout (default 300 s)
#   -KeepRunning        smoke mode: leave the server running after readiness
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [string]$JavaHome,
    [switch]$Smoke,
    [int]$SmokeSeconds = 300,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$gradlew = Join-Path $project 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "gradlew.bat not found in $project"
}

function Resolve-Jdk17 {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($JavaHome) { $candidates.Add($JavaHome) }
    if ($env:JAVA_HOME) { $candidates.Add($env:JAVA_HOME) }
    # Cached JDK locations used by this workstation / CI image.
    foreach ($base in @(
            (Join-Path $env:LOCALAPPDATA 'Temp\lmep_build'),
            (Join-Path $env:USERPROFILE '.gradle\jdks'),
            'C:\Program Files\Eclipse Adoptium',
            'C:\Program Files\Java',
            'C:\Program Files\Microsoft'
        )) {
        if (Test-Path -LiteralPath $base) {
            Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                ForEach-Object { $candidates.Add($_.FullName) }
        }
    }
    foreach ($candidate in $candidates) {
        $java = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $java)) { continue }
        # `java -version` prints to stderr; with ErrorActionPreference=Stop that
        # would turn into a terminating NativeCommandError, so relax it locally.
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $version = (& $java -version 2>&1 | Out-String)
        } catch {
            continue
        } finally {
            $ErrorActionPreference = $previous
        }
        if ($version -match 'version "17\.') {
            return $candidate
        }
    }
    return $null
}

$jdk = Resolve-Jdk17
if (-not $jdk) {
    throw @'
JDK 17 not found.
Set JAVA_HOME to a JDK 17 installation, or pass -JavaHome <directory>.
Forge 1.20.1 does not run on JDK 21+, so the version is checked explicitly.
'@
}
$env:JAVA_HOME = $jdk
Write-Host "JAVA_HOME = $jdk"

$eula = Join-Path $project 'run-server\eula.txt'
if (-not (Test-Path -LiteralPath $eula) -or (Get-Content -LiteralPath $eula -Raw) -notmatch 'eula\s*=\s*true') {
    throw "run-server/eula.txt does not contain eula=true; accept the Minecraft EULA before running a server."
}

$logDir = Join-Path $project 'logs'
New-Item -ItemType Directory -Force $logDir | Out-Null

if (-not $Smoke) {
    Write-Host 'Starting the dedicated server (working directory: run-server). Type "stop" to shut down.'
    & $gradlew runServer --console=plain
    exit $LASTEXITCODE
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $logDir "server-smoke-$stamp.log"
$errLog = Join-Path $logDir "server-smoke-$stamp.err.log"

Write-Host "Starting the dedicated server for a smoke run..."
Write-Host "  log: $log"
$launcher = Start-Process -FilePath 'cmd.exe' `
    -ArgumentList @('/c', "`"$gradlew`" runServer --console=plain --no-daemon") `
    -WorkingDirectory $project `
    -RedirectStandardOutput $log `
    -RedirectStandardError $errLog `
    -NoNewWindow -PassThru

$deadline = (Get-Date).AddSeconds($SmokeSeconds)
$ready = $false
$modReady = $false
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 700
    if (Test-Path -LiteralPath $log) {
        $text = Get-Content -LiteralPath $log -Raw
        if ($text -match 'Done \(') { $ready = $true }
        if ($text -match 'VeriBorder server ready') { $modReady = $true }
        if ($ready -and $modReady) { break }
    }
    if ($launcher.HasExited) { break }
}

if ($ready -and $modReady) {
    Write-Host 'Server reached "Done (" and the mod logged its ready line.' -ForegroundColor Green
} else {
    Write-Host "Server did not become ready within $SmokeSeconds s." -ForegroundColor Red
}

if (-not $KeepRunning) {
    # `gradlew runServer` does not forward stdin, so the server cannot be asked to
    # "stop" through the console. Give the ready state a short grace period, then
    # terminate the whole process tree (launcher + server JVM) deterministically.
    Start-Sleep -Seconds 8
    if (-not $launcher.HasExited) {
        Write-Host 'Terminating the smoke server process tree (the ready state is already recorded).'
        & taskkill.exe /PID $launcher.Id /T /F *> $null
        try { $launcher.WaitForExit(30000) | Out-Null } catch { }
    }
}

$text = if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Raw } else { '' }
$errText = if (Test-Path -LiteralPath $errLog) { Get-Content -LiteralPath $errLog -Raw } else { '' }
$combined = $text + "`n" + $errText

# Client classes referenced from common code crash a dedicated server at class
# load; this list is the acceptance criterion for "no client class leakage".
$fatalPatterns = [ordered]@{
    'missing class'          = 'NoClassDefFoundError|ClassNotFoundException'
    'client class on server' = 'net\.minecraft\.client\.|com\.veriborder\.client\.'
    'fatal JVM error'        = 'A fatal error has been detected|SIGSEGV'
    'unhandled exception'    = 'Exception in thread|Encountered an unexpected exception|Failed to load class'
    'method/linkage error'   = 'NoSuchMethodError|VerifyError|IncompatibleClassChangeError'
}
$failures = [System.Collections.Generic.List[string]]::new()
foreach ($name in $fatalPatterns.Keys) {
    if ($combined -match $fatalPatterns[$name]) { $failures.Add($name) }
}

Write-Host ''
Write-Host '== dedicated-server smoke report =='
Write-Host ("  ready        : {0}" -f $ready)
Write-Host ("  mod ready    : {0}" -f $modReady)
if ($text -match 'VeriBorder server ready:[^\r\n]*') { Write-Host ("  mod line     : {0}" -f $Matches[0]) }
if ($failures.Count -eq 0) {
    Write-Host '  class check  : PASS (no client classes, no missing classes)' -ForegroundColor Green
    Write-Host "  log          : $log"
    if (-not $ready -or -not $modReady) { exit 1 }
    exit 0
}
Write-Host ("  class check  : FAIL ({0})" -f ($failures -join ', ')) -ForegroundColor Red
foreach ($name in $failures) {
    Write-Host "    -- $name --"
    $pattern = $fatalPatterns[$name]
    $matches = [regex]::Matches($combined, "(?m)^.*($pattern).*$")
    $matches | Select-Object -First 5 | ForEach-Object { Write-Host ("    {0}" -f $_.Value.Trim()) }
}
Write-Host "  log          : $log"
exit 1
