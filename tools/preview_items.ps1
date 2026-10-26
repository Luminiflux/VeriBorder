# ---------------------------------------------------------------------------
# VeriBorder item before/after comparison sheets
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_items.ps1
#
# 1. renders the BEFORE state from the snapshot in tools\preview\item_before
#    (item models + item textures as they were before this round),
# 2. renders the AFTER state from the live assets,
# 3. composes one side-by-side sheet per family into tools\preview\:
#      items_devices_compare.png   the 6 device items + the brush (the style pilot)
#      items_docs_compare.png      the 3 document items (handed to the document line)
#
# Each cell shows BEFORE | AFTER plus the element count / canvas size, so a
# reviewer can see both the shape and the resolution change.
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [switch]$SkipRender
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$preview = Join-Path $root 'tools\preview'
$before = Join-Path $preview 'item_before'
$after = Join-Path $preview 'item_after'
New-Item -ItemType Directory -Force $before, $after | Out-Null

# The id whitelist for the renderer. Documents are excluded on purpose (the
# document line owns them and its models are builtin/entity, i.e. nothing to
# rasterize); the before-snapshot only ever contained the pilot + device ids.
$idList = 'id_issuer,passport_issuer,permit_issuer,visa_issuer,self_visa,endorsement_machine,' +
'self_endorsement,desk_scanner,station_verifier,hotel_reader,manual_terminal,airport_checkin,' +
'loss_terminal,renewal_machine,blacklist_terminal,print_preview,entry_stamp,exit_stamp,' +
'stamp_history,device_brush'

