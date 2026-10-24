# ---------------------------------------------------------------------------
# VeriBorder generator drift / idempotency gate
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_generators.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_generators.ps1 -Only devices
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_generators.ps1 -KeepTemp
#
# Answers the one question a generator must answer: "running it again changes
# nothing". It never touches the working copy:
#
#   1. copy src\main\resources into a scratch root,
#   2. delete the trees the requested generators own (so only freshly generated
#      files remain),
#   3. run tools/generate.ps1 against the scratch root,
#   4. run it a second time and compare those two runs byte for byte
#      (idempotency / determinism),
#   5. compare run 1 with the committed resources (drift: the committed files
#      are not what the generator produces).
#
# Drift policy: `recipes` and `items` artefacts are current generator output, so
# drift there is a hard failure. The `devices` block models/textures were
# replaced in round 9 by the bespoke bbmodel/painterly pipeline (see
# tools/archive/build_r9_*.js and gen_*.ps1); generate_assets.ps1 still renders
# the earlier procedural set, so its output deliberately differs from the
# committed art. That drift is reported as a warning; pass -Strict to make it
# fatal as well (useful after the art pipeline is folded back in).
#
# Exit code 0 = deterministic generators and no fatal drift. 1 = non-determinism
# or drift in a current step (see the table).
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    # Comma-separated step list: all | devices | items | recipes
    # (`-File` mode cannot bind several values to one parameter, so this is a
    # single comma-separated string).
    [string]$Only = 'all',
    # Treat legacy-art drift (devices) as a failure too.
    [switch]$Strict,
    [switch]$KeepTemp
)

$ErrorActionPreference = 'Stop'

$validSteps = @('devices', 'items', 'recipes')
$requestedSteps = @($Only -split '[,; ]+' | Where-Object { $_ } | ForEach-Object { $_.ToLowerInvariant() })
if ($requestedSteps -contains 'all' -or $requestedSteps.Count -eq 0) {
    $requestedSteps = $validSteps
}
foreach ($step in $requestedSteps) {
    if ($validSteps -notcontains $step) {
        throw "Unknown generator step '$step'. Valid steps: all, $($validSteps -join ', ')."
    }
}
$onlyList = @($validSteps | Where-Object { $requestedSteps -contains $_ })

$project = Split-Path -Parent $PSScriptRoot
$liveRoot = Join-Path $project 'src\main\resources'
if (-not (Test-Path -LiteralPath $liveRoot)) {
    throw "Committed resources not found: $liveRoot"
}

# Paths (relative to src\main\resources) owned by each generator step. A
# directory is deleted from the scratch tree, a file is removed, so the
# comparison only ever sees files the generator actually wrote.
$stepScopes = @{
    devices = @(
        'assets\veriborder\textures\block',
        'assets\veriborder\textures\entity',
        'assets\veriborder\models\block',
        'assets\veriborder\models\item',
        'assets\veriborder\blockstates',
        'data\veriborder\loot_tables\blocks',
        'data\minecraft\tags\blocks',
        'veriborder.png',
        'assets\veriborder\textures\item\device_brush.png',
        'assets\veriborder\textures\item\dual_gate_brush.png',
        'assets\veriborder\textures\item\gate_config_brush.png',
        'assets\veriborder\textures\item\document_modifier.png'
    )
    items   = @('assets\veriborder\textures\item')
    recipes = @('data\veriborder\recipes')
}

$steps = $onlyList
$scopes = [System.Collections.Generic.List[string]]::new()
foreach ($step in $steps) {
    foreach ($scope in $stepScopes[$step]) {
        if (-not $scopes.Contains($scope)) { $scopes.Add($scope) }
    }
}

# Files that are not generator output by definition.
$ignoredPatterns = @('*.bak', '*.tmp', 'Thumbs.db', '.DS_Store', 'desktop.ini')
function Test-Ignored([string]$name) {
    foreach ($pattern in $ignoredPatterns) {
        if ($name -like $pattern) { return $true }
    }
    return $false
}

