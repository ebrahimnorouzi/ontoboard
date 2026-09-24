<#
    Does this jar resolve and start in a real Protege, at the version we think it is?

    Written because the answer was a memory. Sixteen OntoBoard versions have loaded in this
    machine's Protege installs, and the log of those loads contains a defect that 1,062 unit tests
    and four audit rounds did not find. Then the plugin stopped being installed after 1.19.0 and
    five releases shipped without the only oracle that has ever caught something the suite could
    not. This turns that oracle into a command with an exit code.

    What it proves
        - exactly one ontoboard bundle resolved, and Felix started it
        - the version that started is the version in pom.xml
        - nothing in our own package threw during startup
        - no OSGi resolution failure, no classfile-version failure

    What it does NOT prove
        - that the OntoBoard tab renders, or that any menu item works. Protege restores the last
          workspace, so the tab may or may not open, and a killed process never logs its shutdown.
          Asserting on the tab here would be asserting on the previous session's layout. Driving
          the actions is Phase 2's self-test bundle.

    Usage
        pwsh -File tools/smoke.ps1 -Install "$HOME\Documents\Protege-5.6.9" -Version 1.24.0
        pwsh -File tools/smoke.ps1 -Install ... -Heap 2G -Ontology C:\path\to\ont.owl

    Exit codes
        0  resolved and started at the expected version, nothing thrown
        1  a check failed - the failing log lines are printed
        2  could not run at all (bad install path, no java, no log)
#>
[CmdletBinding()]
param(
    # A Protege install directory: the one containing run.bat, jre\ and plugins\.
    [Parameter(Mandatory = $true)][string] $Install,

    # The version the jar in plugins\ should report. Read from pom.xml if omitted.
    [string] $Version,

    # Heap for this run. The default is deliberately 500M, not run.bat's 32G: Protege.l4j.ini ships
    # -Xmx500M, so that is what a user double-clicking Protege.exe actually gets. An OutOfMemoryError
    # that only appears at 500M is a defect our users hit and we never would.
    [string] $Heap = '500M',

    # An ontology to open. Optional, but a run that opens nothing exercises very little.
    [string] $Ontology,

    # How long to wait for the startup markers before giving up.
    [int] $WaitSeconds = 120
)

$ErrorActionPreference = 'Stop'

function Fail([string] $message, [int] $code = 2) {
    Write-Host ''
    Write-Host "smoke: $message" -ForegroundColor Red
    exit $code
}

# ---------------------------------------------------------------- what we are testing

if (-not (Test-Path -LiteralPath $Install)) { Fail "no such install: $Install" }
$runBat = Join-Path $Install 'run.bat'
if (-not (Test-Path -LiteralPath $runBat)) { Fail "no run.bat in $Install - is that a Protege install?" }

if (-not $Version) {
    $pom = Join-Path $PSScriptRoot '..\pom.xml'
    if (-not (Test-Path -LiteralPath $pom)) { Fail 'no -Version given and pom.xml not found' }
    $Version = (Select-String -LiteralPath $pom -Pattern '^  <version>(.+)</version>' |
        Select-Object -First 1).Matches[0].Groups[1].Value
}

$plugins = Join-Path $Install 'plugins'
$jars = @(Get-ChildItem -LiteralPath $plugins -Filter 'ontoboard-*.jar' -ErrorAction SilentlyContinue)
if ($jars.Count -eq 0) { Fail "no ontoboard jar in $plugins" }
if ($jars.Count -gt 1) {
    # Bundle-SymbolicName is ontoboard;singleton:=true, so two jars is an unresolved collision
    # rather than an upgrade - and Felix says nothing a user would connect to it.
    Fail ("two ontoboard jars in plugins - singleton bundle, so neither will resolve: " +
        ($jars.Name -join ', ')) 1
}

Write-Host "install   $Install"
Write-Host "jar       $($jars[0].Name)  ($([math]::Round($jars[0].Length / 1MB, 1)) MB)"
Write-Host "expecting Plugin: OntoBoard ($Version) and OntoBoard self-check: PASS"
Write-Host "heap      -Xmx$Heap"

# ---------------------------------------------------------------- the launch line, from run.bat

