# VeriBorder - bespoke block model library (18 devices)
# Dot-sourced by tools/generate_assets.ps1 after the drawing helpers are defined.
# Every device has its own silhouette; elements use explicit UVs so textures are
# never stretched or cropped, and no two models share a geometry hash.

$script:ELS = [System.Collections.Generic.List[string]]::new()
$script:UV_L = @(0, 0, 16, 10)   # 64x40 landscape screen content
$script:UV_P = @(0, 0, 12, 16)   # 48x64 portrait screen content

function B {
    param(
        [double]$x1, [double]$y1, [double]$z1,
        [double]$x2, [double]$y2, [double]$z2,
        [string]$tex = 'body',
        [hashtable]$over = $null,
        [hashtable]$uv = $null,
        [hashtable]$rot = $null
    )
    $names = @('north', 'south', 'east', 'west', 'up', 'down')
    $fs = [System.Collections.Generic.List[string]]::new()
    foreach ($f in $names) {
        $t = $tex
        if ($over -and $over.ContainsKey($f)) { $t = $over[$f] }
        $u = @(0, 0, 16, 16)
        if ($uv -and $uv.ContainsKey($f)) { $u = $uv[$f] }
        $fs.Add('"' + $f + '":{"texture":"#' + $t + '","uv":[' + (N $u[0]) + ',' + (N $u[1]) + ',' + (N $u[2]) + ',' + (N $u[3]) + ']}') | Out-Null
    }
    $e = '{"from":[' + (N $x1) + ',' + (N $y1) + ',' + (N $z1) + '],"to":[' + (N $x2) + ',' + (N $y2) + ',' + (N $z2) + '],"faces":{' + ($fs -join ',') + '}'
    if ($rot) {
        $o = $rot.origin
        $e += ',"rotation":{"origin":[' + (N $o[0]) + ',' + (N $o[1]) + ',' + (N $o[2]) + '],"axis":"' + $rot.axis + '","angle":' + (N $rot.angle) + '}'
    }
    $e += '}'
    $script:ELS.Add($e) | Out-Null
}

# north-facing screen: bezel plate + inset screen panel
function PanelN([double]$x1, [double]$y1, [double]$z, [double]$x2, [double]$y2, [string]$bezel, [string]$tex, [double[]]$uvv) {
    B $x1 $y1 ($z - 0.22) $x2 $y2 $z $bezel
    B ($x1 + 0.14) ($y1 + 0.14) ($z - 0.30) ($x2 - 0.14) ($y2 - 0.14) ($z - 0.22) $tex @{ north = $tex } @{ north = $uvv }
}

# south-facing screen
function PanelS([double]$x1, [double]$y1, [double]$z, [double]$x2, [double]$y2, [string]$bezel, [string]$tex, [double[]]$uvv) {
    B $x1 $y1 $z $x2 $y2 ($z + 0.22) $bezel
    B ($x1 + 0.14) ($y1 + 0.14) ($z + 0.22) ($x2 - 0.14) ($y2 - 0.14) ($z + 0.30) $tex @{ south = $tex } @{ south = $uvv }
}

# tilted screen, rotation about X; angle +22.5 leans the top away from the viewer
function PanelTilt([double]$x1, [double]$y1, [double]$z1, [double]$x2, [double]$y2, [double]$z2, [double]$angle, [double[]]$origin, [string]$bezel, [string]$tex, [double[]]$uvv) {
    $r = @{ axis = 'x'; angle = $angle; origin = $origin }
    B $x1 $y1 $z1 $x2 $y2 $z2 $bezel @{} @{} $r
    B ($x1 + 0.16) ($y1 + 0.16) ($z1 - 0.10) ($x2 - 0.16) ($y2 - 0.16) ($z2 - 0.10) $tex @{ north = $tex } @{ north = $uvv } $r
}

function Uv1 { return $script:DEV.uv1 }
function Uv2 { return $script:DEV.uv2 }

# =============================================================== 1 ID ISSUER
function Build-IdIssuer {
    $u = Uv1
    B 1.2 0 1.6 14.8 1.2 14.4 trim
    B 1.6 1.2 2.0 14.4 2.0 14.0 body2
    B 1.6 2.0 2.2 14.4 6.6 14.0 body2
    B 1.6 6.6 2.2 14.4 10.4 14.0 body
    B 2.2 10.4 2.6 13.8 11.2 13.6 body2
    # front service panel, accent strip and vent
    B 2.4 2.6 1.96 13.6 6.2 2.2 body
    B 2.8 3.1 1.88 13.2 3.9 1.96 accent
    B 3.0 4.4 1.90 12.0 5.8 1.96 trim
    # card slot + inserted document
    B 3.0 7.3 1.94 8.2 8.1 2.2 trim
    B 3.4 7.48 1.88 7.8 7.92 1.94 screen
    B 4.2 7.5 1.30 6.8 7.9 1.9 paper
    # control plate with buttons
    B 11.0 6.9 1.94 13.6 9.6 2.2 body2
    B 11.4 7.3 1.86 12.2 7.9 1.94 detail
    B 12.6 7.3 1.86 13.4 7.9 1.94 accent
    B 11.4 8.4 1.86 13.4 8.8 1.94 led
    # slanted operator deck with the main screen
    $r = @{ axis = 'x'; angle = -22.5; origin = @(8, 10.8, 6.4) }
    B 2.8 10.4 3.2 13.2 11.2 9.6 body2 @{} @{} $r
    B 3.4 11.2 3.6 12.6 11.34 9.2 trim @{} @{} $r
    B 4.0 11.34 3.9 12.0 11.40 8.9 screen @{ up = 'screen' } @{ up = $u } $r
    # camera arm and head with LED ring
    B 7.3 11.2 11.4 8.7 14.3 12.6 trim
    B 6.1 13.8 10.4 9.9 15.5 13.3 body
    B 6.4 14.1 10.26 9.6 15.2 10.4 trim
    B 7.1 13.95 10.16 8.9 15.075 10.26 screen2 @{ north = 'screen2' } @{ north = $script:UV_L }
    B 6.5 15.1 10.14 9.5 15.34 10.32 accent
    B 6.5 13.72 10.14 9.5 13.96 10.32 accent
    B 6.06 13.9 10.14 6.28 15.3 10.32 accent
    B 9.72 13.9 10.14 9.94 15.3 10.32 accent
    # document tray with lips and paper
    B 3.4 2.6 0.40 12.6 2.9 2.4 body2
    B 3.4 2.9 0.40 3.9 3.5 2.4 trim
    B 12.1 2.9 0.40 12.6 3.5 2.4 trim
    B 3.4 2.9 0.40 12.6 3.5 0.9 trim
    B 4.2 2.92 1.0 11.8 3.0 2.2 paper
    # side accents and rear vent
    B 1.54 3.0 3.0 1.62 9.4 13.0 accent
    B 14.38 3.0 3.0 14.46 9.4 13.0 accent
    B 2.4 3.0 13.96 13.6 8.0 14.10 trim
}

