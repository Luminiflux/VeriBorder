param(
    [switch]$SkipRender
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# VeriBorder model preview + contact sheet
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_models.ps1
# Runs tools/preview_models.js (isometric renders + metrics + validation), then
# composes tools/preview/contact-sheet.png with device id labels.

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$preview = Join-Path $root 'tools\preview'
$modelDir = Join-Path $root 'src\main\resources\assets\veriborder\models\block'
New-Item -ItemType Directory -Force $preview | Out-Null

if (-not $SkipRender) {
    & node (Join-Path $root 'tools\preview_models.js')
    if ($LASTEXITCODE -ne 0) { Write-Host "preview_models.js reported failures (exit $LASTEXITCODE)" }
}

$files = Get-ChildItem $preview -Filter *.png |
    Where-Object { $_.Name -ne 'contact-sheet.png' } |
    Sort-Object Name
if ($files.Count -eq 0) { throw 'No preview PNGs found - run the generator first.' }

$cols = 6
$rows = [int][Math]::Ceiling($files.Count / $cols)
$cw = 260
$ch = 250
$header = 54
$W = $cols * $cw
$H = $header + $rows * $ch

$bmp = [System.Drawing.Bitmap]::new($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$g.Clear([System.Drawing.Color]::FromArgb(255, 15, 20, 26))

function Fill([int]$x, [int]$y, [int]$w, [int]$h, [System.Drawing.Color]$c) {
    $b = [System.Drawing.SolidBrush]::new($c)
    $g.FillRectangle($b, $x, $y, $w, $h)
    $b.Dispose()
}
function TextAt([string]$s, [System.Drawing.Font]$f, [System.Drawing.Color]$c, [int]$x, [int]$y) {
    $b = [System.Drawing.SolidBrush]::new($c)
    $g.DrawString($s, $f, $b, $x, $y)
    $b.Dispose()
}
function TextCentered([string]$s, [System.Drawing.Font]$f, [System.Drawing.Color]$c, [int]$x, [int]$y, [int]$w) {
    $b = [System.Drawing.SolidBrush]::new($c)
    $sf = [System.Drawing.StringFormat]::new()
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $g.DrawString($s, $f, $b, [System.Drawing.RectangleF]::new($x, $y, $w, 22), $sf)
    $sf.Dispose(); $b.Dispose()
}

$fontTitle = [System.Drawing.Font]::new('Segoe UI', 15, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fontSub = [System.Drawing.Font]::new('Consolas', 10, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$fontId = [System.Drawing.Font]::new('Consolas', 12, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fontMeta = [System.Drawing.Font]::new('Consolas', 9, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)

TextAt "VeriBorder 1.0.0-hotfix-2  -  $((Get-ChildItem $modelDir -Filter *.json).Count) machine models (isometric previews)" $fontTitle ([System.Drawing.Color]::FromArgb(255, 226, 238, 246)) 18 10
$lightCount = 0
$metricsPath = Join-Path $preview 'metrics.txt'
$summary = 'each model: bespoke geometry / own palette / own screen UI'
if (Test-Path $metricsPath) {
    $summary = (Select-String -Path $metricsPath -Pattern '^models:').Line
    if (-not $summary) { $summary = 'see metrics.txt' }
}
TextAt $summary $fontSub ([System.Drawing.Color]::FromArgb(255, 128, 168, 186)) 18 34

for ($i = 0; $i -lt $files.Count; $i++) {
    $f = $files[$i]
    $col = $i % $cols
    $row = [int][Math]::Floor($i / $cols)
    $x = $col * $cw
    $y = $header + $row * $ch
    Fill ($x + 2) ($y + 2) ($cw - 4) ($ch - 4) ([System.Drawing.Color]::FromArgb(255, 22, 29, 37))
    Fill ($x + 2) ($y + 2) ($cw - 4) 1 ([System.Drawing.Color]::FromArgb(255, 42, 54, 66))
    $img = [System.Drawing.Image]::FromFile($f.FullName)
    $pad = 18
    $iw = $cw - $pad * 2
    $ih = $ch - 58
    $g.DrawImage($img, [System.Drawing.Rectangle]::new(($x + $pad), ($y + 10), $iw, $ih))
    $img.Dispose()
    $id = [System.IO.Path]::GetFileNameWithoutExtension($f.Name)
    TextCentered $id $fontId ([System.Drawing.Color]::FromArgb(255, 214, 232, 242)) $x ($y + $ch - 46) $cw
    $meta = ''
    $mf = Join-Path $modelDir "$id.json"
    if (Test-Path $mf) {
        $j = Get-Content $mf -Raw | ConvertFrom-Json
        $els = @($j.elements).Count
        $rot = @($j.elements | Where-Object { $_.rotation }).Count
        $meta = "$els els / $rot rotated"
    }
    TextCentered $meta $fontMeta ([System.Drawing.Color]::FromArgb(255, 116, 158, 176)) $x ($y + $ch - 30) $cw
    if ($i -lt $files.Count - 1) {
        Fill ($x + $cw - 1) ($y + 8) 1 ($ch - 16) ([System.Drawing.Color]::FromArgb(255, 36, 48, 58))
    }
}

$out = Join-Path $preview 'contact-sheet.png'
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose()
$bmp.Dispose()
Write-Host "Contact sheet: $out ($W x $H, $($files.Count) models)"
