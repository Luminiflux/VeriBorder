# ---------------------------------------------------------------------------
# VeriBorder idempotent resource generator (single entry point)
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate.ps1 -Only devices
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate.ps1 -Only items,recipes
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate.ps1 -Root D:\scratch
#
# Each step is a self-contained, rerunnable generator; none of them reads state
# left behind by a previous run:
#
#   devices  generate_assets.ps1 -NoDocuments
#            device/entity textures, block models, blockstates, item models,
#            loot tables, block tags, brush icons, modifier icon, mod logo
#   items    generate_item_textures.ps1
#            the document card textures (passports, IDs, permit, custom covers)
#            plus the ink audit that fails the build on text past the card face
#   recipes  generate_recipes.ps1
#            data/veriborder/recipes/*.json
#
# "Rerun produces no diff" is verified by tools/check_generators.ps1, which
# regenerates into a scratch tree twice and compares both runs against the
# committed resources. That script is the gate; this one is the driver.
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    # Comma-separated step list: all | devices | items | recipes
    # (comma-separated so `powershell -File tools/generate.ps1 -Only items,recipes`
    # works; -File mode cannot bind several positional values to one parameter).
    [string]$Only = 'all',
    # Optional output root (defaults to this project). Must contain src\main\resources
    # for real output; a scratch copy keeps the working tree untouched.
    [string]$Root,
    # Forwarded to generate_item_textures.ps1: audit the committed cards only.
    [switch]$AuditOnly
)

$ErrorActionPreference = 'Stop'

$validSteps = @('devices', 'items', 'recipes')
$requested = @($Only -split '[,; ]+' | Where-Object { $_ } | ForEach-Object { $_.ToLowerInvariant() })
if ($requested -contains 'all' -or $requested.Count -eq 0) {
    $requested = $validSteps
}
foreach ($step in $requested) {
    if ($validSteps -notcontains $step) {
        throw "Unknown generator step '$step'. Valid steps: all, $($validSteps -join ', ')."
    }
}

$project = Split-Path -Parent $PSScriptRoot
$root = if ($Root) { (Resolve-Path -LiteralPath $Root).Path } else { $project }

$steps = [System.Collections.Generic.List[string]]::new()
foreach ($step in $validSteps) {
    if ($requested -contains $step) { $steps.Add($step) }
}

function Invoke-Step {
    param([string]$Name, [string]$Description, [scriptblock]$Action)
    Write-Host ''
    Write-Host "== $Name ==" -ForegroundColor Cyan
    if ($Description) { Write-Host "   $Description" -ForegroundColor DarkGray }
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    & $Action
    $watch.Stop()
    Write-Host ("   {0} finished in {1:0.0}s" -f $Name, $watch.Elapsed.TotalSeconds) -ForegroundColor Green
}

Write-Host "VeriBorder generator  root: $root"
Write-Host "steps: $($steps -join ', ')"

foreach ($step in $steps) {
    switch ($step) {
        'devices' {
            Invoke-Step 'devices' 'textures, models, blockstates, loot, tags, icons, logo' {
                & (Join-Path $PSScriptRoot 'generate_assets.ps1') -Root $root -NoDocuments
            }
        }
        'items' {
            Invoke-Step 'items' 'document card textures + ink audit' {
                if ($AuditOnly) {
                    & (Join-Path $PSScriptRoot 'generate_item_textures.ps1') -Root $root -AuditOnly
                } else {
                    & (Join-Path $PSScriptRoot 'generate_item_textures.ps1') -Root $root
                }
            }
        }
        'recipes' {
            Invoke-Step 'recipes' 'data/veriborder/recipes' {
                & (Join-Path $PSScriptRoot 'generate_recipes.ps1') -Root $root
            }
        }
    }
}

Write-Host ''
Write-Host "OK: generated $(($steps -join ', ')) under $root" -ForegroundColor Green