# =========================================================== 2 PASSPORT ISSUER
function Build-PassportIssuer {
    $u = Uv1
    $u2 = Uv2
    B 1.0 0 1.4 15.0 1.6 14.6 trim
    B 1.2 1.6 2.6 4.8 15.0 14.0 body
    B 11.2 1.6 2.6 14.8 15.0 14.0 body
    B 4.8 1.6 4.2 11.2 10.6 14.0 body2
    B 5.0 10.6 4.4 11.0 11.2 14.0 body2
    # arched top: stepped chamfers
    B 1.0 15.0 2.4 15.0 15.7 14.2 body2
    B 2.2 15.7 2.8 13.8 16.0 13.8 body
    B 4.0 15.2 3.4 12.0 15.7 13.4 accent
    # inspection window with document behind glass
    B 5.2 7.1 4.02 10.8 10.1 4.42 trim
    B 5.7 7.6 4.44 10.3 9.6 4.50 paper
    B 5.5 7.5 4.30 10.5 9.7 4.44 glass
    # main screen + small operator status display on the left column
    PanelN 5.0 2.4 4.4 11.0 6.15 body2 screen $u
    PanelN 1.8 3.0 2.6 3.8 4.25 body2 screen2 $u2
    # brass bands on the columns
    B 1.10 4.0 3.0 1.22 4.5 13.8 accent
    B 1.10 10.0 3.0 1.22 10.5 13.8 accent
    B 14.78 4.0 3.0 14.90 4.5 13.8 accent
    B 14.78 10.0 3.0 14.90 10.5 13.8 accent
    # LED strips on the inner column faces
    B 4.76 3.0 4.0 4.86 9.2 11.0 led
    B 11.14 3.0 4.0 11.24 9.2 11.0 led
    # book scan bed and V-holder
    B 4.8 10.6 4.8 11.2 11.2 13.4 body
    B 5.2 11.2 5.2 10.8 11.44 12.8 glass
    B 5.6 11.44 5.4 7.8 12.0 11.4 body2 @{} @{} @{ axis = 'z'; angle = -22.5; origin = @(7.8, 11.7, 8.4) }
    B 8.2 11.44 5.4 10.4 12.0 11.4 body2 @{} @{} @{ axis = 'z'; angle = 22.5; origin = @(8.2, 11.7, 8.4) }
    B 7.6 11.5 6.0 8.4 12.2 11.0 paper
    # rear ventilation and feet
    B 2.0 2.2 13.96 14.0 6.0 14.08 trim
    B 1.6 0 1.8 2.6 0.5 2.8 trim
    B 13.4 0 1.8 14.4 0.5 2.8 trim
    B 1.6 0 13.2 2.6 0.5 14.2 trim
    B 13.4 0 13.2 14.4 0.5 14.2 trim
    B 4.6 2.0 3.9 11.4 2.8 4.2 body2
}

# ============================================================= 3 PERMIT ISSUER
function Build-PermitIssuer {
    $u = Uv1
    $u2 = Uv2
    B 0.8 0 2.4 15.2 1.2 14.4 trim
    B 1.0 1.2 2.8 15.0 4.8 14.0 body2
    B 1.6 4.8 2.4 14.4 8.4 14.0 body
    B 0.9 8.4 2.0 15.1 9.0 14.4 body2
    B 1.5 4.2 2.34 14.5 4.62 2.40 accent
    PanelN 12.0 5.4 2.4 14.0 6.65 body2 screen2 $u2
    # angled operator screen standing above the counter
    PanelTilt 3.4 8.6 9.2 11.4 13.6 9.8 22.5 @(8, 8.6, 9.5) trim screen $u
    B 3.0 8.8 10.0 4.0 13.2 11.6 body2
    # fingerprint pad and buttons on the counter
    B 10.8 9.0 3.8 13.6 9.5 6.6 body2
    B 11.1 9.5 4.1 13.3 9.58 6.3 glass
    B 4.0 9.0 3.0 6.0 9.4 4.4 body2
    B 4.3 9.4 3.2 5.7 9.44 4.2 accent
    B 6.4 9.0 3.2 7.2 9.35 4.0 led
    # card slot, card and receipt slot with paper
    B 1.9 6.6 2.2 5.1 7.2 2.8 trim
    B 2.2 6.78 2.14 4.8 7.02 2.2 screen
    B 2.6 6.7 1.5 4.4 7.1 2.14 paper
    B 5.9 5.2 2.1 8.7 5.7 2.4 trim
    B 6.2 4.9 1.94 8.4 5.22 2.1 paper
    # lower kick panel and side vents
    B 2.0 1.4 2.72 14.0 2.4 2.8 trim
    B 2.4 2.6 13.94 13.6 4.0 14.06 trim
    B 0.94 2.0 3.4 1.0 4.2 12.6 trim
    B 15.0 2.0 3.4 15.06 4.2 12.6 trim
    B 2.8 9.0 12.4 13.2 9.4 14.2 body2
    # a small document on the counter
    B 4.8 9.0 7.4 9.2 9.06 10.4 paper
}

# =============================================================== 4 VISA ISSUER
function Build-VisaIssuer {
    $u = Uv1
    $u2 = Uv2
    B 0.6 0 1.6 15.4 1.2 15.0 trim
    B 1.2 1.2 2.4 10.4 12.4 14.6 body
    B 10.4 1.2 3.2 15.2 7.4 13.8 body2
    B 10.3 7.4 3.0 10.6 9.9 13.6 accent
    # two tilted screens
    PanelTilt 2.0 6.6 2.2 6.4 11.6 2.6 22.5 @(4.2, 6.6, 2.4) trim screen $u
    B 6.8 8.2 2.36 9.2 9.7 2.6 body2
    B 6.9 8.4 2.30 9.1 9.775 2.36 screen2 @{ north = 'screen2' } @{ north = $u2 }
    # status pillar at the rear right
    B 13.6 7.4 12.4 15.2 14.6 14.2 body
    B 13.9 8.0 12.34 14.9 13.8 12.4 led
    B 13.4 14.6 12.2 15.4 15.2 14.4 body2
    # printer arm and paper output
    B 10.6 9.2 5.0 14.6 10.0 6.4 body2
    B 12.4 8.2 4.6 14.4 9.2 7.0 body
    B 12.7 8.7 3.7 14.1 8.78 4.7 paper
    B 12.6 8.8 4.4 14.2 8.9 4.8 trim
    # passport slot and reader
    B 11.4 6.2 2.9 14.2 6.8 3.4 trim
    B 11.6 6.28 2.4 14.0 6.65 2.9 paper
    B 11.2 7.0 2.9 14.4 7.6 3.4 body2
    # vents and kick plate
    B 2.0 1.4 2.34 9.6 2.6 2.8 trim
    B 2.0 3.0 13.94 13.4 6.0 14.06 trim
    B 1.6 0 2.2 2.8 0.4 3.4 trim
    B 13.2 0 2.2 14.4 0.4 3.4 trim
}

# ===================================================== 5 ENDORSEMENT MACHINE
function Build-Endorsement {
    $u = Uv1
    $u2 = Uv2
    B 1.0 0 1.8 15.0 1.4 14.6 trim
    B 1.4 1.4 2.6 14.6 9.2 14.0 body
    B 2.0 1.4 1.94 14.0 5.6 2.6 body2
    B 2.2 3.8 1.86 13.8 4.24 1.94 accent
    # screens on the console face
    PanelN 2.4 4.6 2.6 9.4 9.1 body2 screen $u
    PanelN 9.8 5.6 2.6 12.8 7.475 body2 screen2 $u2
    # lower vent and drawer
    B 2.6 2.0 1.86 6.4 2.7 1.94 trim
    B 4.0 2.0 13.94 12.0 5.0 14.06 trim
    # glass hood: posts + panes + top
    B 4.2 9.2 4.0 4.6 13.6 4.4 body2
    B 11.4 9.2 4.0 11.8 13.6 4.4 body2
    B 4.2 9.2 11.6 4.6 13.6 12.0 body2
    B 11.4 9.2 11.6 11.8 13.6 12.0 body2
    B 4.6 9.2 3.98 11.4 13.4 4.04 glass
    B 4.6 9.2 11.96 11.4 13.4 12.02 glass
    B 4.2 9.2 4.4 4.26 13.4 11.6 glass
    B 11.74 9.2 4.4 11.8 13.4 11.6 glass
    B 4.6 13.34 4.4 11.4 13.4 11.6 glass
    # stamp ram inside the hood
    B 6.6 9.4 7.0 9.4 12.8 9.6 body2
    B 6.2 9.2 6.6 9.8 10.0 9.4 body
    B 6.5 9.0 6.9 9.5 9.2 9.1 accent
    B 7.0 12.8 3.0 8.2 13.1 9.6 body2
    B 6.6 12.6 2.4 8.6 13.3 3.2 accent
    B 6.7 12.9 2.3 8.5 13.6 3.1 accent @{} @{} @{ axis = 'x'; angle = 22.5; origin = @(7.6, 13.2, 3.2) }
    B 4.8 9.2 5.2 11.2 9.28 7.4 paper
    # side accents + feet
    B 1.34 2.0 4.0 1.42 8.0 13.0 accent
    B 14.58 2.0 4.0 14.66 8.0 13.0 accent
    B 1.6 0 2.0 2.8 0.4 3.2 trim
    B 13.2 0 2.0 14.4 0.4 3.2 trim
}

