param(
  [string]$OutDir = "src/main/resources/assets/veriborder/textures/block"
)
# R9 shared material kit: matte off-white shell, brushed silver, clear acrylic,
# recessed dark slot, embedded screen. No neon, no gradients that read as sci-fi.
Add-Type -AssemblyName System.Drawing

function Save-Bmp($bmp, $name) {
  $p = Join-Path $OutDir $name
  $bmp.Save($p, [System.Drawing.Imaging.ImageFormat]::Png)
  $bmp.Dispose()
  Write-Output "tex -> $p"
}

function New-Solid([string]$hex, [int]$noise = 0) {
  $c = [System.Drawing.ColorTranslator]::FromHtml($hex)
  $b = New-Object System.Drawing.Bitmap 32, 32
  $rnd = New-Object System.Random 7
  for ($x = 0; $x -lt 32; $x++) {
    for ($y = 0; $y -lt 32; $y++) {
      $d = if ($noise -gt 0) { $rnd.Next(-$noise, $noise + 1) } else { 0 }
      $b.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255,
        [Math]::Max(0, [Math]::Min(255, $c.R + $d)),
        [Math]::Max(0, [Math]::Min(255, $c.G + $d)),
        [Math]::Max(0, [Math]::Min(255, $c.B + $d))))
    }
  }
  return $b
}

function New-Brushed([string]$hex) {
  $c = [System.Drawing.ColorTranslator]::FromHtml($hex)
  $b = New-Object System.Drawing.Bitmap 32, 32
  $rnd = New-Object System.Random 11
  for ($y = 0; $y -lt 32; $y++) {
    $line = $rnd.Next(-8, 9)
    for ($x = 0; $x -lt 32; $x++) {
      $d = $line + $rnd.Next(-3, 4)
      $b.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255,
        [Math]::Max(0, [Math]::Min(255, $c.R + $d)),
        [Math]::Max(0, [Math]::Min(255, $c.G + $d)),
        [Math]::Max(0, [Math]::Min(255, $c.B + $d))))
    }
  }
  return $b
}

function New-Glass([string]$hex, [int]$alpha) {
  $c = [System.Drawing.ColorTranslator]::FromHtml($hex)
  $b = New-Object System.Drawing.Bitmap 32, 32
  for ($x = 0; $x -lt 32; $x++) {
    for ($y = 0; $y -lt 32; $y++) {
      $edge = ($x -le 1) -or ($x -ge 30) -or ($y -le 1) -or ($y -ge 30)
      $a = if ($edge) { 235 } else { $alpha }
      $b.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($a, $c.R, $c.G, $c.B))
    }
  }
  return $b
}

function New-Screen {
  $b = New-Object System.Drawing.Bitmap 32, 32
  for ($x = 0; $x -lt 32; $x++) {
    for ($y = 0; $y -lt 32; $y++) {
      $edge = ($x -le 0) -or ($x -ge 31) -or ($y -le 0) -or ($y -ge 31)
      if ($edge) { $col = [System.Drawing.Color]::FromArgb(255, 20, 23, 27) }
      else {
        # very subtle vertical UI band, low saturation
        $t = $y / 32.0
        $r = [int](28 + 10 * $t); $g = [int](34 + 12 * $t); $bb = [int](40 + 14 * $t)
        $col = [System.Drawing.Color]::FromArgb(255, $r, $g, $bb)
      }
      $b.SetPixel($x, $y, $col)
    }
  }
  return $b
}

Save-Bmp (New-Solid '#E9E7E1' 3) 'r9_shell.png'
Save-Bmp (New-Solid '#DCD9D2' 3) 'r9_shell2.png'
Save-Bmp (New-Brushed '#B9BDC1') 'r9_silver.png'
Save-Bmp (New-Brushed '#8E9398') 'r9_silver_dark.png'
Save-Bmp (New-Solid '#2A2E33' 2) 'r9_dark.png'
Save-Bmp (New-Screen) 'r9_screen.png'
Save-Bmp (New-Glass '#CFE4EC' 140) 'r9_glass.png'
Save-Bmp (New-Solid '#3FBF6A' 2) 'r9_led_green.png'
Save-Bmp (New-Solid '#D24B4B' 2) 'r9_led_red.png'
