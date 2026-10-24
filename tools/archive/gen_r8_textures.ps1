param(
  [string]$OutDir = "src/main/resources/assets/veriborder/textures/block"
)
# ROUND8 issue-device textures.
# One material set per device so the seven issuers read as different machines
# (steel blue / burgundy / purple / gold / graphite / teal / green), not white boxes.
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = "Stop"

function HexToColor([string]$hex) {
  if ($hex -match '^#?([0-9a-fA-F]{6})$') { return [System.Drawing.Color]::FromArgb(255, [Convert]::ToInt32($Matches[1].Substring(0,2),16), [Convert]::ToInt32($Matches[1].Substring(2,2),16), [Convert]::ToInt32($Matches[1].Substring(4,2),16)) }
  throw "bad hex $hex"
}
function Shade([string]$hex, [double]$amt) {
  $c = HexToColor $hex
  if ($amt -ge 0) {
    $r = [int][Math]::Round($c.R + (255 - $c.R) * $amt)
    $g = [int][Math]::Round($c.G + (255 - $c.G) * $amt)
    $b = [int][Math]::Round($c.B + (255 - $c.B) * $amt)
  } else {
    $k = 1 + $amt
    $r = [int][Math]::Round($c.R * $k); $g = [int][Math]::Round($c.G * $k); $b = [int][Math]::Round($c.B * $k)
  }
  return ('#{0:X2}{1:X2}{2:X2}' -f $r, $g, $b)
}
function NewBmp([int]$w, [int]$h, [string]$hex) {
  $bmp = New-Object System.Drawing.Bitmap $w, $h
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
  $g.Clear((HexToColor $hex))
  return @{ bmp = $bmp; g = $g }
}
function FillRect($img, [int]$x, [int]$y, [int]$w, [int]$h, [string]$hex) {
  $br = New-Object System.Drawing.SolidBrush (HexToColor $hex)
  $img.g.FillRectangle($br, $x, $y, $w, $h)
  $br.Dispose()
}
function SaveImg($img, [string]$path) {
  $img.g.Dispose()
  $img.bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
  $img.bmp.Dispose()
}
# deterministic speckle so panels are not flat single-colour fills
function Speckle($img, [string]$hex, [int]$seed, [int]$count) {
  $rnd = New-Object System.Random $seed
  for ($i = 0; $i -lt $count; $i++) {
    $x = $rnd.Next(1, 31); $y = $rnd.Next(1, 31)
    FillRect $img $x $y 1 1 $hex
  }
}

$palettes = @{
  id_issuer = @{
    body='#2F4B66'; body2='#22384C'; trim='#12171D'; top='#4E6E8A'; accent='#2FA8E0'
    screenBase='#061826'; screenGlow='#62F0FF'; slot='#080B0F'; paper='#F2EEDE'; passport='#6E2433'
    led=@('#4DFFA0','#6BE9FF','#FFC24A','#FF5C5C')
  }
  passport_issuer = @{
    body='#6C2033'; body2='#4E1524'; trim='#191015'; top='#8C3149'; accent='#D6A44E'
    screenBase='#170A10'; screenGlow='#FFD08A'; slot='#0A0608'; paper='#F4EFDC'; passport='#7E2233'
    led=@('#58FFB0','#7FE4FF','#FFC24A','#FF5C5C')
  }
  permit_issuer = @{
    body='#4A2C6E'; body2='#34204E'; trim='#17121F'; top='#64408C'; accent='#C86BFF'
    screenBase='#0D0718'; screenGlow='#D79BFF'; slot='#08060C'; paper='#F1EEDF'; passport='#5A2340'
    led=@('#5CFFC0','#7FE0FF','#FFC24A','#FF6A8A')
  }
  visa_issuer = @{
    body='#8A6418'; body2='#65480F'; trim='#1B1508'; top='#B08726'; accent='#FFD24A'
    screenBase='#191207'; screenGlow='#FFC94F'; slot='#0C0904'; paper='#F5F0DA'; passport='#6E2A22'
    led=@('#66FFA8','#8FE8FF','#FFC24A','#FF5C5C')
  }
  endorsement_machine = @{
    body='#2C333B'; body2='#1E242A'; trim='#0E1115'; top='#3F4851'; accent='#35D08A'
    screenBase='#06120D'; screenGlow='#5CFFB0'; slot='#07090A'; paper='#F2EEDE'; passport='#5E2436'
    led=@('#46FF9A','#5FE8FF','#FFC24A','#FF5A5A')
  }
  self_visa = @{
    body='#0E4E5C'; body2='#0A3A45'; trim='#091116'; top='#14707F'; accent='#35E6E0'
    screenBase='#04171C'; screenGlow='#6FFFF0'; slot='#05090B'; paper='#F2EEDE'; passport='#6A2436'
    led=@('#4DFFC0','#6BFFFF','#FFD24A','#FF6A6A')
  }
  self_endorsement = @{
    body='#1E5A32'; body2='#144020'; trim='#0B140E'; top='#2A7A44'; accent='#7CE86A'
    screenBase='#07140B'; screenGlow='#B4FF8A'; slot='#050A06'; paper='#F2EEDE'; passport='#5C2432'
    led=@('#6CFF8A','#7FE8FF','#FFD24A','#FF6A6A')
  }
}