# ================================================================ 6 HOTEL READER
function Build-HotelReader {
    $u = Uv1
    $u2 = Uv2
    B 6.2 0 6.2 9.8 0.5 9.8 trim
    B 5.2 0.5 5.2 10.8 1.1 10.8 trim
    B 6.8 1.1 6.8 9.2 2.4 9.2 body2
    B 3.6 2.4 4.6 12.4 8.2 11.4 body
    B 4.2 2.0 5.2 11.8 2.4 10.8 body2
    B 3.8 8.2 4.8 12.2 8.8 11.2 body2
    # tilted display
    PanelTilt 4.4 6.2 5.0 12.0 9.4 5.6 22.5 @(8, 6.2, 5.3) trim screen $u
    # card slot with a card inserted
    B 4.4 4.2 4.46 9.2 5.0 4.6 trim
    B 4.7 4.36 4.40 8.9 4.86 4.46 screen
    B 5.1 4.3 3.5 7.3 4.82 4.4 paper
    # secondary status display
    B 9.6 2.8 4.52 12.0 4.3 4.6 body2
    B 9.7 2.9 4.46 11.9 4.2 4.52 screen2 @{ north = 'screen2' } @{ north = $u2 }
    # indicator dome / bell
    B 11.0 8.8 8.4 12.8 9.6 10.2 body2
    B 11.3 9.6 8.7 12.5 10.0 9.9 led
    B 11.5 8.2 8.9 12.3 8.8 9.7 body2
    # LEDs and side seam
    B 10.2 5.6 4.46 12.4 6.0 4.52 detail
    B 3.54 3.0 5.4 3.62 7.6 10.6 accent
    B 12.38 3.0 5.4 12.46 7.6 10.6 accent
}

# ================================================================ 7 DESK SCANNER
function Build-DeskScanner {
    $u = Uv1
    $u2 = Uv2
    B 0.8 0 1.2 15.2 2.2 14.8 trim
    B 1.2 2.2 1.8 14.8 3.6 14.2 body2
    B 1.4 3.6 2.2 14.6 4.4 13.8 body
    B 2.4 4.36 2.6 10.4 4.44 12.6 trim
    B 2.4 4.44 2.6 10.2 4.52 12.6 glass
    # hinged lid lifted 45 degrees
    $lid = @{ axis = 'x'; angle = 45.0; origin = @(6.3, 4.6, 13.4) }
    B 1.8 4.6 3.0 10.8 4.8 13.6 body2 @{} @{} $lid
    B 2.2 4.8 3.4 10.4 4.9 13.2 trim @{} @{} $lid
    B 2.4 4.8 3.8 4.2 5.2 5.4 body2
    B 8.4 4.8 3.8 10.2 5.2 5.4 body2
    # hinge and paper stop
    B 2.0 4.3 13.2 10.8 4.7 13.9 trim
    B 2.6 4.52 11.9 10.0 5.4 12.15 body2
    # control tower with screen
    B 10.6 4.4 2.4 14.8 7.2 6.6 body
    B 10.8 4.6 6.6 14.6 6.8 10.4 body2
    PanelN 10.8 4.8 2.4 14.8 7.3 body2 screen $u
    B 11.2 7.2 3.0 13.6 7.26 4.5 screen2 @{ up = 'screen2' } @{ up = $u2 }
    B 11.2 7.26 3.0 13.6 7.3 3.2 led
    # front control strip and LEDs
    B 2.0 2.6 1.74 8.4 3.4 1.8 detail
    B 2.4 4.0 1.74 9.6 4.24 1.8 led
    # feet, side accents
    B 1.0 0 1.4 2.2 0.3 2.6 trim
    B 13.8 0 1.4 15.0 0.3 2.6 trim
    B 1.0 0 13.4 2.2 0.3 14.6 trim
    B 13.8 0 13.4 15.0 0.3 14.6 trim
    B 14.56 1.2 3.0 14.64 3.6 12.8 accent
}

# ============================================================== 8 MANUAL TERMINAL
function Build-ManualTerminal {
    $u = Uv1
    $u2 = Uv2
    B 0.6 0 2.2 15.4 0.5 14.6 trim
    B 0.9 0.5 2.4 15.1 3.4 14.4 body2
    B 0.4 3.4 1.8 15.6 4.0 14.8 body
    B 1.4 4.0 12.6 14.6 15.2 14.8 body
    B 1.0 15.2 12.4 15.0 15.8 15.0 body2
    B 1.2 3.6 2.2 3.0 4.4 3.0 trim
    # large inspection screen
    PanelN 2.8 6.4 12.6 12.6 12.8 trim screen $u
    B 2.6 5.9 12.5 12.8 6.3 12.7 body2
    B 3.0 13.0 12.5 12.4 13.3 12.7 led
    # status display on the desk front
    PanelN 4.8 1.2 2.2 7.8 3.075 body2 screen2 $u2
    # tilted keyboard
    B 3.4 4.6 4.4 12.6 4.8 7.6 body2 @{} @{} @{ axis = 'x'; angle = -22.5; origin = @(8, 4.7, 4.4) }
    B 3.8 4.8 4.7 12.2 4.86 7.3 detail @{ up = 'detail' } @{ up = @(0, 0, 16, 6) } @{ axis = 'x'; angle = -22.5; origin = @(8, 4.7, 4.4) }
    # document camera arm
    B 12.6 4.0 5.6 13.6 12.4 6.6 body
    B 8.0 11.6 5.8 13.6 12.4 6.4 body2
    B 7.2 10.6 5.2 9.2 12.0 7.2 body
    B 7.6 10.4 5.4 8.8 10.6 7.0 accent
    B 7.4 10.2 5.6 9.0 10.5 6.8 screen2
    B 6.9 10.2 5.0 9.5 10.55 5.3 accent
    # badge reader, papers and monitor stand
    B 11.4 4.0 2.4 13.6 4.7 3.8 body2
    B 11.8 4.2 3.0 13.2 4.6 3.6 accent
    B 4.0 4.02 8.0 7.6 4.06 11.0 paper
    B 8.2 4.02 8.4 11.2 4.05 11.2 paper
    B 6.4 4.0 11.8 9.6 5.6 12.8 trim
    B 1.6 4.4 2.2 2.2 5.2 2.24 led
}

# ============================================================= 9 AIRPORT CHECK-IN
function Build-AirportCheckin {
    $u = Uv1
    $u2 = Uv2
    B 2.0 0 2.0 3.2 0.8 3.2 trim
    B 12.8 0 2.0 14.0 0.8 3.2 trim
    B 2.0 0 12.8 3.2 0.8 14.0 trim
    B 12.8 0 12.8 14.0 0.8 14.0 trim
    B 2.6 0.8 2.6 13.4 1.8 13.4 trim
    B 3.2 1.8 3.4 12.8 9.2 12.8 body
    B 2.8 9.2 3.0 13.2 14.2 13.2 body
    B 3.4 14.2 3.4 12.6 15.0 12.8 body2
    B 4.2 15.0 3.8 11.8 15.4 12.4 body
    # big portrait screen
    PanelN 3.8 9.4 3.0 12.0 13.8 trim screen $u
    B 3.6 9.2 2.9 12.2 9.4 3.02 body2
    B 3.7 14.2 2.86 12.3 14.6 3.0 led
    B 3.0 14.0 2.2 13.0 14.5 3.4 body2 @{} @{} @{ axis = 'x'; angle = -22.5; origin = @(8, 14.2, 2.8) }
    # passport scanner shelf
    B 3.8 6.2 1.4 12.2 6.8 3.6 body2
    B 4.8 6.5 2.0 11.2 6.7 2.5 trim
    B 5.2 6.7 1.8 10.8 6.78 3.2 paper
    # boarding pass printer
    B 8.6 4.4 2.94 12.0 4.8 3.2 trim
    B 9.0 4.2 2.4 11.6 4.42 2.95 paper
    # lower vent, side fins, status LEDs
    B 4.0 2.4 3.34 8.5 4.0 3.4 trim
    B 2.9 3.0 5.0 3.2 8.6 11.0 accent
    B 12.8 3.0 5.0 13.1 8.6 11.0 accent
    B 3.6 14.4 2.94 5.4 14.8 3.0 led
    B 5.8 14.4 2.94 7.6 14.8 3.0 led
    B 8.0 14.4 2.94 9.8 14.8 3.0 accent
    PanelN 9.8 2.2 3.4 12.8 4.075 body2 screen2 $u2
}