if (-not $SkipRender) {
    Write-Host '== rendering BEFORE (snapshot) =='
    & node (Join-Path $root 'tools\preview_items.js') `
        --models (Join-Path $before 'models') `
        --tex-item (Join-Path $before 'textures') `
        --out (Join-Path $before 'render') --ids $idList | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'before render failed' }
    Write-Host '== rendering AFTER (live assets) =='
    & node (Join-Path $root 'tools\preview_items.js') --out $after --ids $idList | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'after render failed' }
}

# ---- families --------------------------------------------------------------
# All 19 non-gate device items plus the config brush. Documents (id_card*,
# passport*, hk_macao_permit*, custom_*) are NOT here: they belong to the
# document line (its models are builtin/entity / BEWLR, so there is no model
# geometry to rasterize anyway).
$devices = @(
    @{ id = 'id_issuer'; label = 'id_issuer'; note = 'ISSUER tower' }
    @{ id = 'passport_issuer'; label = 'passport_issuer'; note = 'ISSUER tower (tall)' }
    @{ id = 'permit_issuer'; label = 'permit_issuer'; note = 'ISSUER tower (wide)' }
    @{ id = 'visa_issuer'; label = 'visa_issuer'; note = 'ISSUER tower (visa)' }
    @{ id = 'self_visa'; label = 'self_visa'; note = 'ISSUER tower (low, wide)' }
    @{ id = 'endorsement_machine'; label = 'endorsement_machine'; note = 'ISSUER tower (tall)' }
    @{ id = 'self_endorsement'; label = 'self_endorsement'; note = 'ISSUER tower (tallest)' }
    @{ id = 'desk_scanner'; label = 'desk_scanner'; note = 'READER wedge' }
    @{ id = 'station_verifier'; label = 'station_verifier'; note = 'READER wedge (tall)' }
    @{ id = 'hotel_reader'; label = 'hotel_reader'; note = 'READER wedge (low)' }
    @{ id = 'manual_terminal'; label = 'manual_terminal'; note = 'READER wedge (wide)' }
    @{ id = 'airport_checkin'; label = 'airport_checkin'; note = 'SERVICE kiosk (check-in)' }
    @{ id = 'loss_terminal'; label = 'loss_terminal'; note = 'SERVICE kiosk' }
    @{ id = 'renewal_machine'; label = 'renewal_machine'; note = 'SERVICE kiosk (tall)' }
    @{ id = 'blacklist_terminal'; label = 'blacklist_terminal'; note = 'SERVICE kiosk' }
    @{ id = 'print_preview'; label = 'print_preview'; note = 'SERVICE kiosk (print)' }
    @{ id = 'entry_stamp'; label = 'entry_stamp'; note = 'STAMP press' }
    @{ id = 'exit_stamp'; label = 'exit_stamp'; note = 'STAMP press (low)' }
    @{ id = 'stamp_history'; label = 'stamp_history'; note = 'STAMP press (lowest)' }
    @{ id = 'device_brush'; label = 'device_brush'; note = 'BRUSH (2D icon)' }
)

function Get-ModelMeta([string]$dir, [string]$id) {
    $file = Join-Path $dir "$id.json"
    if (-not (Test-Path -LiteralPath $file)) { return 'missing' }
    try { $m = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json } catch { return 'invalid JSON' }
    $els = @($m.elements)
    if ($els.Count -gt 0 -and $null -ne $els[0]) {
        $h = ($els | ForEach-Object { [double]$_.to[1] } | Measure-Object -Maximum).Maximum
        return ('{0} els / {1:0.#} px tall' -f $els.Count, $h)
    }
    $parent = $m.parent
    if ($parent) { return 'flat: ' + (($parent -split ':')[-1]) }
    return 'flat'
}

function Get-TexMeta([string]$texDir, [string]$id) {
    # resolve layer0 for a flat model, else report the chassis size
    $file = Join-Path $texDir "$id.png"
    if (Test-Path -LiteralPath $file) {
        $b = [System.Drawing.Bitmap]::new($file)
        $s = '{0}x{1}' -f $b.Width, $b.Height
        $b.Dispose()
        return $s
    }
    return '-'
}

function New-Sheet([string]$outFile, [string]$title, $items, [int]$cols = 3) {
    $rows = [int][Math]::Ceiling($items.Count / $cols)
    $cw = 560
    $ch = 330
    $header = 74
    $W = $cols * $cw
    $H = $header + $rows * $ch
    $bmp = [System.Drawing.Bitmap]::new($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
    $g.Clear([System.Drawing.Color]::FromArgb(255, 15, 20, 26))

    $fTitle = [System.Drawing.Font]::new('Segoe UI', 16, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $fSub = [System.Drawing.Font]::new('Consolas', 11, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $fName = [System.Drawing.Font]::new('Consolas', 13, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $fMeta = [System.Drawing.Font]::new('Consolas', 10, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $fTag = [System.Drawing.Font]::new('Consolas', 10, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)

    function Fill([int]$x, [int]$y, [int]$w, [int]$h, $c) {
        $b = [System.Drawing.SolidBrush]::new($c); $g.FillRectangle($b, $x, $y, $w, $h); $b.Dispose()
    }
    function Txt([string]$s, $f, $c, [int]$x, [int]$y) {
        $b = [System.Drawing.SolidBrush]::new($c); $g.DrawString($s, $f, $b, $x, $y); $b.Dispose()
    }

    Txt $title $fTitle ([System.Drawing.Color]::FromArgb(255, 226, 238, 246)) 18 12
    Txt 'left = BEFORE (shipped 26.4 state)   right = AFTER (this round)   numbers: model elements + icon canvas' `
        $fSub ([System.Drawing.Color]::FromArgb(255, 128, 168, 186)) 18 38
    Txt 'generated by tools/preview_items.ps1  .  renders by tools/preview_items.js (software rasterizer, 512x512)' `
        $fMeta ([System.Drawing.Color]::FromArgb(255, 96, 128, 144)) 18 54

    for ($i = 0; $i -lt $items.Count; $i++) {
        $it = $items[$i]
        $col = $i % $cols
        $row = [int][Math]::Floor($i / $cols)
        $x = $col * $cw
        $y = $header + $row * $ch
        Fill ($x + 3) ($y + 3) ($cw - 6) ($ch - 6) ([System.Drawing.Color]::FromArgb(255, 22, 29, 37))
        Fill ($x + 3) ($y + 3) ($cw - 6) 1 ([System.Drawing.Color]::FromArgb(255, 42, 54, 66))

        $imgSize = 248
        $gap = 10
        $bx = $x + 16
        $ax = $bx + $imgSize + $gap
        $iy = $y + 44

        $beforeFile = Join-Path $before "render\$($it.id).png"
        $afterFile = Join-Path $after "$($it.id).png"
        foreach ($pair in @(@($beforeFile, $bx, 'BEFORE'), @($afterFile, $ax, 'AFTER'))) {
            $file = $pair[0]; $px = $pair[1]; $tag = $pair[2]
            $tagColor = if ($tag -eq 'BEFORE') { [System.Drawing.Color]::FromArgb(255, 150, 120, 110) } else { [System.Drawing.Color]::FromArgb(255, 120, 200, 160) }
            Txt $tag $fTag $tagColor ($px + 2) ($y + 14)
            if (Test-Path -LiteralPath $file) {
                $img = [System.Drawing.Image]::FromFile($file)
                $g.DrawImage($img, [System.Drawing.Rectangle]::new($px, $iy, $imgSize, $imgSize))
                $img.Dispose()
            } else {
                Txt '(no render)' $fMeta ([System.Drawing.Color]::FromArgb(255, 120, 100, 100)) ($px + 6) ($iy + 100)
            }
            Fill ($px - 1) ($iy - 1) ($imgSize + 2) 1 ([System.Drawing.Color]::FromArgb(255, 38, 48, 58))
        }

        Txt $it.label $fName ([System.Drawing.Color]::FromArgb(255, 214, 232, 242)) ($x + 16) ($y + $ch - 62)
        Txt $it.note $fMeta ([System.Drawing.Color]::FromArgb(255, 132, 168, 184)) ($x + 16) ($y + $ch - 44)
        $bm = Get-ModelMeta (Join-Path $before 'models') $it.id
        $am = Get-ModelMeta (Join-Path $root 'src\main\resources\assets\veriborder\models\item') $it.id
        $bmTex = Get-TexMeta (Join-Path $before 'textures') $it.id
        $amTex = Get-TexMeta (Join-Path $root 'src\main\resources\assets\veriborder\textures\item') $it.id
        $line = 'before: {0}   icon {1}' -f $bm, $bmTex
        Txt $line $fMeta ([System.Drawing.Color]::FromArgb(255, 150, 120, 110)) ($x + 16) ($y + $ch - 27)
        $line2 = 'after:  {0}   icon {1}' -f $am, $amTex
        Txt $line2 $fMeta ([System.Drawing.Color]::FromArgb(255, 120, 200, 160)) ($x + 16) ($y + $ch - 14)
    }

    $bmp.Save($outFile, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
    Write-Host ("sheet: {0} ({1} x {2}, {3} items)" -f $outFile, $W, $H, $items.Count)
}

New-Sheet (Join-Path $preview 'items_devices_compare.png') `
    'VeriBorder 26.4.1  -  device + tool item upgrade (all 19 devices)' $devices 4
