# VeriBorder - device modeling acceptance check (throwaway script)
# Verifies JSON elements >= 10, textures complete, and Blender artifacts present,
# then lists the 8 front-preview PNG paths.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)))
$models = Join-Path $root 'src\main\resources\assets\veriborder\models\block'
$tex    = Join-Path $root 'src\main\resources\assets\veriborder\textures\block'
$blend  = Join-Path $root 'assets\blender'

$ids = @('hotel_reader','desk_scanner','manual_terminal','airport_checkin','station_verifier','blacklist_terminal','loss_terminal','renewal_machine')
$texNames = @('body','body2','trim','top','accent','detail','led','glass','paper','warning','screen','screen2')

$pass = 0
$fail = 0
$report = @()

foreach ($id in $ids) {
    $elCount = -1
    $texOk = $true
    $artOk = $true
    $problems = @()

    # 1) model JSON elements
    $mj = Join-Path $models "$id.json"
    if (Test-Path $mj) {
        try {
            $j = Get-Content $mj -Raw | ConvertFrom-Json
            $elCount = @($j.elements).Count
            if ($elCount -lt 10) { $problems += "elements=$elCount (<10)" }
        } catch { $problems += "invalid JSON" }
    } else {
        $problems += "missing model JSON"
    }

    # 2) textures
    foreach ($t in $texNames) {
        if (-not (Test-Path (Join-Path $tex "$id`_$t.png"))) { $texOk = $false; $problems += "missing texture $t" }
    }

    # 3) blender artifacts
    foreach ($a in @("$id.blend", "$id.obj", "${id}_front.png")) {
        if (-not (Test-Path (Join-Path $blend "$id\$a"))) { $artOk = $false; $problems += "missing $a" }
    }

    $ok = ($elCount -ge 10) -and $texOk -and $artOk
    if ($ok) { $pass++ } else { $fail++ }
    $status = if ($ok) { 'PASS' } else { 'FAIL' }
    $report += [pscustomobject]@{ id = $id; elements = $elCount; textures = $(if($texOk){'12/12'}else{'INCOMPLETE'}); artifacts = $(if($artOk){'ok'}else{'MISSING'}); status = $status; problems = ($problems -join '; ') }
}

$report | Format-Table id, elements, textures, artifacts, status -AutoSize
foreach ($r in $report) { if ($r.problems) { Write-Host ("  ! {0}: {1}" -f $r.id, $r.problems) } }

Write-Host ''
Write-Host "Front-preview PNG paths:"
foreach ($id in $ids) {
    Write-Host ("  " + (Join-Path $blend "$id\${id}_front.png"))
}

Write-Host ''
Write-Host "RESULT: $pass passed, $fail failed"
if ($fail -gt 0) { exit 1 }