# ========================================================== 10 STATION VERIFIER
function Build-StationVerifier {
    $u = Uv1
    $u2 = Uv2
    B 5.0 0 5.0 11.0 1.0 11.0 trim
    B 4.4 1.0 4.4 11.6 2.2 11.6 trim
    B 5.8 2.2 5.8 10.2 9.2 10.2 body
    B 5.7 4.0 5.7 10.3 4.5 10.3 accent
    B 3.8 9.2 4.6 12.2 13.6 11.4 body
    B 4.4 13.6 5.2 11.6 14.2 10.8 body2
    # main screen and ID slot
    PanelN 4.6 10.6 4.6 11.4 13.0 trim screen $u
    B 5.4 8.2 4.5 10.6 8.7 4.6 trim
    B 5.8 8.3 3.6 9.2 8.55 4.5 paper
    PanelN 8.8 9.4 4.6 11.2 10.9 body2 screen2 $u2
    # top status ring
    B 4.4 14.2 5.2 11.6 14.66 5.7 led
    B 4.2 13.8 3.8 11.8 14.2 5.0 body2 @{} @{} @{ axis = 'x'; angle = -22.5; origin = @(8, 14.0, 4.4) }
    B 4.4 14.2 10.3 11.6 14.66 10.8 led
    B 4.4 14.2 5.7 4.85 14.66 10.3 led
    B 11.15 14.2 5.7 11.6 14.66 10.3 led
    # side queue rails
    B 0.6 0 6.0 1.2 7.6 6.6 trim
    B 14.8 0 6.0 15.4 7.6 6.6 trim
    B 0.2 7.2 6.1 3.9 7.6 6.6 body2
    B 12.1 7.2 6.1 15.8 7.6 6.6 body2
    B 0.2 3.4 6.1 3.9 3.8 6.6 body2
    B 12.1 3.4 6.1 15.8 3.8 6.6 body2
    # pedestal vents and foot pads
    B 6.2 2.6 5.74 9.8 5.2 5.8 trim
    B 6.4 0 5.4 7.6 0.4 6.6 trim
    B 8.4 0 5.4 9.6 0.4 6.6 trim
}

# ==================================================================== 11 E-GATE
# Split dual-pillar gate (ROUND7 reference). Two identical 4 px wide x 14 px
# deep pillars (x -2..2 and 14..18, z 1..15) rise ~21 px with a 12 px clear
# central passage. Matte white body, near-black tapered cap with a rounded
# crown and a rear overhang, glowing portrait screen in a black bezel, black
# card reader, cyan LED strip under the cap and a green chevron matrix.
#
# The animated framed-glass leaves are drawn by EGateRenderer (6 x 10 x 1 px
# boxes sliding 6 px behind the pillars); the static shell contributes the door
# floor track only. Nothing of the shell enters x 2..14, so the passage is
# genuinely empty in the block model.
#
# Palette: body #F2F4F3 / #E2E6E4, trim #1F242B, accent cyan #22B8D6.
# Stay inside the preview gate envelope (x -2.8..18.8, y 0..22.6, z -0.5..16.5)
# and out of the clear box x 2.3..13.7, y 0.4..15, z 0.4..15.

# Inner-face box helper: distances d1..d2 measured outward from the pillar's
# inner face ($ix) along $s (+1 = east, -1 = west). Point order is normalised.
function Gate-Inner([double]$ix, [double]$s, [double]$d1, [double]$y1, [double]$z1, [double]$d2, [double]$y2, [double]$z2, [string]$tex, [hashtable]$over = $null, [hashtable]$uv = $null) {
    $a = $ix + $s * $d1
    $b = $ix + $s * $d2
    if ($a -gt $b) { $t = $a; $a = $b; $b = $t }
    B $a $y1 $z1 $b $y2 $z2 $tex $over $uv
}

function Gate-Pillar([double]$x1, [double]$x2, [bool]$east, [bool]$entry) {
    $z1 = 1.0
    $z2 = 15.0
    $ix = if ($east) { $x2 } else { $x1 }
    $s = if ($east) { 1.0 } else { -1.0 }
    $midx = ($x1 + $x2) * 0.5
    $innerFace = if ($east) { 'east' } else { 'west' }

    # inner-face texture overrides (only full-square UVs, so the software
    # preview and Minecraft sample exactly the same texture area)
    $cyanIn = @{}; $cyanIn[$innerFace] = 'cyan'

    # ---- recessed black toe kick, slim white base, cyan reveal line
    B ($x1 + 0.14) 0 ($z1 + 0.18) ($x2 - 0.14) 0.2 ($z2 - 0.18) trim
    B ($x1 + 0.02) 0.2 ($z1 + 0.04) ($x2 - 0.02) 0.92 ($z2 - 0.04) body2
    B ($x1 + 0.05) 0.92 ($z1 + 0.06) ($x2 - 0.05) 0.99 ($z2 - 0.06) accent

    # ---- matte white body (dominant volume); seams are geometry only, so no
    # grey skins cover the white faces
    B $x1 0.99 $z1 $x2 18.05 $z2 body
    B ($x1 + 0.2) 4.55 1.005 ($x2 - 0.2) 4.67 1.04 body2
    B ($x1 + 0.2) 13.3 1.005 ($x2 - 0.2) 13.42 1.04 body2

    # ---- glowing portrait screen in a black bezel on the FRONT face
    B ($x1 + 0.45) 6.7 0.83 ($x2 - 0.45) 12.95 0.95 trim
    B ($x1 + 0.6) 7.0 0.76 ($x2 - 0.6) 12.65 0.84 tablet @{ north = 'tablet' }
    B ($x1 + 0.6) 6.42 0.78 ($x2 - 0.6) 6.56 0.86 cyan @{ north = 'cyan' }
    # black card / NFC reader panel under the screen
    B ($x1 + 0.3) 4.9 0.84 ($x2 - 0.3) 6.15 0.96 trim
    B ($x1 + 0.52) 5.12 0.77 ($x2 - 0.52) 5.5 0.85 accent
    B ($x1 + 0.52) 5.68 0.77 ($x2 - 0.52) 5.94 0.85 led
    # ENTRY / EXIT sign board between screen and cap
    B ($x1 + 0.2) 14.0 0.85 ($x2 - 0.2) 15.95 0.95 sign

    # ---- inner face: slim black status column and cyan edge light
    Gate-Inner $ix $s 0.0 5.4 1.55 0.14 14.6 2.35 trim
    Gate-Inner $ix $s 0.14 5.65 1.7 0.2 7.0 2.2 cyan $cyanIn
    Gate-Inner $ix $s 0.14 7.35 1.7 0.2 12.65 2.2 led $cyanIn
    Gate-Inner $ix $s 0.14 12.95 1.7 0.2 14.35 2.2 cyan $cyanIn
    Gate-Inner $ix $s 0.1 3.4 1.3 0.16 17.3 1.42 cyan $cyanIn

    # ---- cyan LED strip under the cap on front, inner and outer faces
    B ($x1 + 0.08) 17.45 0.9 ($x2 - 0.08) 18.06 1.02 cyan @{ north = 'cyan' }
    Gate-Inner $ix $s 0.1 17.45 1.35 0.18 18.06 14.65 cyan $cyanIn
    if ($east) {
        B ($x1 - 0.2) 17.45 0.95 ($x1 - 0.12) 18.06 14.85 cyan @{ west = 'cyan' }
    } else {
        B ($x2 + 0.12) 17.45 0.95 ($x2 + 0.2) 18.06 14.85 cyan @{ east = 'cyan' }
    }

    # ---- black cap: flush base, brim, tapered body, rounded crown, overhang
    B $x1 17.9 $z1 $x2 18.4 $z2 trim
    B ($x1 - 0.22) 18.35 ($z1 - 0.18) ($x2 + 0.22) 18.82 ($z2 + 0.18) trim
    B ($x1 - 0.16) 18.78 ($z1 - 0.1) ($x2 + 0.16) 19.85 ($z2 + 0.12) trim
    B ($x1 - 0.08) 19.78 ($z1 + 0.15) ($x2 + 0.08) 20.2 ($z2 - 0.15) trim
    # 22.5 deg crown slabs: long edges then short edges, all overlapping the
    # tapered body so the cap is continuous with the pillar (never floating)
    B ($x1 - 0.16) 19.55 ($z1 - 0.45) ($x2 + 0.16) 20.12 ($z1 + 0.55) trim @{} @{} @{ axis = 'x'; angle = -22.5; origin = @($midx, 19.7, ($z1 - 0.05)) }
    B ($x1 - 0.16) 19.55 ($z2 - 0.55) ($x2 + 0.16) 20.12 ($z2 + 0.45) trim @{} @{} @{ axis = 'x'; angle = 22.5; origin = @($midx, 19.7, ($z2 + 0.05)) }
    # (no rotated side wings: they would push the silhouette past the 20 px
    # envelope; the stepped brim/body already rounds the cap in plan)
    B ($x1 - 0.02) 20.0 ($z1 + 0.65) ($x2 + 0.02) 20.34 ($z2 - 0.65) trim
    # green direction matrix on the cap face, under the camera
    B ($x1 + 0.72) 18.9 0.72 ($x2 - 0.72) 19.72 0.8 arrow @{ north = 'arrow' }
    # rear overhang attached to the cap, swept lip, cyan downlight
    B ($x1 - 0.08) 18.8 14.9 ($x2 + 0.08) 20.02 16.18 trim
    B ($x1 - 0.08) 18.86 16.0 ($x2 + 0.08) 19.72 16.38 trim @{} @{} @{ axis = 'x'; angle = 22.5; origin = @($midx, 19.25, 16.15) }
    B ($x1 + 0.36) 18.72 15.12 ($x2 - 0.36) 18.82 15.94 cyan @{ down = 'cyan' }
    # rear service hatch on the white body
    B ($x1 + 0.25) 2.6 15.08 ($x2 - 0.25) 12.6 15.16 body2
    B ($x1 + 0.42) 2.85 15.16 ($x2 - 0.42) 12.35 15.22 trim

    # ---- top module: identity camera bar (entry) or scan wedge (exit)
    if ($entry) {
        B ($x1 + 0.28) 20.28 0.75 ($x2 - 0.28) 21.18 2.5 trim
        B ($x1 + 0.44) 20.44 0.67 ($x2 - 0.44) 21.04 0.76 glass @{ north = 'glass' }
        B ($x1 + 0.76) 20.56 0.63 ($x2 - 0.76) 20.9 0.7 detail
        B ($x1 + 0.54) 20.9 2.5 ($x2 - 0.54) 21.08 2.59 body2
        B ($x1 + 0.92) 20.62 2.59 ($x2 - 0.92) 20.96 2.65 cyan
    } else {
        B ($x1 + 0.3) 20.3 0.65 ($x2 - 0.3) 20.84 2.45 trim @{} @{} @{ axis = 'x'; angle = 22.5; origin = @($midx, 20.32, 1.55) }
        B ($x1 + 0.5) 20.44 0.75 ($x2 - 0.5) 20.74 1.5 glass @{} @{} @{ axis = 'x'; angle = 22.5; origin = @($midx, 20.32, 1.55) }
        B ($x1 + 0.82) 20.78 2.0 ($x2 - 0.82) 20.98 2.35 accent
    }
}

