# ---------------------------------------------------------------------------
# VeriBorder item-texture regeneration + ink audit
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_item_textures.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_item_textures.ps1 -AuditOnly
#
# Regenerates ONLY the document card textures (5 passports, 2 ID cards, the
# HK/Macau permit, 3 custom docs). Dry-running generate_assets.ps1 itself would
# also rewrite every device texture, block model, blockstate and loot table, so
# the drawing functions are lifted out of it instead of running the whole file.
#
# The audit is the regression gate for the "text runs off the card" bug and for
# the shader see-through bug:
#   * canvas 1 px border must stay transparent (brushes excepted, they are
#     full-bleed by design),
#   * every drawn string must sit inside the card face with >= 2 px margins,
#   * no pixel may be semi-transparent (0 < alpha < 255). Iris/OptiFine draw any
#     translucent pixel in the sorted translucent pass, so a feathered icon edge
#     bleeds light and makes the item look see-through. Save-Bitmap (lifted from
#     generate_assets.ps1) hardens alpha on save; this gate proves it stayed
#     hardened, and it also catches hand-edited / re-exported PNGs.
# Text boxes are measured by re-running the same card functions with Draw-Text /
# Draw-TextCentered redirected to an empty probe bitmap, so the numbers come from
# the real layout code, not from re-typed coordinates.
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [switch]$AuditOnly,
    # Optional output root (a directory containing src\main\resources); see
    # tools/check_generators.ps1.
    [string]$Root
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = if ($Root) { (Resolve-Path -LiteralPath $Root).Path } else { Split-Path -Parent $PSScriptRoot }
$itemTex = Join-Path $root 'src\main\resources\assets\veriborder\textures\item'
# The generator owns this directory: recreate it when it was removed (scratch
# runs, clean checkouts) instead of relying on generate_assets.ps1 having run.
New-Item -ItemType Directory -Force $itemTex | Out-Null
$source = Join-Path $PSScriptRoot 'generate_assets.ps1'

# card face rectangles (x, y, w, h) - keep in sync with New-CardTexture /
# New-CustomDocTexture in generate_assets.ps1
$cardFaces = @{
    id_card_cn      = @(4, 15, 56, 36)
    id_card_hk      = @(4, 15, 56, 36)
    hk_macao_permit = @(4, 15, 56, 36)
    custom_id_card  = @(4, 15, 56, 36)
    custom_permit   = @(7, 13, 50, 38)
    passport_cn     = @(13, 6, 38, 52)
    passport_hk     = @(13, 6, 38, 52)
    passport_mo     = @(13, 6, 38, 52)
    passport_us     = @(13, 6, 38, 52)
    passport_jp     = @(13, 6, 38, 52)
    custom_passport = @(13, 6, 38, 52)
}
# full-bleed icons: the handle / bristles intentionally reach the canvas edge
$fullBleed = @('device_brush', 'dual_gate_brush', 'gate_config_brush')

# ---- load only the drawing functions --------------------------------------
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($source, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors) { throw "generate_assets.ps1 has parse errors: $($parseErrors[0].Message)" }

$wanted = @('C', 'Font', 'Brush', 'Pen', 'Fill-Rect', 'Draw-Line', 'Draw-Text', 'Draw-TextCentered',
    'New-Bitmap', 'New-BitmapSmooth', 'Set-HardAlpha', 'Save-Bitmap', 'Get-FitFontSize',
    'New-CardTexture', 'New-CustomDocTexture')
foreach ($fn in $ast.FindAll({ param($n) $n -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
    if ($wanted -contains $fn.Name) { . ([scriptblock]::Create($fn.Extent.Text)) }
}
$calls = @($ast.FindAll({
            param($n) $n -is [System.Management.Automation.Language.CommandAst] -and
                @('New-CardTexture', 'New-CustomDocTexture') -contains $n.GetCommandName()
        }, $false))
if ($calls.Count -eq 0) { throw 'No document texture calls found in generate_assets.ps1' }

# ---- regenerate ------------------------------------------------------------
if (-not $AuditOnly) {
    foreach ($call in $calls) { . ([scriptblock]::Create($call.Extent.Text)) }
}

# ---- text-layer probe ------------------------------------------------------
function Get-InkBbox([System.Drawing.Bitmap]$bmp) {
    $rect = [System.Drawing.Rectangle]::new(0, 0, $bmp.Width, $bmp.Height)
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $stride = $data.Stride
        $bytes = [byte[]]::new([Math]::Abs($stride) * $bmp.Height)
        [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    } finally { $bmp.UnlockBits($data) }
    $x0 = $bmp.Width; $y0 = $bmp.Height; $x1 = -1; $y1 = -1
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        $row = $y * [Math]::Abs($stride)
        for ($x = 0; $x -lt $bmp.Width; $x++) {
            if ($bytes[$row + $x * 4 + 3] -gt 0) {
                if ($x -lt $x0) { $x0 = $x }
                if ($x -gt $x1) { $x1 = $x }
                if ($y -lt $y0) { $y0 = $y }
                if ($y -gt $y1) { $y1 = $y }
            }
        }
    }
    if ($x1 -lt 0) { return $null }
    return @($x0, $y0, $x1, $y1)
}

# count pixels with 0 < alpha < 255 (the shader-breaking ones)
function Get-SemiAlphaCount([System.Drawing.Bitmap]$bmp) {
    $rect = [System.Drawing.Rectangle]::new(0, 0, $bmp.Width, $bmp.Height)
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $stride = [Math]::Abs($data.Stride)
        $bytes = [byte[]]::new($stride * $bmp.Height)
        [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    } finally { $bmp.UnlockBits($data) }
    $n = 0
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        $row = $y * $stride
        for ($x = 0; $x -lt $bmp.Width; $x++) {
            $a = $bytes[$row + $x * 4 + 3]
            if ($a -gt 0 -and $a -lt 255) { $n++ }
        }
    }
    return $n
}

