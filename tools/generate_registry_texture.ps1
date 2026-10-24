# ---------------------------------------------------------------------------
# VeriBorder — credential registry item icon (16x16)
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_registry_texture.ps1
#
# 26.3 round 12: the registry item used to reuse the configuration-copy brush
# sprite. It now has its own 16x16 icon in the registry/ledger style (dark cover,
# gold band, page lines and a wax seal) so it is never mistaken for a brush.
#
# The script writes exactly one file and is idempotent: re-running it produces the
# same bytes. Every pixel on the canvas border stays transparent, which is what
# tools/generate_item_textures.ps1 audits for non-full-bleed item icons.
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [string]$Root
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = if ($Root) { (Resolve-Path -LiteralPath $Root).Path } else { Split-Path -Parent $PSScriptRoot }
$itemTex = Join-Path $root 'src\main\resources\assets\veriborder\textures\item'
New-Item -ItemType Directory -Force $itemTex | Out-Null
$target = Join-Path $itemTex 'credential_registry.png'

$size = 16
$bmp = [System.Drawing.Bitmap]::new($size, $size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.Clear([System.Drawing.Color]::Transparent)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor

function C([string]$hex) {
    return [System.Drawing.ColorTranslator]::FromHtml($hex)
}

# palette: ledger cover, spine, gold trim, seal wax, page paper
$cover = C '#1E3A5F'
$coverLight = C '#2B4E7C'
$spine = C '#0F2136'
$gold = C '#C9A227'
$goldLight = C '#EBCB6B'
$paper = C '#EDE6D4'
$shadow = C '#0A1522'

# book cover body (1px transparent ring kept clear on every side)
$bCover = [System.Drawing.SolidBrush]::new($cover)
$g.FillRectangle($bCover, 1, 2, 14, 12)
$bCoverLight = [System.Drawing.SolidBrush]::new($coverLight)
$g.FillRectangle($bCoverLight, 3, 3, 10, 10)
$bSpine = [System.Drawing.SolidBrush]::new($spine)
$g.FillRectangle($bSpine, 1, 2, 2, 12)
$bShadow = [System.Drawing.SolidBrush]::new($shadow)
$g.FillRectangle($bShadow, 1, 13, 14, 1)

# gold trim: top band, bottom band and the bookmark ribbon on the right edge
$bGold = [System.Drawing.SolidBrush]::new($gold)
$g.FillRectangle($bGold, 3, 2, 12, 1)
$g.FillRectangle($bGold, 3, 12, 12, 1)
$g.FillRectangle($bGold, 12, 3, 2, 4)
$bGoldLight = [System.Drawing.SolidBrush]::new($goldLight)
$g.FillRectangle($bGoldLight, 3, 3, 1, 1)

# page lines (the "ledger" text rows)
$bPaper = [System.Drawing.SolidBrush]::new($paper)
$g.FillRectangle($bPaper, 5, 5, 6, 1)
$g.FillRectangle($bPaper, 5, 7, 4, 1)
$g.FillRectangle($bPaper, 5, 9, 5, 1)
$g.FillRectangle($bPaper, 4, 3, 3, 1)

# wax seal in the lower-right corner (two gold rings + a light centre)
$bSealOuter = [System.Drawing.SolidBrush]::new($gold)
$g.FillRectangle($bSealOuter, 10, 9, 3, 3)
$bSealCentre = [System.Drawing.SolidBrush]::new($goldLight)
$g.FillRectangle($bSealCentre, 11, 10, 1, 1)

# corner highlight so the icon reads as a solid object at 1x
$bEdge = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(255, 58, 92, 134))
$g.FillRectangle($bEdge, 14, 2, 1, 12)

$g.Dispose()
$bmp.Save($target, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

Write-Host "wrote $target (${size}x${size})"
