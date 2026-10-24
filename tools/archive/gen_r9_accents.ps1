param(
  [string]$OutDir = "src/main/resources/assets/veriborder/textures/block"
)
# Round 9: small *painted* accent panels only (muted purple / muted brass).
# These are matte powder-coated plates, deliberately desaturated so they never
# read as neon strips.
Add-Type -AssemblyName System.Drawing

function New-Painted([string]$hex, [string]$edgeHex, [string]$name) {
  $c = [System.Drawing.ColorTranslator]::FromHtml($hex)
  $e = [System.Drawing.ColorTranslator]::FromHtml($edgeHex)
  $b = New-Object System.Drawing.Bitmap 32, 32
  $rnd = New-Object System.Random 23
  for ($x = 0; $x -lt 32; $x++) {
    for ($y = 0; $y -lt 32; $y++) {
      $edge = ($x -le 0) -or ($x -ge 31) -or ($y -le 0) -or ($y -ge 31)
      $base = if ($edge) { $e } else { $c }
      $d = $rnd.Next(-4, 5)
      # faint horizontal brushed variation so the plate is not a flat swatch
      if (-not $edge) { $d += [int](2 * [Math]::Sin($y * 0.9)) }
      $b.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255,
        [Math]::Max(0, [Math]::Min(255, $base.R + $d)),
        [Math]::Max(0, [Math]::Min(255, $base.G + $d)),
        [Math]::Max(0, [Math]::Min(255, $base.B + $d))))
    }
  }
  $p = Join-Path $OutDir $name
  $b.Save($p, [System.Drawing.Imaging.ImageFormat]::Png)
  $b.Dispose()
  Write-Output "tex -> $p"
}

# muted aubergine paint (permit issuer) / muted brass paint (visa issuer)
New-Painted '#6F5C8A' '#54456A' 'permit_issuer_accent.png'
New-Painted '#C2A05A' '#94783C' 'visa_issuer_accent.png'