$ids = @('id_issuer','passport_issuer','permit_issuer','visa_issuer','endorsement_machine','self_visa','self_endorsement')
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force $OutDir | Out-Null }
$written = 0

foreach ($id in $ids) {
  $p = $palettes[$id]

  # ---- body: main shell, subtle top highlight / bottom shade + speckle
  $img = NewBmp 32 32 $p.body
  FillRect $img 0 0 32 3 (Shade $p.body 0.10)
  FillRect $img 0 29 32 3 (Shade $p.body -0.16)
  FillRect $img 0 0 1 32 (Shade $p.body 0.06)
  FillRect $img 31 0 1 32 (Shade $p.body -0.08)
  Speckle $img (Shade $p.body -0.07) 11 22
  SaveImg $img (Join-Path $OutDir "${id}_body.png"); $written++

  # ---- body2: lower cabinet / secondary shell with a horizontal panel seam
  $img = NewBmp 32 32 $p.body2
  FillRect $img 0 0 32 2 (Shade $p.body2 0.12)
  FillRect $img 0 15 32 1 (Shade $p.body2 -0.22)
  FillRect $img 0 16 32 1 (Shade $p.body2 0.10)
  FillRect $img 0 30 32 2 (Shade $p.body2 -0.20)
  Speckle $img (Shade $p.body2 -0.08) 23 16
  SaveImg $img (Join-Path $OutDir "${id}_body2.png"); $written++

  # ---- trim: dark metal frame with a top highlight and corner rivets
  $img = NewBmp 32 32 $p.trim
  FillRect $img 0 0 32 2 (Shade $p.trim 0.28)
  FillRect $img 0 2 32 1 (Shade $p.trim 0.14)
  FillRect $img 0 30 32 2 (Shade $p.trim 0.10)
  foreach ($pt in @(@(3,3),@(28,3),@(3,28),@(28,28))) { FillRect $img $pt[0] $pt[1] 2 2 (Shade $p.trim 0.34) }
  SaveImg $img (Join-Path $OutDir "${id}_trim.png"); $written++

  # ---- top: light counter surface with an inset panel seam
  $img = NewBmp 32 32 $p.top
  FillRect $img 0 0 32 32 $p.top
  FillRect $img 5 5 22 22 (Shade $p.top -0.10)
  FillRect $img 6 6 20 20 $p.top
  FillRect $img 0 0 32 1 (Shade $p.top 0.20)
  FillRect $img 0 31 32 1 (Shade $p.top -0.22)
  Speckle $img (Shade $p.top -0.06) 37 10
  SaveImg $img (Join-Path $OutDir "${id}_top.png"); $written++

  # ---- accent: colour stripe with a bright leading edge
  $img = NewBmp 32 32 $p.accent
  FillRect $img 0 0 32 4 (Shade $p.accent 0.22)
  FillRect $img 0 28 32 4 (Shade $p.accent -0.26)
  FillRect $img 0 0 2 32 (Shade $p.accent 0.30)
  SaveImg $img (Join-Path $OutDir "${id}_accent.png"); $written++

  # ---- screen: dark glass, bright frame, header bar, text rows, data block
  $img = NewBmp 32 32 $p.screenBase
  $glow = $p.screenGlow
  FillRect $img 0 0 32 2 (Shade $glow 0.0)
  FillRect $img 0 30 32 2 (Shade $glow -0.10)
  FillRect $img 0 0 2 32 (Shade $glow -0.05)
  FillRect $img 30 0 2 32 (Shade $glow -0.10)
  FillRect $img 4 4 24 5 $glow
  FillRect $img 4 11 16 2 $glow
  FillRect $img 4 15 20 2 (Shade $glow 0.10)
  FillRect $img 4 19 13 2 $glow
  FillRect $img 4 23 24 1 (Shade $glow -0.15)
  FillRect $img 20 25 8 3 $glow
  FillRect $img 4 25 6 3 (Shade $glow -0.20)
  SaveImg $img (Join-Path $OutDir "${id}_screen.png"); $written++

  # ---- lights: 2x2 LED atlas (uv quadrants 8x8 each) green / cyan / amber / red
  $img = NewBmp 32 32 '#0A0C0E'
  $quads = @(@(0,0),@(16,0),@(0,16),@(16,16))
  for ($q = 0; $q -lt 4; $q++) {
    $x = $quads[$q][0]; $y = $quads[$q][1]
    FillRect $img $x $y 16 16 (Shade $p.led[$q] -0.35)
    FillRect $img ($x+2) ($y+2) 12 12 $p.led[$q]
    FillRect $img ($x+5) ($y+5) 6 6 (Shade $p.led[$q] 0.35)
  }
  SaveImg $img (Join-Path $OutDir "${id}_lights.png"); $written++

  # ---- slot: recessed document throat
  $img = NewBmp 32 32 $p.slot
  FillRect $img 0 0 32 1 (Shade $p.slot 0.30)
  FillRect $img 0 1 32 3 (Shade $p.slot 0.12)
  FillRect $img 0 30 32 2 (Shade $p.slot 0.18)
  FillRect $img 4 16 24 1 (Shade $p.slot 0.10)
  SaveImg $img (Join-Path $OutDir "${id}_slot.png"); $written++

  # ---- paper: card stock with a printed rule and a corner chip
  $img = NewBmp 32 32 $p.paper
  FillRect $img 0 0 32 2 (Shade $p.paper 0.10)
  FillRect $img 0 30 32 2 (Shade $p.paper -0.14)
  FillRect $img 4 8 24 2 (Shade $p.paper -0.35)
  FillRect $img 4 13 16 2 (Shade $p.paper -0.28)
  FillRect $img 4 18 20 2 (Shade $p.paper -0.28)
  FillRect $img 22 22 6 6 (Shade $p.paper -0.45)
  SaveImg $img (Join-Path $OutDir "${id}_paper.png"); $written++

  # ---- passport: booklet cover with a gold emblem
  $img = NewBmp 32 32 $p.passport
  FillRect $img 0 0 32 2 (Shade $p.passport 0.14)
  FillRect $img 0 30 32 2 (Shade $p.passport -0.20)
  FillRect $img 2 2 28 1 (Shade $p.passport 0.22)
  $gold = '#D8B45A'
  FillRect $img 12 10 8 8 $gold
  FillRect $img 14 12 4 4 (Shade $p.passport -0.10)
  FillRect $img 8 22 16 2 $gold
  SaveImg $img (Join-Path $OutDir "${id}_passport.png"); $written++
}

Write-Output "r8 textures written: $written -> $OutDir"