# probe canvas is wider than the texture so a regression that draws past the
# canvas is measured instead of silently clipped at x = 63
$script:probeBmp = [System.Drawing.Bitmap]::new(192, 64, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$script:probeG = [System.Drawing.Graphics]::FromImage($script:probeBmp)
$script:probeG.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$script:probeG.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$script:probeG.Clear([System.Drawing.Color]::Transparent)
$script:textBoxes = [System.Collections.Generic.List[object]]::new()
$script:textRuns = [ordered]@{}

function Draw-Text($g, [string]$text, [System.Drawing.Font]$font, [System.Drawing.Color]$color, [int]$x, [int]$y) {
    if ($null -eq $script:probeG) { return }
    $b = Brush ([System.Drawing.Color]::White)
    $script:probeG.DrawString($text, $font, $b, $x, $y)
    $b.Dispose()
    $bb = Get-InkBbox $script:probeBmp
    if ($bb) { $script:textBoxes.Add([pscustomobject]@{ Text = $text; Size = $font.Size; X0 = $bb[0]; Y0 = $bb[1]; X1 = $bb[2]; Y1 = $bb[3] }) }
    $script:probeG.Clear([System.Drawing.Color]::Transparent)
}
function Draw-TextCentered($g, [string]$text, [System.Drawing.Font]$font, [System.Drawing.Color]$color, [int]$x, [int]$y, [int]$w) {
    if ($null -eq $script:probeG) { return }
    $b = Brush ([System.Drawing.Color]::White)
    $sf = [System.Drawing.StringFormat]::new()
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $script:probeG.DrawString($text, $font, $b, [System.Drawing.RectangleF]::new($x, $y, $w, 20), $sf)
    $sf.Dispose(); $b.Dispose()
    $bb = Get-InkBbox $script:probeBmp
    if ($bb) { $script:textBoxes.Add([pscustomobject]@{ Text = $text; Size = $font.Size; X0 = $bb[0]; Y0 = $bb[1]; X1 = $bb[2]; Y1 = $bb[3] }) }
    $script:probeG.Clear([System.Drawing.Color]::Transparent)
}

$probeDir = Join-Path ([System.IO.Path]::GetTempPath()) "vb_item_probe_$PID"
New-Item -ItemType Directory -Force $probeDir | Out-Null
try {
    $itemTex = $probeDir
    foreach ($call in $calls) {
        $name = $call.CommandElements[1].Extent.Text.Trim([char[]]@("'", '"'))
        $script:textBoxes.Clear()
        . ([scriptblock]::Create($call.Extent.Text))
        $script:textRuns[$name] = @($script:textBoxes)
    }
} finally {
    $script:probeG.Dispose()
    $script:probeBmp.Dispose()
    $script:probeG = $null
    $itemTex = Join-Path $root 'src\main\resources\assets\veriborder\textures\item'
    Remove-Item -Recurse -Force $probeDir
}

# ---- audit -----------------------------------------------------------------
Write-Host ''
Write-Host '== item texture ink audit =='
$fail = 0
$rows = @()
foreach ($file in (Get-ChildItem $itemTex -Filter *.png | Sort-Object Name)) {
    $name = $file.BaseName
    $bmp = [System.Drawing.Bitmap]::new($file.FullName)
    $W = $bmp.Width; $H = $bmp.Height
    $bb = Get-InkBbox $bmp
    $semi = Get-SemiAlphaCount $bmp
    $ring = 0
    if ($bb) {
        for ($x = 0; $x -lt $W; $x++) {
            if ($bmp.GetPixel($x, 0).A -gt 0) { $ring++ }
            if ($bmp.GetPixel($x, $H - 1).A -gt 0) { $ring++ }
        }
        for ($y = 0; $y -lt $H; $y++) {
            if ($bmp.GetPixel(0, $y).A -gt 0) { $ring++ }
            if ($bmp.GetPixel($W - 1, $y).A -gt 0) { $ring++ }
        }
    }
    $bmp.Dispose()

    $status = @()
    $ringExempt = $fullBleed -contains $name
    if ($ring -gt 0 -and -not $ringExempt) { $status += 'RING' }
    if (-not $bb) { $status += 'EMPTY' }
    if ($semi -gt 0) { $status += "SEMI_ALPHA:$semi" }

    $canvasMargins = if ($bb) { '{0}/{1}/{2}/{3}' -f $bb[0], ($W - 1 - $bb[2]), $bb[1], ($H - 1 - $bb[3]) } else { '-' }
    $face = $cardFaces[$name]
    $textBbox = '-'
    $textMargins = '-'
    $faceText = '-'
    if ($face) {
        $fx, $fy, $fw, $fh = $face
        $faceText = '{0},{1} {2}x{3}' -f $fx, $fy, $fw, $fh
        $runs = $script:textRuns[$name]
        if ($runs -and $runs.Count -gt 0) {
            $tx0 = ($runs | Measure-Object -Property X0 -Minimum).Minimum
            $ty0 = ($runs | Measure-Object -Property Y0 -Minimum).Minimum
            $tx1 = ($runs | Measure-Object -Property X1 -Maximum).Maximum
            $ty1 = ($runs | Measure-Object -Property Y1 -Maximum).Maximum
            $textBbox = '{0},{1}-{2},{3}' -f $tx0, $ty0, $tx1, $ty1
            $ml = $tx0 - $fx
            $mr = ($fx + $fw - 1) - $tx1
            $mt = $ty0 - $fy
            $mb = ($fy + $fh - 1) - $ty1
            $textMargins = '{0}/{1}/{2}/{3}' -f $ml, $mr, $mt, $mb
            if ($ml -lt 2 -or $mr -lt 2 -or $mt -lt 2 -or $mb -lt 2) { $status += 'TEXT_MARGIN' }
            foreach ($run in $runs) {
                $rl = $run.X0 - $fx
                $rr = ($fx + $fw - 1) - $run.X1
                $rt = $run.Y0 - $fy
                $rb = ($fy + $fh - 1) - $run.Y1
                if ($rl -lt 2 -or $rr -lt 2 -or $rt -lt 2 -or $rb -lt 2) { $status += "TEXT_MARGIN:$($run.Text)" }
            }
        } else {
            $status += 'NO_TEXT'
        }
    }

    $verdict = if ($status.Count -eq 0) { 'PASS' } else { 'FAIL' }
    if ($verdict -eq 'FAIL') { $fail++ }
    if ($ring -gt 0 -and $ringExempt -and $verdict -eq 'PASS') { $verdict = 'PASS*' }

    $inkText = if ($bb) { '{0},{1}-{2},{3}' -f $bb[0], $bb[1], $bb[2], $bb[3] } else { '-' }
    $rows += [pscustomobject]@{
        name = $name; canvas = "$W x $H"; ink = $inkText
        inkMargins = $canvasMargins; ring = $ring; semi = $semi; face = $faceText; text = $textBbox; textMargins = $textMargins
        status = ($status -join ','); verdict = $verdict
    }
}
$rows | Format-Table -AutoSize | Out-String -Width 220 | Write-Host

# per-string detail for the document cards
Write-Host '== drawn strings (card face coordinates) =='
foreach ($name in $script:textRuns.Keys) {
    $face = $cardFaces[$name]
    if (-not $face) { continue }
    $fx, $fy, $fw, $fh = $face
    foreach ($run in $script:textRuns[$name]) {
        $ml = $run.X0 - $fx; $mr = ($fx + $fw - 1) - $run.X1
        $mt = $run.Y0 - $fy; $mb = ($fy + $fh - 1) - $run.Y1
        $ok = if ($ml -ge 2 -and $mr -ge 2 -and $mt -ge 2 -and $mb -ge 2) { 'ok' } else { 'OUT' }
        Write-Host ("  {0,-16} {1,-18} px {2,4:0.#}  ink {3,3},{4,3} - {5,3},{6,3}  margins L{7} R{8} T{9} B{10}  {11}" -f `
                $name, ("'" + $run.Text + "'"), $run.Size, $run.X0, $run.Y0, $run.X1, $run.Y1, $ml, $mr, $mt, $mb, $ok)
    }
}

Write-Host ''
if ($fail -gt 0) {
    Write-Host "FAIL: $fail texture(s) outside their card face / canvas border, or with semi-transparent pixels" -ForegroundColor Red
    exit 1
}
Write-Host 'PASS: every item texture is inside its card face, off the canvas border, and fully opaque or fully transparent'
