param(
  [Parameter(Mandatory=$true)][string]$Text,
  [Parameter(Mandatory=$true)][string]$Out,
  [int]$W = 64,
  [int]$H = 32,
  [string]$Bg = "#0E141B",
  [string]$Fg = "#7FE7FF",
  [int]$FontSize = 14,
  [string]$Border = "#2A3642",
  # Minecraft's NORTH face maps u=0 to the east edge, so any label applied to a
  # north face reads mirrored unless the texture itself is pre-flipped.
  [switch]$FlipX
)
# Draws a crisp English label panel for a Minecraft model texture.
# Keeps every machine's signage legible and consistent across devices.
Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap $W, $H
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::SingleBitPerPixelGridFit
$g.Clear([System.Drawing.ColorTranslator]::FromHtml($Bg))
if ($Border) {
  $pen = New-Object System.Drawing.Pen ([System.Drawing.ColorTranslator]::FromHtml($Border)), 2
  $g.DrawRectangle($pen, 1, 1, $W - 3, $H - 3)
  $pen.Dispose()
}
$font = New-Object System.Drawing.Font('Consolas', $FontSize, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$brush = New-Object System.Drawing.SolidBrush ([System.Drawing.ColorTranslator]::FromHtml($Fg))
$fmt = New-Object System.Drawing.StringFormat
$fmt.Alignment = [System.Drawing.StringAlignment]::Center
$fmt.LineAlignment = [System.Drawing.StringAlignment]::Center
$rect = New-Object System.Drawing.RectangleF 0, 0, $W, $H
$g.DrawString($Text, $font, $brush, $rect, $fmt)
$g.Dispose(); $font.Dispose(); $brush.Dispose(); $fmt.Dispose()
$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
if ($FlipX) { $bmp.RotateFlip([System.Drawing.RotateFlipType]::RotateNoneFlipX) }
$bmp.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "label -> $Out ($W x $H) : $Text$(if ($FlipX) { ' [flipX]' } else { '' })"
