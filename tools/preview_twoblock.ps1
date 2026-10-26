# ---------------------------------------------------------------------------
# VeriBorder 26.2.1 - two-block device item comparison sheet
#
#   node tools/preview_item_gui.js                                       (1) render
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_twoblock.ps1   (2) compose
#   powershell ... -File .\tools\preview_twoblock.ps1 -SkipRender        (re-compose only)
#
# Writes tools\preview\items_twoblock.png: every two-block device
# (DeviceBlock.TALL, read by tools/lib/device_tall.js) inside a vanilla inventory
# slot, BEFORE vs AFTER the item-model fix.
#
#   BEFORE  the shipped one-block icon: lower storey only, so the machine looks
#           like a desktop unit and the reported "two-block machine shows up as a
#           one-block item" bug is visible,
#   AFTER   the merged model: lower tier ++ extension tier shifted +16, with the
#           gui display scale normalised by height so it still fits one block.
#
# The slot art is drawn here (18x18 slot units at 8 px/unit = 144 px, inner
# 16x16 = 128 px); the item pixels come from tools/preview_item_gui.js, which
# applies each model's own "display": {"gui": ...} transform at that same fixed
# zoom, so the cells are the pixels the game draws rather than an auto-fit.
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [switch]$SkipRender
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$preview = Join-Path $root 'tools\preview'
$work = Join-Path $preview 'twoblock'
$outFile = Join-Path $preview 'items_twoblock.png'

if (-not $SkipRender) {
    Write-Host '== rendering GUI slots (before / after) =='
    & node (Join-Path $root 'tools\preview_item_gui.js') --out $work | ForEach-Object { Write-Host "   $_" }
    if ($LASTEXITCODE -ne 0) { throw 'preview_item_gui.js failed' }
}
if (-not (Test-Path -LiteralPath (Join-Path $work 'manifest.json'))) {
    throw "missing $work\manifest.json - run: node tools/preview_item_gui.js"
}
$manifest = Get-Content -LiteralPath (Join-Path $work 'manifest.json') -Raw | ConvertFrom-Json
$rows = @($manifest.rows)
$blockRefPx = [double]$manifest.blockRefPx

# ---- layout ---------------------------------------------------------------
$margin = 18
$labelW = 470
$slotPx = 128            # inner slot
$unit = [int]($slotPx / 16)   # 8 px per slot unit
$slotOuter = [int]($slotPx + 2 * $unit)
$barW = 150              # 1-block reference column
$rowH = 178
$headerH = 118
$footerH = 128
$refH = 168
$W = $margin + $labelW + $slotOuter + 40 + $slotOuter + 26 + $barW + $margin
$H = $headerH + $rows.Count * $rowH + $refH + $footerH