# One closed door leaf: 6 x 10 px frosted glass panel inside an opaque metal
# frame (top/bottom rails, outer/inner stiles, mid rail, lock plate and a cyan
# lock light). Static block-model geometry, so the closed barrier is visible
# regardless of entity renderer transparency. The OPEN blockstate uses the
# separate <id>_open model without these leaves.
function Gate-Leaf([double]$x1, [double]$x2, [bool]$outerLeft) {
    $glass = @{ north = 'leaf'; south = 'leaf' }
    # frosted pane (the device glass texture is opaque, so it always shows)
    B ($x1 + 0.8) 3.8 7.5 ($x2 - 0.8) 12.2 8.5 glass $glass
    # top / bottom rails
    B $x1 12.2 7.4 $x2 13 8.6 trim
    B $x1 3 7.4 $x2 3.8 8.6 trim
    # outer and inner stiles (the inner pair forms the centre seam)
    if ($outerLeft) {
        B $x1 3 7.4 ($x1 + 0.8) 13 8.6 trim
        B ($x2 - 0.8) 3 7.4 $x2 13 8.6 trim
    } else {
        B $x1 3 7.4 ($x1 + 0.8) 13 8.6 trim
        B ($x2 - 0.8) 3 7.4 $x2 13 8.6 trim
    }
    # mid rail
    B ($x1 + 0.8) 7.4 7.42 ($x2 - 0.8) 8.2 8.58 trim
    # lock plate on the leading edge + cyan lock light
    $lx = if ($outerLeft) { $x2 - 1.8 } else { $x1 + 0.6 }
    B $lx 4.4 7.36 ($lx + 1.2) 6.4 8.64 trim
    B ($lx + 0.3) 4.7 7.28 ($lx + 0.9) 5.9 7.36 cyan
}

function Build-EGate([bool]$open = $false) {
    # entry gate: identity camera bars, cyan inbound floor guide
    Gate-Pillar -2 2 $true $true
    Gate-Pillar 14 18 $false $true
    # door floor track (all below y 0.4, so the 12 px passage stays clear)
    B 2.0 0 6.85 14.0 0.12 9.15 trim
    B 2.2 0.12 7.45 13.8 0.19 7.6 body2
    B 2.2 0.12 8.4 13.8 0.19 8.55 body2
    B 2.8 0.12 7.72 13.2 0.19 8.28 accent
    # inbound cyan guide chevrons leading to the closed leaves
    B 4.6 0.12 2.5 11.4 0.19 3.3 cyan
    B 5.0 0.12 3.7 11.0 0.19 4.5 cyan
    B 5.4 0.12 4.9 10.6 0.19 5.7 cyan
    B 5.8 0.12 6.0 10.2 0.19 6.6 cyan
    # entry-only green touchdown line at the approach
    B 3.2 0.12 1.25 12.8 0.19 1.65 led
    if (-not $open) {
        Gate-Leaf 2 8 $true
        Gate-Leaf 8 14 $false
    }
}

