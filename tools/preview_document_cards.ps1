# ---------------------------------------------------------------------------
# VeriBorder document-card contact sheet
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_document_cards.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_document_cards.ps1 -Out D:\scratch\cards.png
#
# Composes what the game actually draws: <art>_flat tinted with a document colour
# (ItemColors multiplies layer0) with emblem_<id> stacked on top, at 8x
# nearest-neighbour so single texels stay visible. Also renders the 16 px icon
# size, which is the one that has to read as a card in the inventory.
#
# Read-only: it never writes into src\main\resources.
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [string]$Out
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$project = Split-Path -Parent $PSScriptRoot
$dir = Join-Path $project 'src\main\resources\assets\veriborder\textures\item'
$out = if ($Out) { $Out } else { Join-Path $project 'tools\preview\document_cards.png' }
New-Item -ItemType Directory -Force (Split-Path -Parent $out) | Out-Null

function Load([string]$n) {
    $path = Join-Path $dir "$n.png"
    if (-not (Test-Path -LiteralPath $path)) { throw "missing texture: $path" }
    return [System.Drawing.Bitmap]::new($path)
}

# One game-accurate icon: tint layer0, then overlay the crest (never tinted).
function Compose([string]$art, [string]$crest, [int]$accent) {
    $card = Load "${art}_flat"
    $bmp = [System.Drawing.Bitmap]::new(64, 64, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $cr = ($accent -shr 16) -band 0xFF
    $cg = ($accent -shr 8) -band 0xFF
    $cb = $accent -band 0xFF
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            $p = $card.GetPixel($x, $y)
            if ($p.A -eq 0) {
                $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0))
            } else {
                $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255,
                        [int](($p.R * $cr) / 255), [int](($p.G * $cg) / 255), [int](($p.B * $cb) / 255)))
            }
        }
    }
    $card.Dispose()
    $crestBmp = Load "emblem_$crest"
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            $c = $crestBmp.GetPixel($x, $y)
            if ($c.A -ne 0) { $bmp.SetPixel($x, $y, $c) }
        }
    }
    $crestBmp.Dispose()
    return $bmp
}

function Scaled($src, [int]$factor, [bool]$smooth) {
    $bmp = [System.Drawing.Bitmap]::new($src.Width * $factor, $src.Height * $factor,
            [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = if ($smooth) { [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic }
                           else { [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor }
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $g.DrawImage($src, 0, 0, $bmp.Width, $bmp.Height)
    $g.Dispose()
    return $bmp
}

# Columns: art x (neutral, official red, template navy, template green)
$columns = @(
    @{ art = 'id_card';  crest = 'emblem';  accent = 0xFFFFFF; label = 'neutral  #FFFFFF' }
    @{ art = 'id_card';  crest = 'shield';  accent = 0xC43B2D; label = 'CN id    #C43B2D' }
    @{ art = 'id_card';  crest = 'star';    accent = 0x2F6FE4; label = 'template #2F6FE4' }
    @{ art = 'passport'; crest = 'emblem';  accent = 0xFFFFFF; label = 'neutral  #FFFFFF' }
    @{ art = 'passport'; crest = 'star';    accent = 0xA3212B; label = 'JP book  #A3212B' }
    @{ art = 'passport'; crest = 'mrz';     accent = 0x1E7A46; label = 'template #1E7A46' }
    @{ art = 'permit';   crest = 'emblem';  accent = 0xFFFFFF; label = 'neutral  #FFFFFF' }
    @{ art = 'permit';   crest = 'gear';    accent = 0x1D4F91; label = 'permit   #1D4F91' }
    @{ art = 'permit';   crest = 'eagle';   accent = 0x7C6AF2; label = 'template #7C6AF2' }
)

$zoom = 8
$iconZoom = 3
$pad = 12
$cellW = 64 * $zoom
$cellH = 64 * $zoom + 30 + 64 * $iconZoom
$sheetW = $columns.Count * ($cellW + $pad) + $pad
$sheetH = $cellH + 2 * $pad + 22
$sheet = [System.Drawing.Bitmap]::new($sheetW, $sheetH, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$sg = [System.Drawing.Graphics]::FromImage($sheet)
$sg.Clear([System.Drawing.Color]::FromArgb(255, 14, 18, 26))
$tiny = [System.Drawing.Font]::new('Consolas', 9)
$brush = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 226, 235, 245))
$sg.DrawString('VeriBorder document cards - layer0 <art>_flat x colour + layer1 emblem_<id> (8x, then 16 px icon at 3x)',
    $tiny, $brush, $pad, 6)

$index = 0
foreach ($column in $columns) {
    $icon = Compose $column.art $column.crest $column.accent
    $big = Scaled $icon $zoom $false
    # 16 px inventory size: downscale, then blow up again with smoothing off
    $small = [System.Drawing.Bitmap]::new(16, 16, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $sgSmall = [System.Drawing.Graphics]::FromImage($small)
    $sgSmall.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $sgSmall.DrawImage($icon, 0, 0, 16, 16)
    $sgSmall.Dispose()
    $icon16 = Scaled $small $iconZoom $false

    $x = $pad + $index * ($cellW + $pad)
    $y = $pad + 18
    $sg.DrawImage($big, $x, $y)
    $sg.DrawImage($icon16, $x, $y + $big.Height + 4)
    $sg.DrawString($column.label, $tiny, $brush, $x, $y + $big.Height + $icon16.Height + 6)

    $big.Dispose(); $icon16.Dispose(); $small.Dispose(); $icon.Dispose()
    $index++
}

$sg.Dispose()
$sheet.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()
$tiny.Dispose(); $brush.Dispose()
Write-Host "wrote $out"
