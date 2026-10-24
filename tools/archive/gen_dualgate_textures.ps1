<#
.SYNOPSIS
    Generates the dual-clearance gate textures (deep green #1F5E3A accent).

.DESCRIPTION
    Copies the egate block/entity texture set to dual_gate_* and recolours the
    accent parts:
      * block/dual_gate_accent.png  - solid #1F5E3A (accent slot)
      * block/dual_gate_cyan.png    - light strips remapped to a #1F5E3A ramp
      * entity/dual_gate_door_frame.png - solid #1F5E3A door frame
      * entity/dual_gate_scan.png   - cyan scan beam -> #2E8B57
      * item/dual_gate_brush.png    - gate brush bristles recoloured green
    Everything else is an exact copy, so white/silver/black stay unchanged.

    Run from the project root:
      powershell -NoProfile -ExecutionPolicy Bypass -File tools/gen_dualgate_textures.ps1
#>
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$blockDir = Join-Path $root 'src\main\resources\assets\veriborder\textures\block'
$entityDir = Join-Path $root 'src\main\resources\assets\veriborder\textures\entity'
$itemDir = Join-Path $root 'src\main\resources\assets\veriborder\textures\item'

$green = @(31, 94, 58)          # #1F5E3A
$scanGreen = @(46, 139, 87)     # #2E8B57 (readable beam)

function Save-Copy([string]$from, [string]$to) {
    Copy-Item -LiteralPath $from -Destination $to -Force
    Write-Host ("copy  {0}" -f (Split-Path -Leaf $to))
}

function Save-Solid([string]$to, [int]$width, [int]$height, [int[]]$rgb) {
    $bitmap = New-Object System.Drawing.Bitmap($width, $height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $color = [System.Drawing.Color]::FromArgb(255, $rgb[0], $rgb[1], $rgb[2])
    for ($y = 0; $y -lt $height; $y++) {
        for ($x = 0; $x -lt $width; $x++) {
            $bitmap.SetPixel($x, $y, $color)
        }
    }
    $bitmap.Save($to, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    Write-Host ("solid {0}" -f (Split-Path -Leaf $to))
}

function Save-Mapped([string]$from, [string]$to, [scriptblock]$map) {
    $source = [System.Drawing.Bitmap]::FromFile($from)
    $target = New-Object System.Drawing.Bitmap($source.Width, $source.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $source.Height; $y++) {
        for ($x = 0; $x -lt $source.Width; $x++) {
            $pixel = $source.GetPixel($x, $y)
            $target.SetPixel($x, $y, (& $map $pixel))
        }
    }
    $target.Save($to, [System.Drawing.Imaging.ImageFormat]::Png)
    $target.Dispose()
    $source.Dispose()
    Write-Host ("map   {0}" -f (Split-Path -Leaf $to))
}

# ---- block textures: exact copies, then recolour the accent ----------------
Get-ChildItem -LiteralPath $blockDir -Filter 'egate_*.png' | ForEach-Object {
    $name = $_.Name -replace '^egate_', 'dual_gate_'
    Save-Copy $_.FullName (Join-Path $blockDir $name)
}

Save-Solid (Join-Path $blockDir 'dual_gate_accent.png') 32 32 $green

Save-Mapped (Join-Path $blockDir 'egate_cyan.png') (Join-Path $blockDir 'dual_gate_cyan.png') {
    param($pixel)
    if ($pixel.A -eq 0) {
        return [System.Drawing.Color]::FromArgb(0, 0, 0, 0)
    }
    $luma = [Math]::Max($pixel.R, [Math]::Max($pixel.G, $pixel.B)) / 255.0
    $factor = 0.55 + 0.45 * $luma
    $r = [Math]::Min(255, [int]($green[0] * $factor))
    $g = [Math]::Min(255, [int]($green[1] * $factor))
    $b = [Math]::Min(255, [int]($green[2] * $factor))
    return [System.Drawing.Color]::FromArgb($pixel.A, $r, $g, $b)
}

# ---- entity textures: door frame + scan beam ------------------------------
Save-Copy (Join-Path $entityDir 'egate_door_glass.png') (Join-Path $entityDir 'dual_gate_door_glass.png')
Save-Solid (Join-Path $entityDir 'dual_gate_door_frame.png') 32 32 $green
Save-Mapped (Join-Path $entityDir 'egate_scan.png') (Join-Path $entityDir 'dual_gate_scan.png') {
    param($pixel)
    if ($pixel.A -eq 0) {
        return [System.Drawing.Color]::FromArgb(0, 0, 0, 0)
    }
    # The cyan beam becomes a readable mid/dark green; the near-white core stays.
    if ($pixel.R -lt 200 -and $pixel.G -gt 150) {
        return [System.Drawing.Color]::FromArgb($pixel.A, $scanGreen[0], $scanGreen[1], $scanGreen[2])
    }
    return $pixel
}

# ---- brush item texture: recolour the orange bristles ---------------------
Save-Mapped (Join-Path $itemDir 'gate_config_brush.png') (Join-Path $itemDir 'dual_gate_brush.png') {
    param($pixel)
    # Orange bristles -> green; brown wooden handle and cream band stay as-is.
    if ($pixel.R -gt 200 -and $pixel.G -gt 140 -and $pixel.B -lt 100) {
        return [System.Drawing.Color]::FromArgb($pixel.A, 79, 191, 122)   # #4FBF7A
    }
    if ($pixel.R -ge 150 -and $pixel.R -le 200 -and $pixel.G -ge 90 -and $pixel.G -le 140 -and $pixel.B -lt 80) {
        return [System.Drawing.Color]::FromArgb($pixel.A, 46, 139, 87)    # #2E8B57
    }
    if ($pixel.R -gt 200 -and $pixel.G -gt 170 -and $pixel.B -gt 80 -and $pixel.B -lt 130) {
        return [System.Drawing.Color]::FromArgb($pixel.A, 143, 211, 168)  # #8FD3A8
    }
    return $pixel
}

Write-Host 'dual_gate textures generated.'