function Build-ExitGate([bool]$open = $false) {
    # exit gate: angled scan wedges, amber outbound floor guide
    Gate-Pillar -2 2 $true $false
    Gate-Pillar 14 18 $false $false
    # door floor track
    B 2.0 0 6.85 14.0 0.12 9.15 trim
    B 2.2 0.12 7.45 13.8 0.19 7.6 body2
    B 2.2 0.12 8.4 13.8 0.19 8.55 body2
    B 2.8 0.12 7.72 13.2 0.19 8.28 accent
    # outbound warning chevrons and a stop bar on the far side
    B 4.6 0.12 10.0 11.4 0.19 10.8 warning
    B 5.0 0.12 11.2 11.0 0.19 12.0 warning
    B 5.4 0.12 12.4 10.6 0.19 13.2 warning
    B 3.0 0.12 13.45 13.0 0.19 13.85 accent
    B 3.4 0.12 14.0 12.6 0.19 14.3 led
    if (-not $open) {
        Gate-Leaf 2 8 $true
        Gate-Leaf 8 14 $false
    }
}
# ================================================== 11c SELF-SERVICE ENDORSEMENT
# A tall violet self-service kiosk: slim column, cantilevered shelf, big
# backward-tilted portrait screen, side wings and a status beacon. Its
# silhouette is intentionally nothing like the staff endorsement console.
function Build-SelfEndorsement {
    $u = Uv1
    $u2 = Uv2
    $tilt = @{ axis = 'x'; angle = 22.5; origin = @(8, 8.4, 4.2) }
    # base and column
    B 2.6 0 2.0 13.4 0.8 14.0 trim
    B 3.2 0.8 2.6 12.8 1.5 13.6 body2
    B 3.8 1.5 3.2 12.2 1.9 13.0 body2
    B 4.2 1.9 3.6 11.8 7.8 12.6 body
    B 4.7 2.3 3.4 11.3 7.4 3.62 body2
    B 4.7 7.4 3.4 11.3 7.8 12.6 body2
    # card / passport slot with an inserted permit
    B 5.4 4.5 3.3 9.0 5.1 3.45 trim
    B 5.7 4.62 3.2 8.7 4.98 3.3 paper
    B 5.4 5.2 3.28 6.2 5.5 3.35 led
    B 7.2 5.2 3.28 9.0 5.5 3.35 accent
    # receipt printer
    B 5.6 2.6 3.3 9.2 3.2 3.45 trim
    B 5.9 3.2 3.15 8.9 3.5 3.35 paper
    # cantilevered shelf with fingerprint pad and a small status screen
    B 2.2 7.8 2.6 13.8 8.4 13.4 body2
    B 2.6 8.4 2.4 13.4 8.7 3.2 trim
    B 4.6 8.4 2.9 7.4 8.9 4.2 body2
    B 4.85 8.9 3.1 7.15 8.96 4.0 glass
    B 5.1 8.96 3.3 5.5 9.02 3.7 led
    B 9.8 8.4 3.0 12.6 8.55 5.4 body2
    B 10.0 8.55 3.2 12.4 8.58 5.2 screen2 @{ up = 'screen2' } @{ up = $u2 }
    # big portrait head, tilted back 22.5 degrees (portrait 7.6 x 10.3 screen)
    B 2.5 8.4 4.9 13.5 19.6 8.8 body @{} @{} $tilt
    B 3.0 8.6 4.6 13.0 19.4 5.1 body2 @{} @{} $tilt
    B 4.2 8.85 4.42 11.8 19.15 4.6 screen @{ north = 'screen' } @{ north = $u } $tilt
    B 2.4 19.4 4.8 13.6 20.2 8.9 body2 @{} @{} $tilt
    B 4.4 18.55 4.3 11.6 18.95 4.5 led @{} @{} $tilt
    # side wings
    B 0.8 8.6 4.6 2.4 16.6 8.8 body2
    B 13.6 8.6 4.6 15.2 16.6 8.8 body2
    B 0.75 9.0 5.0 0.85 16.0 8.4 accent
    B 15.15 9.0 5.0 15.25 16.0 8.4 accent
    B 1.0 16.6 4.8 2.2 17.2 8.6 body
    B 13.8 16.6 4.8 15.0 17.2 8.6 body
    # head supports
    B 5.2 7.8 5.0 6.0 8.9 6.2 trim
    B 10.0 7.8 5.0 10.8 8.9 6.2 trim
    # top status mast and beacon
    B 7.4 17.6 5.4 8.6 21.8 6.8 trim
    B 7.15 21.8 5.15 8.85 22.5 7.05 accent
    B 7.5 22.5 5.5 8.5 22.8 6.7 led
    # rear service panel, vents and feet
    B 4.6 2.0 12.6 11.4 7.0 12.72 trim
    B 5.0 2.4 12.72 11.0 5.6 12.78 body2
    B 3.0 0 2.2 4.2 0.5 3.4 trim
    B 11.8 0 2.2 13.0 0.5 3.4 trim
    B 3.0 0 12.8 4.2 0.5 14.0 trim
    B 11.8 0 12.8 13.0 0.5 14.0 trim
}

# ================================================== 11d SELF-SERVICE VISA
# A low blue passport-desk kiosk: wide base, sloped portrait screen, passport
# tray, fingerprint pad, receipt slot and side trims. Distinct from both the
# tall endorsement kiosk and the staff visa console.
function Build-SelfVisa {
    $u = Uv1
    $u2 = Uv2
    $tilt = @{ axis = 'x'; angle = 22.5; origin = @(8, 7.4, 4.0) }
    # base and body
    B 1.8 0 2.0 14.2 0.9 14.0 trim
    B 2.2 0.9 2.4 13.8 1.5 13.6 body2
    B 3.0 1.5 3.0 13.0 7.4 13.0 body
    B 3.0 1.5 3.0 13.0 2.2 3.4 body2
    # passport tray with an inserted passport
    B 5.0 5.9 2.7 11.0 6.5 2.95 trim
    B 5.3 6.05 2.6 10.7 6.35 2.75 paper
    B 5.2 6.7 2.7 7.0 6.95 2.85 led
    B 7.2 6.7 2.7 8.4 6.95 2.85 accent
    # sloped screen head
    B 3.2 7.2 3.2 12.8 13.8 4.4 body2 @{} @{} $tilt
    B 3.6 7.4 3.05 12.4 13.4 3.35 screen @{ north = 'screen' } @{ north = $u } $tilt
    B 3.8 13.4 3.2 12.2 13.9 4.2 body @{} @{} $tilt
    # camera bar and status LED
    B 4.0 14.0 3.2 12.0 14.8 5.4 body
    B 6.2 14.3 3.1 9.8 14.6 3.25 led
    B 7.0 14.1 3.15 9.0 14.25 3.22 accent
    # side wings and accent strips
    B 1.4 1.5 3.2 2.8 9.4 13.0 body2
    B 13.2 1.5 3.2 14.6 9.4 13.0 body2
    B 1.35 2.0 3.6 1.45 8.8 12.6 accent
    B 14.55 2.0 3.6 14.65 8.8 12.6 accent
    B 1.3 9.4 3.6 2.9 10.0 12.8 body
    B 13.1 9.4 3.6 14.7 10.0 12.8 body
    # fingerprint pad and receipt slot
    B 4.2 6.9 2.9 6.0 7.2 4.0 body2
    B 4.4 7.2 3.0 5.8 7.3 3.9 led
    B 6.8 2.2 2.95 10.2 2.8 3.1 trim
    B 7.0 2.8 2.85 10.0 3.0 2.95 paper
    B 10.6 2.2 2.95 11.4 2.5 3.1 led
    # rear service panel, vents and feet
    B 4.0 2.0 12.85 12.0 6.4 12.97 trim
    B 4.6 2.4 12.97 11.4 5.8 13.03 body2
    B 4.0 0 12.9 12.0 0.5 13.05 trim
    B 2.4 0 2.4 3.2 0.4 3.2 trim
    B 12.8 0 2.4 13.6 0.4 3.2 trim
    B 2.4 0 12.8 3.2 0.4 13.6 trim
    B 12.8 0 12.8 13.6 0.4 13.6 trim
}

# =============================================================== 12 ENTRY STAMP
function Build-EntryStamp {
    $u = Uv1
    $u2 = Uv2
    B 1.2 0 2.0 14.8 1.2 14.2 trim
    B 1.6 1.2 2.4 14.4 2.2 13.8 body2
    B 3.2 2.2 3.0 12.8 3.0 12.4 trim
    B 3.8 3.0 3.6 12.2 3.08 11.6 paper
    # two press columns, beam and crown
    B 2.4 2.2 9.2 4.4 13.2 12.6 body
    B 11.6 2.2 9.2 13.6 13.2 12.6 body
    B 2.0 12.8 9.0 14.0 14.2 12.8 body2
    B 3.0 14.2 9.4 13.0 14.8 12.4 body
    B 3.4 13.0 8.94 9.6 13.42 9.0 led
    # ram and angled stamp head
    B 6.8 6.4 7.4 9.2 12.8 10.4 body2
    B 5.8 5.2 6.8 10.2 6.4 9.6 body @{} @{} @{ axis = 'x'; angle = -22.5; origin = @(8, 5.8, 8.2) }
    B 6.2 4.9 7.0 9.8 5.2 9.2 screen2 @{ down = 'screen2' } @{ down = $u2 }
    B 6.6 5.2 7.2 9.4 5.4 9.0 accent
    # lever and knob
    B 11.8 12.4 2.4 13.4 13.0 9.6 body2 @{} @{} @{ axis = 'x'; angle = 22.5; origin = @(12.6, 12.6, 9.6) }
    B 11.4 14.4 2.2 13.8 15.3 4.4 body2
    B 11.6 15.3 2.4 13.6 15.9 4.2 accent
    # ink pad and glass dome over the document bed
    B 3.6 3.0 4.2 6.2 3.4 6.0 trim
    B 3.8 3.4 4.4 6.0 3.5 5.8 accent
    B 6.6 3.0 3.6 9.4 3.4 5.2 trim
    B 4.4 3.2 3.4 11.6 4.4 6.8 glass
    B 5.0 4.4 3.8 11.0 5.2 6.4 glass
    B 5.8 5.2 4.2 10.2 5.6 6.0 glass
    # operator console with display
    B 1.0 2.2 4.0 5.0 6.4 8.0 body
    PanelN 1.2 3.2 4.0 5.2 5.7 body2 screen $u
    B 1.2 5.9 3.94 5.0 6.2 4.0 led
    B 9.4 1.6 2.06 12.0 3.225 2.4 body2
    PanelN 9.6 1.8 2.06 12.2 3.4 body2 screen2 $u2
    # feet and side seams
    B 1.4 0 2.2 2.6 0.4 3.4 trim
    B 13.4 0 2.2 14.6 0.4 3.4 trim
    B 1.4 0 12.8 2.6 0.4 14.0 trim
    B 13.4 0 12.8 14.6 0.4 14.0 trim
}