# Read it rather than hardcoding: 5.6.9 puts the launcher and Felix under bundles\ and adds
# glassfish-corba-orb.jar, where 5.5.0 has them under bin\. A hardcoded classpath silently tests
# one install twice.
$launchLine = (Get-Content -LiteralPath $runBat | Where-Object { $_ -match 'jre\\bin\\java' } |
    Select-Object -First 1)
if (-not $launchLine) { Fail "could not find the java line in $runBat" }

$arguments = $launchLine -replace '^\s*jre\\bin\\java\s+', '' -replace '\s*%1\s*$', ''
$arguments = $arguments -replace '-Xmx\S+', "-Xmx$Heap"
if ($Ontology) {
    if (-not (Test-Path -LiteralPath $Ontology)) { Fail "no such ontology: $Ontology" }
    $arguments = "$arguments `"$Ontology`""
}

$java = Join-Path $Install 'jre\bin\java.exe'
if (-not (Test-Path -LiteralPath $java)) { Fail "no bundled jre at $java" }

# ---------------------------------------------------------------- watch only the new log

# protege.log is 67 MB on this machine. Reading it whole to find three lines is its own problem, so
# remember where it ends and read only past that afterwards.
$log = Join-Path $HOME '.Protege\logs\protege.log'
$offset = 0
if (Test-Path -LiteralPath $log) { $offset = (Get-Item -LiteralPath $log).Length }

$started = $null
try {
    $started = Start-Process -FilePath $java -ArgumentList $arguments `
        -WorkingDirectory $Install -PassThru
} catch {
    Fail "could not launch: $($_.Exception.Message)"
}

# Protege installs several plugins; their failures are not ours. Every fatal pattern below is
# therefore matched only on a line that also names our bundle, or on a "failed to install" line
# whose bundle IS ours. Without this the tool blames OntoBoard for whatever else is broken in the
# user's plugins directory - which is what it did the first time it ran.
function Get-OurFailures([string] $text) {
    $found = @()
    if (-not $text) { return $found }

    foreach ($line in ($text -split "`r?`n")) {
        if ($line -notmatch 'ontoboard') { continue }
        if ($line -match 'failed to install|unresolved constraint|Importing java\.\* packages not allowed') {
            $found += $line.Trim()
        }
    }
    # Stack frames in our own package are ours by definition, whatever the line says about bundles.
    foreach ($m in [regex]::Matches($text, '.*de\.fizkarlsruhe\.ise\.ontoboard.*(Exception|Error).*')) {
        $found += $m.Value.Trim()
    }
    return $found
}

function Test-OurFailure([string] $text) {
    return (Get-OurFailures $text).Count -gt 0
}

function New-LogSlice {
    if (-not (Test-Path -LiteralPath $log)) { return '' }
    $stream = [System.IO.File]::Open($log, 'Open', 'Read', 'ReadWrite')
    try {
        if ($stream.Length -le $offset) { return '' }
        $stream.Seek($offset, 'Begin') | Out-Null
        $bytes = New-Object byte[] ($stream.Length - $offset)
        $read = $stream.Read($bytes, 0, $bytes.Length)
        return [System.Text.Encoding]::UTF8.GetString($bytes, 0, $read)
    } finally {
        $stream.Dispose()
    }
}

Write-Host -NoNewline 'waiting   '
$slice = ''
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    Write-Host -NoNewline '.'
    $slice = New-LogSlice
    if ($slice -match 'OntoBoard self-check: (PASS|FAIL)') { break }
    if (Test-OurFailure $slice) { break }
    if ($started.HasExited) { break }
}
Write-Host ''

if (-not $started.HasExited) {
    # Protege is a desktop application; there is no clean scripted shutdown, so it is killed. That
    # is why nothing here asserts on a shutdown marker.
    try { $started.Kill() } catch { }
    $started.WaitForExit(15000) | Out-Null
}
$slice = New-LogSlice
if (-not $slice) { Fail "nothing was written to $log - did Protege start at all?" }

# ---------------------------------------------------------------- the checks

$problems = @()

if ($slice -notmatch 'Starting bundle ontoboard') {
    $problems += 'Felix never started the ontoboard bundle.'
}