function Get-GeneratedManifest {
    param([string]$Root, [string[]]$ScopePaths)
    $manifest = [System.Collections.Generic.Dictionary[string, string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    foreach ($scope in $ScopePaths) {
        $path = Join-Path $Root $scope
        if (-not (Test-Path -LiteralPath $path)) { continue }
        $item = Get-Item -LiteralPath $path
        $files = if ($item.PSIsContainer) {
            Get-ChildItem -LiteralPath $path -Recurse -File | Where-Object { -not (Test-Ignored $_.Name) }
        } else {
            @($item)
        }
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($Root.Length).TrimStart('\')
            $manifest[$relative] = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
        }
    }
    return $manifest
}

function Compare-Manifests {
    param($Left, $Right)
    $differences = [System.Collections.Generic.List[string]]::new()
    foreach ($key in $Left.Keys) {
        if (-not $Right.ContainsKey($key)) {
            $differences.Add("missing:$key")
        } elseif ($Left[$key] -ne $Right[$key]) {
            $differences.Add("changed:$key")
        }
    }
    foreach ($key in $Right.Keys) {
        if (-not $Left.ContainsKey($key)) { $differences.Add("extra:$key") }
    }
    return $differences
}

# Which generator step owns a generated path (first matching step, in pipeline
# order). Used to decide whether drift is a hard failure (current steps) or a
# known legacy-art difference (devices).
function Get-StepForPath([string]$Relative) {
    $normalized = $Relative.Replace('/', '\')
    if ($normalized -like 'data\veriborder\recipes\*') { return 'recipes' }
    if ($normalized -like 'assets\veriborder\textures\item\*') {
        $name = [System.IO.Path]::GetFileName($normalized)
        $deviceIcons = @('device_brush.png', 'dual_gate_brush.png', 'gate_config_brush.png',
            'document_modifier.png')
        return if ($deviceIcons -contains $name) { 'devices' } else { 'items' }
    }
    return 'devices'
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$temp = Join-Path ([System.IO.Path]::GetTempPath()) "vb_gen_check_$PID"
if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
$scratchResources = Join-Path $temp 'src\main\resources'
New-Item -ItemType Directory -Force $scratchResources | Out-Null
Copy-Item -LiteralPath $liveRoot -Destination $scratchResources -Recurse -Force

Write-Host "VeriBorder generator gate"
Write-Host "  steps    : $($steps -join ', ')"
Write-Host "  scratch  : $temp"
Write-Host "  reference: $liveRoot"

try {
    # 2. Drop the generated trees so only fresh output is compared.
    foreach ($scope in $scopes) {
        $path = Join-Path $scratchResources $scope
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Recurse -Force
        }
    }

    $log = Join-Path $temp 'generate.log'
    Write-Host ''
    Write-Host '== run 1: regenerate into scratch ==' -ForegroundColor Cyan
    $global:LASTEXITCODE = 0
    & (Join-Path $PSScriptRoot 'generate.ps1') -Root $temp -Only ($steps -join ',') *>&1 | Tee-Object -FilePath $log
    if ($LASTEXITCODE -ne 0) { throw "generate.ps1 failed (exit $LASTEXITCODE); see $log" }
    $first = Get-GeneratedManifest -Root $scratchResources -ScopePaths $scopes

    Write-Host ''
    Write-Host '== run 2: rerun to prove idempotency ==' -ForegroundColor Cyan
    $global:LASTEXITCODE = 0
    & (Join-Path $PSScriptRoot 'generate.ps1') -Root $temp -Only ($steps -join ',') *>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "generate.ps1 rerun failed (exit $LASTEXITCODE)" }
    $second = Get-GeneratedManifest -Root $scratchResources -ScopePaths $scopes

    $committed = Get-GeneratedManifest -Root $liveRoot -ScopePaths $scopes

    $unstable = Compare-Manifests -Left $first -Right $second
    # Drift only looks at files the generator produced: committed resources may
    # legitimately contain hand-written assets and outputs of other generators
    # (a new item model, a hand-painted texture), so "extra in the repository"
    # is not drift.
    $drift = [System.Collections.Generic.List[string]]::new()
    foreach ($key in $first.Keys) {
        if (-not $committed.ContainsKey($key)) {
            $drift.Add("missing-in-repository:$key")
        } elseif ($committed[$key] -ne $first[$key]) {
            $drift.Add("changed:$key")
        }
    }

    Write-Host ''
    Write-Host "generated files: $($first.Count)"
    $rows = foreach ($key in ($first.Keys | Sort-Object)) {
        [pscustomobject]@{
            file = $key
            inRepository = $committed.ContainsKey($key)
            same = $committed.ContainsKey($key) -and $committed[$key] -eq $first[$key]
            stable = $second.ContainsKey($key) -and $second[$key] -eq $first[$key]
        }
    }
    if ($rows) { $rows | Format-Table -AutoSize | Out-String -Width 200 | Write-Host }

    if ($unstable.Count -eq 0) {
        Write-Host 'IDEMPOTENT: a second run produced byte-identical files' -ForegroundColor Green
    } else {
        Write-Host "NOT IDEMPOTENT: $($unstable.Count) file(s) changed on the second run" -ForegroundColor Red
        $unstable | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    }

    $fatal = [System.Collections.Generic.List[string]]::new()
    $legacy = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in $drift) {
        $relative = ($entry -split ':', 2)[1]
        if ((Get-StepForPath $relative) -eq 'devices' -and -not $Strict) {
            $legacy.Add($entry)
        } else {
            $fatal.Add($entry)
        }
    }

    if ($drift.Count -eq 0) {
        Write-Host 'NO DRIFT: committed resources are exactly what the generators produce' -ForegroundColor Green
    } else {
        Write-Host "DRIFT: $($drift.Count) generated file(s) differ from the committed resources" -ForegroundColor Yellow
        $drift | ForEach-Object { Write-Host "  $_" -ForegroundColor Yellow }
    }
    if ($legacy.Count -gt 0) {
        Write-Host ("  note: {0} of them are the round-9 bespoke device art (legacy generator); " -f $legacy.Count)
        Write-Host '        pass -Strict to make that drift fatal as well.'
    }

    if ($unstable.Count -gt 0 -or $fatal.Count -gt 0) {
        exit 1
    }
    Write-Host ''
    Write-Host 'PASS: generators are idempotent; current steps reproduce the committed files' -ForegroundColor Green
    exit 0
} finally {
    if (-not $KeepTemp -and (Test-Path -LiteralPath $temp)) {
        Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
    } elseif ($KeepTemp) {
        Write-Host "scratch tree kept at $temp"
    }
}