# ================================================================ 13 EXIT STAMP
function Build-ExitStamp {
    $u = Uv1
    $u2 = Uv2
    B 0.8 0 1.4 15.2 1.6 14.6 trim
    B 1.4 1.6 2.0 14.2 3.4 14.0 body2
    B 2.8 2.0 1.90 13.2 3.1 2.0 body
    B 3.0 2.4 1.84 6.0 2.9 1.9 detail
    B 7.0 2.4 1.84 10.0 2.9 1.9 detail
    # rear tower with display
    B 10.4 3.4 9.8 14.6 14.8 14.4 body
    B 10.2 14.8 9.6 14.8 15.4 14.6 body2
    B 10.6 12.0 9.74 12.4 14.4 9.8 led
    PanelN 10.6 11.2 9.8 14.2 13.6 body2 screen $u
    # cantilever beam and angled press head
    B 2.2 11.4 5.2 14.0 12.6 7.2 body2
    B 2.0 11.2 5.0 3.4 13.0 7.4 body
    B 4.2 8.8 5.6 8.8 12.0 8.2 body @{} @{} @{ axis = 'x'; angle = -22.5; origin = @(6.5, 10.4, 6.9) }
    B 4.6 8.4 5.8 8.4 8.9 8.0 accent
    B 4.9 8.55 6.1 8.1 8.85 7.6 screen2 @{ down = 'screen2' } @{ down = $u2 }
    # document tray and glass window
    B 3.2 3.4 1.2 11.6 3.8 5.0 body
    B 3.2 3.8 1.2 3.7 4.4 5.0 trim
    B 11.1 3.8 1.2 11.6 4.4 5.0 trim
    B 3.2 3.8 1.2 11.6 4.4 1.7 trim
    B 3.8 3.82 1.9 11.0 3.9 4.6 paper
    B 4.0 4.4 2.0 10.8 4.7 5.2 glass
    B 4.4 4.7 2.4 10.4 5.0 4.8 glass
    B 3.6 2.0 1.84 7.2 2.3 1.9 led
    # large side crank with knob
    B 1.0 5.0 7.0 2.2 8.0 8.0 body2
    B 0.4 5.4 6.6 1.4 6.0 7.0 body2
    B 0.3 8.0 6.8 2.5 9.2 8.2 accent
    B 1.1 9.2 7.0 1.7 9.7 8.0 accent
    # side vents, status and feet
    B 14.58 4.0 4.4 14.66 9.0 8.6 trim
    B 13.2 15.4 9.6 14.2 15.8 10.4 led
    B 1.4 0 1.6 2.6 0.4 2.8 trim
    B 13.4 0 1.6 14.6 0.4 2.8 trim
    B 1.4 0 13.2 2.6 0.4 14.4 trim
    B 13.4 0 13.2 14.6 0.4 14.4 trim
    PanelN 4.0 1.8 2.0 6.6 3.425 body2 screen2 $u2
}

# ============================================================= 14 STAMP HISTORY
function Build-StampHistory {
    $u = Uv1
    $u2 = Uv2
    B 2.0 0 2.6 14.0 1.6 14.0 trim
    B 2.4 1.6 3.0 13.6 13.4 14.0 body
    B 2.0 13.4 2.6 14.0 14.2 14.0 body2
    B 2.6 14.2 3.0 13.4 14.8 13.6 body
    B 3.0 13.0 2.94 13.0 13.4 3.0 accent
    B 3.0 12.8 2.4 13.0 13.2 3.4 body2 @{} @{} @{ axis = 'x'; angle = -22.5; origin = @(8, 13.0, 3.0) }
    PanelN 3.4 7.8 3.0 12.6 12.4 trim screen $u
    # archive drawers on the left
    for ($i = 0; $i -lt 3; $i++) {
        $y = 2.4 + $i * 1.7
        B 3.2 $y 2.84 8.2 ($y + 1.3) 3.0 body2
        B 3.6 ($y + 0.4) 2.78 6.4 ($y + 0.7) 2.84 accent
        B 7.0 ($y + 0.45) 2.78 7.9 ($y + 0.62) 2.84 led
    }
    # printer bay on the right
    B 8.6 2.4 2.84 12.8 3.4 3.0 body2
    B 9.0 3.4 2.84 12.4 4.0 3.0 trim
    B 9.2 3.0 2.62 12.2 3.62 2.9 paper
    PanelN 8.8 4.4 3.0 11.8 6.275 body2 screen2 $u2
    # side vent and rear service panel
    B 13.54 7.0 5.0 13.7 12.0 11.0 trim
    B 2.6 3.0 13.94 13.2 9.0 14.08 trim
    # feet
    B 2.2 0 2.8 3.4 0.4 4.0 trim
    B 12.6 0 2.8 13.8 0.4 4.0 trim
    B 2.2 0 12.6 3.4 0.4 13.8 trim
    B 12.6 0 12.6 13.8 0.4 13.8 trim
}

# ================================================================ 15 LOSS TERMINAL
function Build-LossTerminal {
    $u = Uv1
    $u2 = Uv2
    B 0.6 0 2.0 15.4 1.8 14.0 trim
    B 0.8 1.8 2.4 15.2 2.6 14.0 body2
    B 1.4 2.6 5.6 14.6 11.4 14.0 body
    # slanted front wedge with screen
    $sl = @{ axis = 'x'; angle = 22.5; origin = @(8, 2.6, 3.0) }
    B 1.8 2.6 2.6 14.2 9.4 3.4 body @{} @{} $sl
    B 3.2 3.8 2.16 11.2 8.8 2.28 screen @{ north = 'screen' } @{ north = $u } $sl
    B 2.4 2.8 2.44 13.6 3.2 2.56 warning
    # document tray
    B 3.0 2.2 0.8 9.0 2.7 2.6 body2
    B 3.2 2.7 0.8 3.8 3.3 2.6 trim
    B 8.2 2.7 0.8 8.8 3.3 2.6 trim
    B 3.4 2.72 1.2 8.6 2.8 2.4 paper
    # red alert beacon
    B 6.8 11.4 8.0 9.2 12.0 10.4 trim
    B 7.0 12.0 8.2 9.0 13.0 10.2 accent
    B 7.4 13.0 8.6 8.6 13.6 9.8 accent
    B 7.6 13.2 8.2 8.4 13.5 8.7 led
    # warning side bands and status LEDs
    B 1.34 3.0 6.4 1.42 10.6 13.2 warning
    B 14.58 3.0 6.4 14.66 10.6 13.2 warning
    B 2.0 11.0 5.8 14.0 11.4 6.2 led
    B 1.2 2.8 13.94 14.0 7.0 14.06 trim
    PanelN 2.0 1.0 2.0 5.0 2.875 body2 screen2 $u2
    # handles
    B 1.2 6.0 7.0 1.4 8.0 9.0 accent
    B 14.6 6.0 7.0 14.8 8.0 9.0 accent
}

# ============================================================= 16 RENEWAL MACHINE
function Build-Renewal {
    $u = Uv1
    $u2 = Uv2
    B 2.0 0 2.4 14.0 1.8 14.0 trim
    B 2.4 1.8 3.0 13.6 14.4 14.0 body
    B 2.0 14.4 2.6 14.0 15.2 14.0 body2
    B 2.8 15.2 3.4 13.2 15.8 13.6 body
    B 7.6 2.6 2.9 8.4 13.6 3.0 accent
    B 2.4 2.6 2.9 13.6 3.4 3.0 trim
    # left bay screen
    PanelN 3.2 6.8 3.0 7.1 12.0 trim screen $u
    B 3.2 12.4 2.94 7.1 12.8 3.0 led
    # right bay: receipt slot, paper, status screen
    B 8.8 6.6 2.8 12.8 7.0 3.0 trim
    B 9.0 5.8 2.6 12.6 6.6 2.85 paper
    PanelN 8.9 9.2 3.0 11.9 11.075 body2 screen2 $u2
    B 8.8 3.0 2.2 12.8 3.6 3.0 body2
    B 8.9 3.2 2.86 12.7 3.5 2.9 accent
    B 8.8 3.0 2.0 12.8 3.3 3.0 body2 @{} @{} @{ axis = 'x'; angle = -22.5; origin = @(10.8, 3.15, 2.5) }
    # card slot and status lamps
    B 3.4 4.2 2.8 6.4 4.8 3.0 trim
    B 3.6 4.35 2.74 6.2 4.65 2.8 screen
    B 3.0 13.6 2.9 4.8 14.2 3.0 led
    B 5.2 13.6 2.9 7.0 14.2 3.0 led
    B 10.9 13.6 2.9 12.7 14.2 3.0 accent
    # side vents and feet
    B 2.34 6.0 4.0 2.4 12.0 12.0 trim
    B 13.6 6.0 4.0 13.66 12.0 12.0 trim
    B 2.2 0 2.6 3.2 0.4 3.8 trim
    B 12.8 0 2.6 13.8 0.4 3.8 trim
    B 2.2 0 12.6 3.2 0.4 13.8 trim
    B 12.8 0 12.6 13.8 0.4 13.8 trim
}