$versionMatch = [regex]::Match($slice, 'Plugin: OntoBoard \(([^)]+)\)')
if (-not $versionMatch.Success) {
    $problems += 'Protege never logged "Plugin: OntoBoard (...)", so the bundle did not activate.'
} elseif ($versionMatch.Groups[1].Value -ne $Version) {
    # The point of the check. Felix keys its cache on symbolic name AND version, so a stale cached
    # bundle can run while a newer jar sits in plugins/ - which is why the version is asserted and
    # not just the presence of a line.
    $problems += ("resolved OntoBoard $($versionMatch.Groups[1].Value), expected $Version - " +
        'a stale Felix cache, or the wrong jar')
}

# The self-check. This is the assertion that makes a release receipt worth having: the plugin runs
# ROBOT's report over a small ontology inside the bundle and says so. Protege 5.x opens an empty
# ontology at startup, which builds an OWLEditorKit, which is what invokes the hook - so an absent
# verdict means the hook did not run, and that is itself a defect worth failing on.
$verdict = [regex]::Match($slice, 'OntoBoard self-check: (PASS|FAIL) (\d+)/(\d+)')
if (-not $verdict.Success) {
    $problems += ('OntoBoard never logged a self-check verdict. The EditorKitHook ' +
        '(OntoBoardStartup) did not run, so nothing confirms ROBOT works inside the bundle.')
} elseif ($verdict.Groups[1].Value -ne 'PASS') {
    $failedChecks = [regex]::Matches($slice, '.*OntoBoard self-check FAILED:.*')
    $problems += "self-check $($verdict.Groups[2].Value)/$($verdict.Groups[3].Value) checks passed"
    foreach ($hit in $failedChecks) { $problems += "  $($hit.Value.Trim())" }
}

foreach ($failure in (Get-OurFailures $slice)) { $problems += $failure }

# These two name no bundle, so they are matched anywhere in the slice. A truncated jar and a
# classfile the JRE cannot read are both fatal to us and neither is something another plugin's
# failure would produce in our startup window.
foreach ($pattern in @('UnsupportedClassVersionError', 'zip END header not found')) {
    $hit = [regex]::Match($slice, ".*$pattern.*")
    if ($hit.Success) { $problems += $hit.Value.Trim() }
}

$outOfMemory = [regex]::Matches($slice, '.*OutOfMemoryError.*')
foreach ($hit in $outOfMemory) { $problems += "out of memory at -Xmx$Heap : $($hit.Value.Trim())" }

# ---------------------------------------------------------------- the verdict

if ($problems.Count -gt 0) {
    Write-Host ''
    Write-Host "FAILED  $Install  -Xmx$Heap" -ForegroundColor Red
    foreach ($problem in $problems) { Write-Host "  - $problem" -ForegroundColor Red }
    Write-Host ''
    Write-Host "The full slice of this run is in $log past byte $offset."
    exit 1
}

$othersFailed = [regex]::Matches($slice, '.*Bundle plugins.*failed to install.*')
if ($othersFailed.Count -gt 0) {
    Write-Host ''
    Write-Host 'Other plugins in this install failed to load. Not ours, not fixed here, but worth'
    Write-Host 'knowing - a broken neighbour can change what resolves:'
    foreach ($other in $othersFailed) {
        if ($other.Value -notmatch 'ontoboard') { Write-Host "  - $($other.Value.Trim())" }
    }
}

Write-Host ''
Write-Host "PASSED  OntoBoard $Version resolved and started  ($Install, -Xmx$Heap)" -ForegroundColor Green
# Quoted verbatim so a release receipt can record what the host actually reported, rather than the
# script's summary of it.
foreach ($line in [regex]::Matches($slice, '.*OntoBoard self-check.*')) {
    Write-Host "  $($line.Value.Trim() -replace '^.*?OntoBoard self-check', 'OntoBoard self-check')"
}
if ($slice -match "Saved tab state for 'OntoBoard' tab") {
    Write-Host '        the OntoBoard tab was open in the restored workspace'
} else {
    Write-Host '        (the tab did not report opening - not a failure here; see the header)'
}
exit 0