$bmp = [System.Drawing.Bitmap]::new($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$g.Clear([System.Drawing.Color]::FromArgb(255, 15, 20, 26))

$fTitle = [System.Drawing.Font]::new('Segoe UI', 17, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fSub = [System.Drawing.Font]::new('Consolas', 11.5, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$fName = [System.Drawing.Font]::new('Consolas', 14, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fMeta = [System.Drawing.Font]::new('Consolas', 10.5, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$fTag = [System.Drawing.Font]::new('Consolas', 11, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)

$colBg = [System.Drawing.Color]::FromArgb(255, 22, 29, 37)
$colLine = [System.Drawing.Color]::FromArgb(255, 42, 54, 66)
$colTitle = [System.Drawing.Color]::FromArgb(255, 226, 238, 246)
$colSub = [System.Drawing.Color]::FromArgb(255, 128, 168, 186)
$colDim = [System.Drawing.Color]::FromArgb(255, 96, 128, 144)
$colBefore = [System.Drawing.Color]::FromArgb(255, 176, 132, 118)
$colAfter = [System.Drawing.Color]::FromArgb(255, 120, 200, 160)
$colSlotBorder = [System.Drawing.Color]::FromArgb(255, 55, 55, 55)
$colSlotInner = [System.Drawing.Color]::FromArgb(255, 139, 139, 139)
$colSlotTop = [System.Drawing.Color]::FromArgb(255, 116, 116, 116)
$colBar = [System.Drawing.Color]::FromArgb(255, 110, 150, 170)

function Fill([int]$x, [int]$y, [int]$w, [int]$h, $c) {
    $b = [System.Drawing.SolidBrush]::new($c); $g.FillRectangle($b, $x, $y, $w, $h); $b.Dispose()
}
function Txt([string]$s, $f, $c, [int]$x, [int]$y) {
    $b = [System.Drawing.SolidBrush]::new($c); $g.DrawString($s, $f, $b, $x, $y); $b.Dispose()
}
function Line([int]$x, [int]$y, [int]$w, [int]$h, $c) { Fill $x $y $w $h $c }

# vanilla-shaped slot: dark 1-unit frame around a 16x16 inner field
function New-Slot([int]$x, [int]$y) {
    Fill $x $y $slotOuter $slotOuter $colSlotBorder
    Fill ($x + $unit) ($y + $unit) $slotPx $slotPx $colSlotInner
    Line ($x + $unit) ($y + $unit) $slotPx ([int]($unit / 2)) $colSlotTop
}
function Put-Item([string]$file, [int]$x, [int]$y) {
    if (-not (Test-Path -LiteralPath $file)) { return $false }
    $img = [System.Drawing.Image]::FromFile($file)
    $g.DrawImage($img, [System.Drawing.Rectangle]::new([int]($x + $unit), [int]($y + $unit), [int]$slotPx, [int]$slotPx))
    $img.Dispose()
    return $true
}

# ---- header ---------------------------------------------------------------
Txt 'VeriBorder 26.2.1  -  two-block devices in the GUI slot' $fTitle $colTitle $margin 12
Txt ('left = BEFORE (shipped one-block icon, upper half missing)      right = AFTER (lower tier ++ extension tier shifted +16, gui scale normalised)') `
    $fSub $colSub $margin 40
Txt ('registry: ' + $manifest.registry + '  (' + $rows.Count + ' devices)   .   slots drawn at 8 px / model unit: one inventory slot = 16 x 16 units = ' + $slotPx + ' px inner') `
    $fMeta $colDim $margin 60
Txt ('gui: scale = 0.625 * 16 / bbox-height, translation.y = (16 - bbox-height) / 2   .   "1 blk" bar = a plain 16x16x16 block item, measured at ' + ('{0:0.00}' -f $blockRefPx) + ' px') `
    $fMeta $colDim $margin 76
Line $margin ($headerH - 12) ($W - 2 * $margin) 1 $colLine

# ---- rows -----------------------------------------------------------------
$xBefore = $margin + $labelW
$xAfter = $xBefore + $slotOuter + 40
$xBar = $xAfter + $slotOuter + 26

for ($i = 0; $i -lt $rows.Count; $i++) {
    $r = $rows[$i]
    $y = $headerH + $i * $rowH
    Fill ($margin - 8) $y ($W - 2 * ($margin - 8)) ($rowH - 8) $colBg
    Line ($margin - 8) $y ($W - 2 * ($margin - 8)) 1 $colLine

    $b = $r.before
    $a = $r.after
    Txt $r.id $fName $colTitle $margin ($y + 12)
    Txt ('2-block machine   bbox ' + ('{0:0.0}' -f $a.height) + ' px   gui ' + $a.guiScale + '   shift ' + $a.guiShift) `
        $fMeta $colSub $margin ($y + 34)
    if ($null -ne $b) {
        Txt ('before: ' + $b.elements + ' els   ' + ('{0:0.0}' -f $b.height) + ' px   gui ' + $b.guiScale + '   ' + ('{0:0.0}' -f $b.visualSlotPx) + ' px screen') `
            $fMeta $colBefore $margin ($y + 54)
    } else {
        Txt 'before: (no snapshot)' $fMeta $colBefore $margin ($y + 54)
    }
    Txt ('after:  ' + $a.elements + ' els   ' + ('{0:0.0}' -f $a.height) + ' px   seam y=16   ' + ('{0:0.0}' -f $a.visualSlotPx) + ' px screen') `
        $fMeta $colAfter $margin ($y + 72)
    if ($null -ne $b -and $b.visualSlotPx -gt 0) {
        $delta = $a.visualSlotPx - $b.visualSlotPx
        $sign = if ($delta -ge 0) { '+' } else { '' }
        Txt ('gap fixed: +' + ('{0:0.0}' -f ($a.height - $b.height)) + ' px of model height, on-screen ' + $sign + ('{0:0.0}' -f $delta) + ' px') `
            $fMeta $colDim $margin ($y + 90)
    }

    # BEFORE slot
    Txt 'BEFORE' $fTag $colBefore ($xBefore + 2) ($y + 10)
    New-Slot $xBefore ($y + 28) | Out-Null
    if ($null -ne $b) { Put-Item (Join-Path $work ($r.id + '_before.png')) $xBefore ($y + 28) | Out-Null }

    # AFTER slot
    Txt 'AFTER' $fTag $colAfter ($xAfter + 2) ($y + 10)
    New-Slot $xAfter ($y + 28) | Out-Null
    Put-Item (Join-Path $work ($r.id + '_after.png')) $xAfter ($y + 28) | Out-Null

    # 1-block reference bar + the icon's own on-screen height tick
    $slotTop = $y + 28 + $unit
    $midY = [int]($slotTop + $slotPx / 2)
    $barTop = [int]($midY - $blockRefPx * $unit / 2)
    $barH = [int]($blockRefPx * $unit)
    Line $xBar $barTop 3 $barH $colBar
    Line ($xBar - 4) $barTop 11 1 $colBar
    Line ($xBar - 4) ($barTop + $barH) 11 1 $colBar
    Txt '1 blk' $fMeta $colBar ($xBar + 12) ($barTop - 4)

    $tickH = [int]($a.visualSlotPx * $unit)
    $tickTop = [int]($midY - $tickH / 2)
    Line ($xAfter + $slotOuter + 6) $tickTop 16 2 $colAfter
    Line ($xAfter + $slotOuter + 6) ($tickTop + $tickH - 2) 16 2 $colAfter
    Txt (('{0:0.00}' -f ($a.visualSlotPx / $blockRefPx)) + ' blk') $fMeta $colAfter $xBar ($y + $rowH - 34)
}

# ---- reference row --------------------------------------------------------
$yRef = $headerH + $rows.Count * $rowH
Fill ($margin - 8) $yRef ($W - 2 * ($margin - 8)) ($refH - 8) $colBg
Line ($margin - 8) $yRef ($W - 2 * ($margin - 8)) 1 $colLine
Txt 'reference' $fName $colTitle $margin ($yRef + 14)
Txt 'a plain 16x16x16 block item at the vanilla gui scale 0.625' $fMeta $colSub $margin ($yRef + 38)
Txt ('on screen: ' + ('{0:0.00}' -f $blockRefPx) + ' px = 1.00 blk  ->  every AFTER icon above stays under this') $fMeta $colDim $margin ($yRef + 56)
Txt 'one full block item: the yardstick for "1.0 block visual height"' $fMeta $colDim $margin ($yRef + 72)
New-Slot $xBefore ($yRef + 8) | Out-Null
Put-Item (Join-Path $work '_reference_block.png') $xBefore ($yRef + 8) | Out-Null
$refMid = [int]($yRef + 8 + $unit + $slotPx / 2)
$refBarTop = [int]($refMid - $blockRefPx * $unit / 2)
$refBarH = [int]($blockRefPx * $unit)
Line $xBar $refBarTop 3 $refBarH $colBar
Line ($xBar - 4) $refBarTop 11 1 $colBar
Line ($xBar - 4) ($refBarTop + $refBarH) 11 1 $colBar
Txt '1 blk' $fMeta $colBar ($xBar + 12) ($refBarTop - 4)
Txt '1.00 blk' $fMeta $colBar $xBar ($yRef + $refH - 34)

# ---- footer ---------------------------------------------------------------
$yF = $yRef + $refH + 12
Line $margin $yF ($W - 2 * $margin) 1 $colLine
$n = $rows.Count
$tallMin = ($rows | ForEach-Object { [double]$_.after.height } | Measure-Object -Minimum).Minimum
$tallMax = ($rows | ForEach-Object { [double]$_.after.height } | Measure-Object -Maximum).Maximum
$visMax = ($rows | ForEach-Object { [double]$_.after.visualSlotPx } | Measure-Object -Maximum).Maximum
$beforeMin = ($rows | Where-Object { $null -ne $_.before } | ForEach-Object { [double]$_.before.height } | Measure-Object -Minimum).Minimum
$beforeMax = ($rows | Where-Object { $null -ne $_.before } | ForEach-Object { [double]$_.before.height } | Measure-Object -Maximum).Maximum
Txt ('why: ' + $n + ' devices are TWO blocks tall in the world (DeviceBlock.TALL -> DeviceExtensionBlock above), but the item generator only built the lower storey,') `
    $fMeta $colSub $margin ($yF + 10)
Txt ('     so all the slot ever showed was a ' + ('{0:0.0}' -f $beforeMin) + '-' + ('{0:0.0}' -f $beforeMax) + ' px one-block icon.') `
    $fMeta $colSub $margin ($yF + 26)
Txt ('fix: item model = lower tier elements ++ extension tier elements shifted +16  ->  bbox ' + ('{0:0.0}' -f $tallMin) + '..' + ('{0:0.0}' -f $tallMax) + ' px (assertion: > 20 px)') `
    $fMeta $colAfter $margin ($yF + 42)
Txt ('gui:  height * scale pinned to 10 px = one block in model space; measured silhouette max ' + ('{0:0.0}' -f $visMax) + ' px vs the ' + ('{0:0.0}' -f $blockRefPx) + ' px full-block reference (' + ('{0:0.00}' -f ($visMax / $blockRefPx)) + ' blk)') `
    $fMeta $colAfter $margin ($yF + 58)
Txt ('generated by tools/preview_twoblock.ps1 (slot art + labels) and tools/preview_item_gui.js (renders, tools/preview/twoblock/manifest.json)') `
    $fMeta $colDim $margin ($yF + 76)

$bmp.Save($outFile, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Host ("sheet: {0}  ({1} x {2}, {3} two-block devices)" -f $outFile, $W, $H, $n)