# =============================================================== 17 PRINT PREVIEW
function Build-PrintPreview {
    $u = Uv1
    $u2 = Uv2
    B 0.8 0 2.0 15.2 1.4 14.4 trim
    B 1.2 1.4 2.4 14.8 5.2 14.0 body
    B 0.8 5.2 2.0 15.2 5.8 14.4 body2
    # angled preview screen above the body
    PanelTilt 2.4 5.8 5.4 9.4 9.8 6.0 22.5 @(5.9, 5.8, 5.7) trim screen $u
    B 5.2 5.2 6.0 6.6 7.6 6.6 body2
    # paper output tray with a stack and a sheet
    B 8.6 1.2 0.4 14.6 2.0 3.4 body2
    B 8.6 2.0 0.4 9.1 2.6 3.4 trim
    B 14.1 2.0 0.4 14.6 2.6 3.4 trim
    B 8.6 2.0 0.4 14.6 2.6 0.9 trim
    B 9.0 2.0 0.8 14.2 2.2 3.0 paper
    B 9.4 2.2 0.0 13.8 2.24 1.2 paper
    # paper feed on the top
    B 3.4 5.8 11.0 12.6 6.4 13.8 body2
    B 4.0 6.4 11.4 12.0 6.9 13.4 paper
    B 4.2 6.92 11.6 11.8 6.96 13.2 paper
    B 9.8 5.34 2.34 13.6 7.2 2.4 body2
    PanelN 10.2 2.6 2.4 13.2 4.475 body2 screen2 $u2
    # control buttons, LEDs, side vents and feet
    B 2.2 4.6 2.34 6.4 5.2 2.4 detail
    B 2.4 3.0 2.34 6.0 3.6 2.4 led
    B 14.36 1.6 3.0 14.44 4.2 12.8 accent
    B 1.0 0 2.2 2.2 0.3 3.4 trim
    B 13.8 0 2.2 15.0 0.3 3.4 trim
    B 1.0 0 13.0 2.2 0.3 14.2 trim
    B 13.8 0 13.0 15.0 0.3 14.2 trim
}

# ========================================================== 18 BLACKLIST TERMINAL
function Build-Blacklist {
    $u = Uv1
    $u2 = Uv2
    B 0.6 0 2.0 15.4 1.6 14.4 trim
    B 1.0 1.6 2.0 15.0 2.2 2.2 warning
    B 0.8 1.6 9.0 2.8 13.2 14.0 body
    B 13.2 1.6 9.0 15.2 13.2 14.0 body
    B 1.4 13.2 9.4 14.6 14.4 13.6 body2
    B 2.4 14.4 8.8 13.6 14.8 12.8 body2 @{} @{} @{ axis = 'x'; angle = -22.5; origin = @(8, 14.6, 12.8) }
    B 2.6 12.9 9.4 13.4 13.2 9.6 warning
    B 0.6 13.2 9.0 1.4 14.4 10.2 led
    B 13.6 13.2 9.0 14.4 14.4 10.2 led
    # camera pod under the arch
    B 7.2 11.6 9.8 8.8 13.2 11.4 body
    B 7.4 12.0 9.68 8.6 12.8 9.8 screen2 @{ north = 'screen2' } @{ north = $u2 }
    B 7.0 11.8 9.6 9.0 13.0 9.7 accent
    # back panel and main screen
    B 2.8 1.6 12.8 13.2 12.2 14.0 body2
    PanelN 3.6 4.6 12.8 12.4 10.2 trim screen $u
    PanelN 4.4 2.0 12.8 7.4 3.875 body2 screen2 $u2
    # red glass side curtains
    B 2.8 2.6 9.0 4.6 12.0 9.06 glass
    B 11.4 2.6 9.0 13.2 12.0 9.06 glass
    # biometric / card pad on the base
    B 6.2 1.6 1.6 9.8 2.4 2.4 body2
    B 6.6 2.4 1.9 9.4 2.5 2.2 accent
    B 6.8 2.5 2.0 7.6 2.56 2.2 led
    # floor guide lights, tower vents and feet
    B 4.0 0 2.4 12.0 0.12 3.0 accent
    B 0.9 3.0 13.94 1.6 7.0 14.06 trim
    B 14.4 3.0 13.94 15.1 7.0 14.06 trim
    B 1.0 0 2.2 2.0 0.3 3.2 trim
    B 14.0 0 2.2 15.0 0.3 3.2 trim
}

# ------------------------------------------------------------------ writer
function New-BlockModel($d) {
    $id = $d.id
    $texNames = @('body', 'body2', 'trim', 'top', 'accent', 'detail', 'led', 'glass', 'paper', 'warning', 'screen', 'screen2')
    # gates carry four extra slots: the ENTRY / EXIT sign board, the cyan LED
    # strip, the green direction arrow and the portrait tablet reader UI
    if ($d.gate) { $texNames += @('sign', 'cyan', 'arrow', 'tablet', 'leaf') }
    $texJson = [System.Collections.Generic.List[string]]::new()
    $texJson.Add('"particle":"veriborder:block/' + $id + '_body"') | Out-Null
    foreach ($t in $texNames) {
        $texJson.Add('"' + $t + '":"veriborder:block/' + $id + '_' + $t + '"') | Out-Null
    }
    $texBlock = '"textures":{' + ($texJson -join ',') + '}'

    $script:ELS.Clear()
    switch ($id) {
        'id_issuer' { Build-IdIssuer }
        'passport_issuer' { Build-PassportIssuer }
        'permit_issuer' { Build-PermitIssuer }
        'visa_issuer' { Build-VisaIssuer }
        'endorsement_machine' { Build-Endorsement }
        'hotel_reader' { Build-HotelReader }
        'desk_scanner' { Build-DeskScanner }
        'manual_terminal' { Build-ManualTerminal }
        'airport_checkin' { Build-AirportCheckin }
        'station_verifier' { Build-StationVerifier }
        'egate' { Build-EGate $false }
        'exit_gate' { Build-ExitGate $false }
        'self_endorsement' { Build-SelfEndorsement }
        'self_visa' { Build-SelfVisa }
        'entry_stamp' { Build-EntryStamp }
        'exit_stamp' { Build-ExitStamp }
        'stamp_history' { Build-StampHistory }
        'loss_terminal' { Build-LossTerminal }
        'renewal_machine' { Build-Renewal }
        'print_preview' { Build-PrintPreview }
        'blacklist_terminal' { Build-Blacklist }
        default { throw "No model builder for $id" }
    }
    $json = '{' + $texBlock + ',"elements":[' + ($script:ELS -join ',') + '],' + $script:DISPLAY + '}'
    [System.IO.File]::WriteAllText((Join-Path $blockModels "$id.json"), $json, $utf8)

    # Gates also ship an OPEN variant (blockstate open=true): identical shell,
    # no door leaves. Both variants share the same textures.
    if ($d.gate) {
        $script:ELS.Clear()
        if ($id -eq 'egate') { Build-EGate $true } else { Build-ExitGate $true }
        $openJson = '{' + $texBlock + ',"elements":[' + ($script:ELS -join ',') + '],' + $script:DISPLAY + '}'
        [System.IO.File]::WriteAllText((Join-Path $blockModels "${id}_open.json"), $openJson, $utf8)
    }
}