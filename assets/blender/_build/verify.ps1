# Acceptance check for the 4 stamp/print devices.
$ErrorActionPreference = 'Stop'
$root = "C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder"
$assets = Join-Path $root 'src\main\resources\assets\veriborder'
$devices = @('entry_stamp', 'exit_stamp', 'stamp_history', 'print_preview')
$requiredTex = @('body','body2','trim','top','accent','detail','led','glass','paper','warning','screen','screen2')

$allOk = $true
foreach ($id in $devices) {
    $modelJson = Join-Path $assets "models\block\$id.json"
    $j = Get-Content $modelJson -Raw | ConvertFrom-Json
    $elements = @($j.elements).Count
    $missingTex = @()
    foreach ($t in $requiredTex) {
        $p = Join-Path $assets "textures\block\$id`_$t.png"
        if (-not (Test-Path $p)) { $missingTex += $t }
    }
    $blendDir = Join-Path $root "assets\blender\$id"
    $blend = Join-Path $blendDir "$id.blend"
    $obj = Join-Path $blendDir "$id.obj"
    $png = Join-Path $blendDir "$id`_front.png"
    $blendOk = (Test-Path $blend) -and ((Get-Item $blend).Length -gt 1000)
    $objOk = (Test-Path $obj) -and ((Get-Item $obj).Length -gt 1000)
    $pngOk = (Test-Path $png) -and ((Get-Item $png).Length -gt 1000)

    $ok = ($elements -ge 10) -and ($missingTex.Count -eq 0) -and $blendOk -and $objOk -and $pngOk
    if (-not $ok) { $allOk = $false }
    Write-Output ("{0,-14} elements={1,3} (>=10:{2})  textures_missing={3,2}  blend={4} obj={5} png={6}  -> {7}" -f `
        $id, $elements, ($elements -ge 10), $missingTex.Count, $blendOk, $objOk, $pngOk, $(if ($ok) {'PASS'} else {'FAIL'}))
    if ($missingTex.Count) { Write-Output ("     missing textures: " + ($missingTex -join ', ')) }
}

Write-Output ""
Write-Output "=== Preview image paths ==="
foreach ($id in $devices) {
    Write-Output (Join-Path $root "assets\blender\$id\$id`_front.png")
}

Write-Output ""
if ($allOk) { Write-Output "RESULT: ALL PASS" } else { Write-Output "RESULT: FAIL" }
