$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$base = "C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder"
$design = Get-Content "$base\assets\blender\_work\design.json" -Raw | ConvertFrom-Json
$texDir = "$base\src\main\resources\assets\veriborder\textures\block"

function Save-Solid($path, [int]$r, [int]$g, [int]$b) {
  $bmp = New-Object System.Drawing.Bitmap 32, 32
  $gfx = [System.Drawing.Graphics]::FromImage($bmp)
  $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, $r, $g, $b))
  $gfx.FillRectangle($brush, 0, 0, 32, 32)
  $brush.Dispose(); $gfx.Dispose()
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
}

function Save-Screen($path, [int]$r, [int]$g, [int]$b) {
  $bmp = New-Object System.Drawing.Bitmap 32, 32
  $gfx = [System.Drawing.Graphics]::FromImage($bmp)
  $rect = New-Object System.Drawing.Rectangle 0, 0, 32, 32
  $hi = [System.Drawing.Color]::FromArgb(255, [Math]::Min(255,$r+70), [Math]::Min(255,$g+70), [Math]::Min(255,$b+70))
  $lo = [System.Drawing.Color]::FromArgb(255, [Math]::Max(0,$r-110), [Math]::Max(0,$g-110), [Math]::Max(0,$b-110))
  $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $hi, $lo, 90
  $gfx.FillRectangle($brush, $rect)
  # subtle UI: bright header band + content blocks
  $ui = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(90, 255, 255, 255))
  $gfx.FillRectangle($ui, 4, 4, 24, 5)
  $gfx.FillRectangle($ui, 4, 13, 15, 4)
  $gfx.FillRectangle($ui, 4, 20, 20, 4)
  $ui.Dispose(); $brush.Dispose(); $gfx.Dispose()
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
}

function Save-Led($path, [int]$r, [int]$g, [int]$b) {
  $bmp = New-Object System.Drawing.Bitmap 32, 32
  $gfx = [System.Drawing.Graphics]::FromImage($bmp)
  $gfx.SmoothingMode = 'AntiAlias'
  $gfx.Clear([System.Drawing.Color]::FromArgb(255, 12, 14, 16))
  $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
  $gp.AddEllipse(2, 2, 28, 28)
  $pgb = New-Object System.Drawing.Drawing2D.PathGradientBrush $gp
  $pgb.CenterPoint = New-Object System.Drawing.PointF 16, 16
  $pgb.CenterColor = [System.Drawing.Color]::FromArgb(255, [Math]::Min(255,$r+120), [Math]::Min(255,$g+120), [Math]::Min(255,$b+120))
  $edge = [System.Drawing.Color]::FromArgb(255, $r, $g, $b)
  $pgb.SurroundColors = [System.Drawing.Color[]]@($edge)
  $gfx.FillEllipse($pgb, 2, 2, 28, 28)
  $pgb.Dispose(); $gp.Dispose(); $gfx.Dispose()
  $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
}

$count = 0
foreach ($id in $design.devices.PSObject.Properties.Name) {
  $dev = $design.devices.$id
  # shared texture slots
  foreach ($slot in $design.shared_textures.PSObject.Properties.Name) {
    $st = $design.shared_textures.$slot
    $path = Join-Path $texDir ("{0}_{1}.png" -f $id, $slot)
    switch ($st.kind) {
      'screen' { Save-Screen $path $st.rgb[0] $st.rgb[1] $st.rgb[2] }
      'led'    { Save-Led    $path $st.rgb[0] $st.rgb[1] $st.rgb[2] }
      default  { Save-Solid  $path $st.rgb[0] $st.rgb[1] $st.rgb[2] }
    }
    $count++
  }
  # accent texture per device
  $apath = Join-Path $texDir ("{0}_accent.png" -f $id)
  Save-Solid $apath $dev.accent_rgb[0] $dev.accent_rgb[1] $dev.accent_rgb[2]
  $count++
}

Write-Output "Generated $count texture PNGs"
