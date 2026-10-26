# ---------------------------------------------------------------------------
# VeriBorder item-art library  (26.4.1 "item asset upgrade")
#
# Dot-source this file, then set $Script:IA_OutDir to the textures\item folder
# you want the painters to write into:
#
#   . (Join-Path $PSScriptRoot 'lib_item_art.ps1')
#   $Script:IA_OutDir = Join-Path $root 'src\main\resources\assets\veriborder\textures\item'
#
# Everything here is ITEM-SIDE ART ONLY. Nothing in this file reads or writes
# textures\block, models\block, blockstates or the gate assets.
#
# House style (the brief, encoded once so all 18 icons agree):
#   * ONE key light, from the TOP-LEFT  ->  every object lights its top and its
#     left, and shadows its bottom and its right (IA-Lit / IA-Edge / ramps).
#   * ONE palette (IA-P) -> matte white shell, brushed metal, deep-blue glass,
#     cyan LED, paper, wood + gold. No ad-hoc colours in the painters.
#   * 1 px dark outline (PAL.outline) around every silhouette, drawn as an
#     opaque halo so it survives alpha hardening.
#   * BINARY alpha. Painters only ever use opaque colours; the few GDI+ ornament
#     calls (ellipse rings, text) sit on top of an already-opaque surface, so
#     they cannot create a feathered silhouette either. Save-IaBitmap still
#     flattens any pixel that a future edit sneaks in, and reports how many it
#     had to fix (that count is what the upgrade report quotes per icon).
#   * Canvas sizes: 32x32 for tool/device icons, 64x64 for document cards.
# ---------------------------------------------------------------------------

Add-Type -AssemblyName System.Drawing

# Hoisted colours: PowerShell 5.1 binds the literal "[System.Drawing.Color]::White"
# as a *string* when it is used directly as a constructor argument, so the two
# anchors and the clear colour live in variables instead.
$Script:IA_White = [System.Drawing.Color]::FromArgb(255, 255, 255)
$Script:IA_Black = [System.Drawing.Color]::FromArgb(0, 0, 0)
$Script:IA_Clear = [System.Drawing.Color]::FromArgb(0, 0, 0, 0)

# ---- palette ---------------------------------------------------------------
$Script:IA_Palette = $null
function IA-P {
    if ($null -eq $Script:IA_Palette) {
        $hex = @{
            outline   = '#0A0E14'
            shadow    = '#10161E'
            # matte white shell (device body) - top-lit ramp
            whiteHi   = '#FFFFFF'
            whiteLite = '#F4F7F9'
            white     = '#E3EAEE'
            whiteMid  = '#CFD9DF'
            whiteDk   = '#AFBCC5'
            # brushed metal trim
            metalHi   = '#F7FBFD'
            metal     = '#CBD8E1'
            metalMid  = '#9FB0BC'
            metalDk   = '#71808C'
            metalDeep = '#4A555E'
            # deep-blue glass screen
            glassHi   = '#63ACDC'
            glass1    = '#0C2740'
            glass2    = '#123B5E'
            glass3    = '#1B5686'
            glassEdge = '#081A2C'
            # cyan LED
            ledCore   = '#5FE0FF'
            ledHalo   = '#1E7FB8'
            ledDim    = '#0F4A6B'
            # paper / card stock
            paperHi   = '#FCFAF3'
            paper     = '#F1EBDC'
            paperMid  = '#E2D9C6'
            paperDk   = '#C9BCA2'
            paperLine = '#A99B7F'
            # wood + gold (brushes, seals, foil)
            woodHi    = '#DCA968'
            wood      = '#B07C40'
            woodMid   = '#8C5F2E'
            woodDk    = '#5E3D1B'
            goldHi    = '#F6E3A6'
            gold      = '#D9B65C'
            goldDk    = '#A5873A'
            # signal colours
            green     = '#4ED88A'
            greenDk   = '#1F7C4A'
            red       = '#E5564F'
            redDk     = '#8C2A26'
            amber     = '#E8A93F'
            amberDk   = '#8A5F17'
            teal      = '#2FC6B7'
            tealDk    = '#166B64'
            blue      = '#4EA1D3'
            blueDk    = '#22557A'
            violet    = '#7C6AF2'
            ink       = '#16324A'
        }
        $pal = @{}
        foreach ($k in $hex.Keys) { $pal[$k] = [System.Drawing.ColorTranslator]::FromHtml($hex[$k]) }
        $Script:IA_Palette = $pal
    }
    return $Script:IA_Palette
}

# deterministic value-noise so reruns are byte-identical
function IA-Noise([int]$x, [int]$y, [int]$salt) {
    $h = ([long]$x * 374761393) + ([long]$y * 668265263) + ([long]$salt * 1442695041)
    $h = $h -band 0x7FFFFFFF
    return [int]($h % 997)
}
function IA-Blend($a, $b, [double]$t) {
    if ($t -lt 0) { $t = 0 }
    if ($t -gt 1) { $t = 1 }
    return [System.Drawing.Color]::FromArgb(
        [int]($a.R * (1 - $t) + $b.R * $t),
        [int]($a.G * (1 - $t) + $b.G * $t),
        [int]($a.B * (1 - $t) + $b.B * $t))
}
function IA-Shade($c, [double]$t) {
    if ($t -ge 0) { return IA-Blend $c ($Script:IA_White) $t }
    return IA-Blend $c ($Script:IA_Black) (-$t)
}

# ---- canvas ----------------------------------------------------------------
function IA-NewBitmap([int]$w, [int]$h) {
    $bmp = [System.Drawing.Bitmap]::new($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    # Antialiased glyphs are safe here: every string sits on an already-opaque
    # card face, so AA blends RGB but leaves alpha at 255 (the binary-alpha gate
    # still passes). Glyphs below ~7 px turn to mush, which is why the card
    # headings are short strings set at the largest size that fits the face.
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $g.Clear($Script:IA_Clear)
    return @($bmp, $g)
}

# Hard alpha on save: every pixel becomes 0 or 255. Returns the number of pixels
# that had to be corrected, so the generator can report "alpha fixed: N px".
function IA-HardenAlpha([System.Drawing.Bitmap]$bmp) {
    $flat = [System.Drawing.Bitmap]::new($bmp.Width, $bmp.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $fixes = 0
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        for ($x = 0; $x -lt $bmp.Width; $x++) {
            $p = $bmp.GetPixel($x, $y)
            if ($p.A -eq 255) { $flat.SetPixel($x, $y, $p); continue }
            if ($p.A -eq 0) {
                # fully transparent: keep it clean (RGB zeroed so no dark halo if
                # a shader samples the texel) and do not count it as a fix.
                $flat.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, 0, 0, 0))
                continue
            }
            $fixes++
            $a = if ($p.A -ge 128) { 255 } else { 0 }
            if ($a -eq 255) { $flat.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $p.R, $p.G, $p.B)) }
            else { $flat.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(0, $p.R, $p.G, $p.B)) }
        }
    }
    $bmp.Dispose()
    return @($flat, $fixes)
}

function IA-Save($bmp, $g, [string]$path) {
    if ($g) { $g.Dispose() }
    $harden = IA-HardenAlpha $bmp
    $flat = $harden[0]
    $fixes = [int]$harden[1]
    $flat.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $flat.Dispose()
    return $fixes
}

# ---- text helpers ----------------------------------------------------------
# The audit in generate_item_textures.ps1 replaces Draw-Text / Draw-TextCentered
# with probe versions to measure where strings land, so painters MUST go through
# these two names.
function Font([double]$size, [bool]$bold = $true) {
    $style = if ($bold) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
    return [System.Drawing.Font]::new('Arial', [single]$size, $style, [System.Drawing.GraphicsUnit]::Pixel)
}
function Draw-Text($g, [string]$text, [System.Drawing.Font]$font, [System.Drawing.Color]$color, [int]$x, [int]$y) {
    $b = [System.Drawing.SolidBrush]::new($color)
    $g.DrawString($text, $font, $b, [single]$x, [single]$y)
    $b.Dispose()
}
function Draw-TextCentered($g, [string]$text, [System.Drawing.Font]$font, [System.Drawing.Color]$color, [int]$x, [int]$y, [int]$w) {
    $b = [System.Drawing.SolidBrush]::new($color)
    $sf = [System.Drawing.StringFormat]::new()
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $g.DrawString($text, $font, $b, [System.Drawing.RectangleF]::new($x, $y, $w, 20), $sf)
    $sf.Dispose(); $b.Dispose()
}
# largest font size (<= max) whose rendering still fits $maxW px
function Get-FitFontSize($g, [string]$text, [double]$max, [double]$min, [int]$maxW, [bool]$bold = $true) {
    $size = $max
    while ($size -gt $min) {
        $f = Font $size $bold
        $w = $g.MeasureString($text, $f).Width
        $f.Dispose()
        if ($w -le $maxW) { return $size }
        $size -= 0.5
    }
    return $min
}

# ---- primitives ------------------------------------------------------------
function IA-Fill($g, [int]$x, [int]$y, [int]$w, [int]$h, $col) {
    if ($w -le 0 -or $h -le 0) { return }
    $b = [System.Drawing.SolidBrush]::new($col)
    $g.FillRectangle($b, $x, $y, $w, $h)
    $b.Dispose()
}
function IA-HLine($g, [int]$x1, [int]$x2, [int]$y, $col) { IA-Fill $g $x1 $y ([Math]::Max(0, $x2 - $x1 + 1)) 1 $col }
function IA-VLine($g, [int]$x, [int]$y1, [int]$y2, $col) { IA-Fill $g $x $y1 1 ([Math]::Max(0, $y2 - $y1 + 1)) $col }

# 1 px rect ring (the house outline)
function IA-Edge($g, [int]$x, [int]$y, [int]$w, [int]$h, $col) {
    if ($w -lt 2 -or $h -lt 2) { return }
    IA-HLine $g $x ($x + $w - 1) $y $col
    IA-HLine $g $x ($x + $w - 1) ($y + $h - 1) $col
    IA-VLine $g $x $y ($y + $h - 1) $col
    IA-VLine $g ($x + $w - 1) $y ($y + $h - 1) $col
}

# Top-left-lit slab: base fill, bright 1 px top+left, dark 1 px bottom+right.
function IA-Lit($g, [int]$x, [int]$y, [int]$w, [int]$h, $hi, $base, $dk) {
    IA-Fill $g $x $y $w $h $base
    IA-HLine $g ($x + 1) ($x + $w - 2) ($y + 1) $hi
    IA-VLine $g ($x + 1) ($y + 1) ($y + $h - 2) $hi
    IA-HLine $g ($x + 1) ($x + $w - 2) ($y + $h - 2) $dk
    IA-VLine $g ($x + $w - 2) ($y + 1) ($y + $h - 2) $dk
}

# Brushed metal: horizontal (or vertical) streaks that follow the light.
function IA-Brushed($g, [int]$x, [int]$y, [int]$w, [int]$h, $hi, $base, $dk, [int]$salt, [bool]$vertical = $false) {
    IA-Fill $g $x $y $w $h $base
    if ($vertical) {
        for ($i = 0; $i -lt $w; $i++) {
            $n = IA-Noise $i 0 $salt
            if ($n % 3 -eq 0) { IA-VLine $g ($x + $i) ($y + 1) ($y + $h - 2) $dk }
            elseif ($n % 3 -eq 1) { IA-VLine $g ($x + $i) ($y + 1) ($y + $h - 2) $hi }
        }
    } else {
        for ($i = 0; $i -lt $h; $i++) {
            $n = IA-Noise 0 $i $salt
            if ($n % 3 -eq 0) { IA-HLine $g ($x + 1) ($x + $w - 2) ($y + $i) $dk }
            elseif ($n % 3 -eq 1) { IA-HLine $g ($x + 1) ($x + $w - 2) ($y + $i) $hi }
        }
    }
    IA-HLine $g ($x + 1) ($x + $w - 2) ($y + 1) $hi
    IA-HLine $g ($x + 1) ($x + $w - 2) ($y + $h - 2) $dk
}

# Deep-blue glass panel: vertical gradient + specular streak + scanlines.
function IA-Glass($g, [int]$x, [int]$y, [int]$w, [int]$h, [int]$salt, [bool]$scan = $true) {
    $p = IA-P
    $ih = [Math]::Max(1, $h - 2)
    for ($i = 0; $i -lt $ih; $i++) {
        $t = $i / [double]$ih
        $row = if ($t -lt 0.34) { IA-Blend $p.glass3 $p.glass2 ($t / 0.34) }
        elseif ($t -lt 0.72) { IA-Blend $p.glass2 $p.glass1 (($t - 0.34) / 0.38) }
        else { IA-Blend $p.glass1 $p.glassEdge (($t - 0.72) / 0.28) }
        IA-HLine $g ($x + 1) ($x + $w - 2) ($y + 1 + $i) $row
    }
    # specular: a short diagonal highlight in the upper-left corner
    $len = [Math]::Max(2, [int]($w * 0.42))
    for ($i = 0; $i -lt $len; $i++) {
        $hx = $x + 2 + $i
        $hy = $y + 2
        if ($hx -le ($x + $w - 3)) { IA-Fill $g $hx $hy 1 1 (IA-Shade $p.glassHi 0.25) }
        if ($i -lt ($len / 2)) { IA-Fill $g ($x + 2) ($y + 2 + $i) 1 1 (IA-Shade $p.glassHi 0.12) }
    }
    if ($scan -and $h -ge 8) {
        for ($i = 3; $i -lt ($h - 2); $i += 3) {
            IA-Fill $g ($x + 1) ($y + 1 + $i) ($w - 2) 1 (IA-Shade $p.glass1 -0.2)
        }
    }
    IA-Edge $g $x $y $w $h $p.glassEdge
    IA-HLine $g ($x + 1) ($x + $w - 2) $y (IA-Shade $p.glassHi 0.35)
}

# cyan LED with a dark bezel: bright core, dimmer neighbours, no AA
function IA-Led($g, [int]$x, [int]$y, [int]$w = 2, [int]$h = 1) {
    $p = IA-P
    IA-Fill $g ($x - 1) ($y - 1) ($w + 2) ($h + 2) $p.glassEdge
    IA-Fill $g $x $y $w $h $p.ledHalo
    IA-Fill $g $x $y 1 $h $p.ledCore
    IA-Fill $g ($x + $w - 1) $y 1 $h (IA-Shade $p.ledCore 0.45)
}

# paper grain: sparse 1 px speckles in two tones
function IA-Grain($g, [int]$x, [int]$y, [int]$w, [int]$h, $hi, $dk, [int]$salt, [int]$density = 7) {
    for ($iy = 0; $iy -lt $h; $iy++) {
        for ($ix = 0; $ix -lt $w; $ix++) {
            $n = IA-Noise $ix $iy ($salt + 31)
            if (($n % $density) -ne 0) { continue }
            $col = if (($n % 2) -eq 0) { $hi } else { $dk }
            IA-Fill $g ($x + $ix) ($y + $iy) 1 1 $col
        }
    }
}

# ---------------------------------------------------------------------------
#  DOCUMENT CARDS (64x64)
#  Same face rectangles the ink audit measures text against:
#    card   (id card / permit / custom id)  x=4  y=15 w=56 h=36
#    permit (custom permit)                 x=7  y=13 w=50 h=38
#    booklet(passport / custom passport)    x=13 y=6  w=38 h=52
#  The upgrade adds: 1 px dark outline, brushed foil edge, top-left light ramp,
#  paper grain, security guilloche, EMV chip + cyan LED, booklet page block and
#  bottom shadow - all opaque, all inside the canvas so the outer 1 px ring stays
#  clear for the RING check.
# ---------------------------------------------------------------------------
function New-IaDocCard([string]$name, [string]$kind, [string]$title, [string]$subtitle,
    [string]$c1hex, [string]$c2hex, [string]$foilhex, [string]$code) {
    $p = IA-P
    $bmp, $g = IA-NewBitmap 64 64
    $c1 = [System.Drawing.ColorTranslator]::FromHtml($c1hex)
    $c2 = [System.Drawing.ColorTranslator]::FromHtml($c2hex)
    $foil = [System.Drawing.ColorTranslator]::FromHtml($foilhex)

    if ($kind -eq 'booklet') { $x = 13; $y = 6; $w = 38; $h = 52 }
    elseif ($kind -eq 'permit') { $x = 7; $y = 13; $w = 50; $h = 38 }
    else { $x = 4; $y = 15; $w = 56; $h = 36 }

    # 1) drop shadow (opaque, 2 px down-right) and the 1 px silhouette outline
    IA-Fill $g ($x + 1) ($y + 2) $w $h $p.shadow
    IA-Fill $g ($x + 2) ($y + 1) $w $h $p.shadow
    IA-Edge $g ($x - 1) ($y - 1) ($w + 2) ($h + 2) $p.outline

    # 2) page block (booklets read as a booklet: a paper edge on the right)
    if ($kind -eq 'booklet') {
        IA-Fill $g ($x + $w - 1) ($y + 2) 2 ($h - 5) $p.paperMid
        IA-Edge $g ($x + $w - 1) ($y + 2) 2 ($h - 5) $p.paperDk
        for ($i = 4; $i -lt ($h - 6); $i += 4) { IA-Fill $g ($x + $w) ($y + $i) 1 1 $p.paperHi }
    }

    # 3) cover: vertical ramp lit from the top, with a light left edge column
    $ih = $h - 2
    for ($i = 0; $i -lt $ih; $i++) {
        $t = $i / [double]$ih
        $row = if ($t -lt 0.10) { IA-Shade $c1 0.34 }
        elseif ($t -lt 0.55) { IA-Blend (IA-Shade $c1 0.16) $c1 (($t - 0.10) / 0.45) }
        elseif ($t -lt 0.86) { IA-Blend $c1 $c2 (($t - 0.55) / 0.31) }
        else { IA-Shade $c2 -0.22 }
        IA-HLine $g ($x + 1) ($x + $w - 2) ($y + 1 + $i) $row
    }
    IA-VLine $g ($x + 1) ($y + 1) ($y + $h - 2) (IA-Shade $c1 0.40)
    IA-VLine $g ($x + 2) ($y + 1) ($y + $h - 2) (IA-Shade $c1 0.18)
    IA-VLine $g ($x + $w - 2) ($y + 1) ($y + $h - 2) (IA-Shade $c2 -0.30)

    # 4) paper grain over the cover (card stock, not a flat fill)
    IA-Grain $g ($x + 3) ($y + 2) ($w - 5) ($h - 4) (IA-Shade $c1 0.10) (IA-Shade $c2 -0.12) 117 17

    # 5) brushed foil edge: bright along the light side, dark on the shadow side
    IA-HLine $g ($x + 1) ($x + $w - 2) ($y + 1) (IA-Shade $foil 0.35)
    IA-VLine $g ($x + 1) ($y + 1) ($y + $h - 2) (IA-Shade $foil 0.25)
    IA-HLine $g ($x + 1) ($x + $w - 2) ($y + $h - 2) (IA-Shade $foil -0.45)
    IA-VLine $g ($x + $w - 2) ($y + 1) ($y + $h - 2) (IA-Shade $foil -0.35)
    for ($i = 3; $i -lt ($w - 4); $i += 5) {
        if ((IA-Noise $i 7 5) % 3 -ne 0) { IA-Fill $g ($x + $i) ($y + 1) 1 1 (IA-Shade $foil 0.6) }
    }

    if ($kind -eq 'booklet') {
        # gold emblem ring with a security guilloche behind it
        $ex = $x + 5; $ey = $y + 7
        for ($r = 0; $r -lt 3; $r++) {
            $rr = 11 - $r * 2
            $pen = [System.Drawing.Pen]::new((IA-Shade $foil (-0.30 + 0.12 * $r)), 1)
            $g.DrawEllipse($pen, ($ex + 4 - $r), ($ey + 4 - $r), $rr, $rr)
            $pen.Dispose()
        }
        $pen = [System.Drawing.Pen]::new((IA-Shade $foil 0.25), 1.4)
        $cx = $ex + 9; $cy = $ey + 9
        $g.DrawLine($pen, $cx, ($cy - 5), $cx, ($cy + 5))
        $g.DrawLine($pen, ($cx - 5), $cy, ($cx + 5), $cy)
        $g.DrawEllipse($pen, ($cx - 3), ($cy - 3), 6, 6)
        $pen.Dispose()

        # cover text block: five short rules, then the MRZ band
        for ($i = 0; $i -lt 5; $i++) {
            IA-HLine $g ($x + 7) ($x + 30) ($y + 25 + $i * 4) (IA-Shade $c1 0.22)
        }
        IA-Fill $g ($x + 5) ($y + $h - 12) ($w - 11) 3 (IA-Shade $c1 0.30)
        for ($i = 0; $i -lt ($w - 13); $i += 3) {
            IA-Fill $g ($x + 6 + $i) ($y + $h - 11) 2 1 (IA-Shade $foil 0.20)
        }
        IA-Led $g ($x + $w - 6) ($y + 3) 2 1

        Draw-TextCentered $g $title (Font 5) (IA-Shade $foil 0.50) $x ($y + 44) $w
    } else {
        # Card face layout for the 56x36 (and 50x38 permit) faces. Vertical plan,
        # face rows 0..35:  3..12 heading band | 15..27 photo + data column |
        # 28..32 MRZ band | 34 bottom foil. The heading owns a single big line
        # (9 px) because two stacked lines at 5-6 px are unreadable; the subtitle
        # moves down into the data column where it has room.
        $innerW = $w - 4
        IA-Fill $g ($x + 2) ($y + 3) ($w - 4) 10 (IA-Shade $c2 -0.55)
        IA-HLine $g ($x + 2) ($x + $w - 3) ($y + 3) (IA-Shade $foil 0.20)
        IA-HLine $g ($x + 2) ($x + $w - 3) ($y + 12) (IA-Shade $c2 -0.72)

        Draw-TextCentered $g $title (Font (Get-FitFontSize $g $title 10 7 $innerW $false) $false) (IA-Shade $foil 0.80) $x ($y + 4) $w

        # photo box: dark well, head + shoulders, 1 px frame
        $px = $x + 4; $py = $y + 15
        IA-Fill $g $px $py 13 13 $p.glassEdge
        IA-Edge $g $px $py 13 13 (IA-Shade $c2 -0.60)
        IA-Fill $g ($px + 1) ($py + 1) 11 11 (IA-Blend $p.glass2 $p.glass3 0.45)
        $face = [System.Drawing.SolidBrush]::new((IA-Blend $p.glassHi $p.glass3 0.45))
        $g.FillEllipse($face, ($px + 4), ($py + 2), 5, 5)
        $g.FillRectangle($face, ($px + 2), ($py + 7), 9, 5)
        $face.Dispose()

        # data column right of the photo: subtitle line, then two rule pairs
        if ($subtitle -ne '') {
            Draw-Text $g $subtitle (Font (Get-FitFontSize $g $subtitle 5 3.5 ($w - 26)) $false) $Script:IA_White ($x + 19) ($y + 15)
        }
        for ($i = 0; $i -lt 2; $i++) {
            $ry = $y + 21 + $i * 4
            IA-HLine $g ($x + 19) ($x + 22) $ry (IA-Shade $foil 0.10)
            IA-HLine $g ($x + 24) ($x + $w - 6) $ry (IA-Blend $p.paperHi (IA-Shade $c2 -0.25) 0.35)
        }

        # EMV chip (right aligned above the MRZ) + cyan status LED on the band
        IA-Fill $g ($x + $w - 11) ($y + 16) 6 4 (IA-Shade $foil -0.05)
        IA-Edge $g ($x + $w - 11) ($y + 16) 6 4 (IA-Shade $foil -0.50)
        IA-HLine $g ($x + $w - 10) ($x + $w - 7) ($y + 17) (IA-Shade $foil 0.45)
        IA-HLine $g ($x + $w - 10) ($x + $w - 7) ($y + 18) $p.goldDk
        IA-Led $g ($x + $w - 6) ($y + 4) 2 1

        # MRZ band across the bottom of the face
        $mz = $y + $h - 8
        IA-Fill $g ($x + 4) $mz ($w - 8) 5 (IA-Blend $p.paperHi (IA-Shade $c2 -0.35) 0.30)
        IA-Edge $g ($x + 4) $mz ($w - 8) 5 (IA-Blend $p.paperDk (IA-Shade $c2 -0.35) 0.35)
        for ($i = 0; $i -lt ($w - 30); $i += 2) {
            if ((IA-Noise $i 3 9) % 3 -ne 0) {
                IA-Fill $g ($x + 5 + $i) ($mz + 1) 1 3 (IA-Shade $p.ink 0.30)
            }
        }
        IA-Fill $g ($x + $w - 16) ($mz + 1) 12 3 (IA-Shade $p.ink -0.10)
        Draw-TextCentered $g $code (Font 5) $Script:IA_White ($x + $w - 16) ($mz) 12
    }


    $fixes = IA-Save $bmp $g (Join-Path $Script:IA_OutDir "$name.png")
    return $fixes
}

# Custom (player-authored) document: neutral paper stock with the same frame.
function New-IaCustomDoc([string]$name, [string]$kind) {
    $p = IA-P
    $bmp, $g = IA-NewBitmap 64 64
    if ($kind -eq 'booklet') { $x = 13; $y = 6; $w = 38; $h = 52 }
    elseif ($kind -eq 'permit') { $x = 7; $y = 13; $w = 50; $h = 38 }
    else { $x = 4; $y = 15; $w = 56; $h = 36 }

    IA-Fill $g ($x + 1) ($y + 2) $w $h $p.shadow
    IA-Fill $g ($x + 2) ($y + 1) $w $h $p.shadow
    IA-Edge $g ($x - 1) ($y - 1) ($w + 2) ($h + 2) $p.outline

    if ($kind -eq 'booklet') {
        IA-Fill $g ($x + $w - 1) ($y + 2) 2 ($h - 5) $p.paperMid
        IA-Edge $g ($x + $w - 1) ($y + 2) 2 ($h - 5) $p.paperDk
        for ($i = 4; $i -lt ($h - 6); $i += 4) { IA-Fill $g ($x + $w) ($y + $i) 1 1 $p.paperHi }
    }

    # neutral stock: paper ramp, lit from the top-left
    $ih = $h - 2
    for ($i = 0; $i -lt $ih; $i++) {
        $t = $i / [double]$ih
        $row = if ($t -lt 0.12) { $p.paperHi }
        elseif ($t -lt 0.80) { IA-Blend $p.paperHi $p.paper ($t / 0.80) }
        else { IA-Blend $p.paper $p.paperMid (($t - 0.80) / 0.20) }
        IA-HLine $g ($x + 1) ($x + $w - 2) ($y + 1 + $i) $row
    }
    IA-VLine $g ($x + 1) ($y + 1) ($y + $h - 2) $Script:IA_White
    IA-VLine $g ($x + $w - 2) ($y + 1) ($y + $h - 2) $p.paperDk
    IA-Grain $g ($x + 3) ($y + 2) ($w - 5) ($h - 4) $p.paperHi $p.paperDk 211 13

    # brushed steel edge (neutral documents get metal, not gold foil)
    IA-HLine $g ($x + 1) ($x + $w - 2) ($y + 1) $p.metalHi
    IA-HLine $g ($x + 1) ($x + $w - 2) ($y + $h - 2) $p.metalMid
    IA-VLine $g ($x + 1) ($y + 1) ($y + $h - 2) $p.metalHi
    IA-VLine $g ($x + $w - 2) ($y + 1) ($y + $h - 2) $p.metalMid
    for ($i = 3; $i -lt ($w - 4); $i += 4) {
        if ((IA-Noise $i 11 3) % 3 -ne 0) { IA-Fill $g ($x + $i) ($y + 1) 1 1 $p.metalHi }
    }

    if ($kind -eq 'booklet') {
        $ex = $x + 14; $ey = $y + 11
        $pen = [System.Drawing.Pen]::new($p.metalDk, 1.2)
        $g.DrawEllipse($pen, $ex, $ey, 10, 10)
        $g.DrawLine($pen, ($ex + 5), ($ey + 1), ($ex + 5), ($ey + 9))
        $g.DrawLine($pen, ($ex + 1), ($ey + 5), ($ex + 9), ($ey + 5))
        $pen.Dispose()
        for ($i = 0; $i -lt 5; $i++) { IA-HLine $g ($x + 8) ($x + 30) ($y + 26 + $i * 4) $p.paperLine }
        IA-Fill $g ($x + 5) ($y + $h - 12) ($w - 11) 3 $p.paperMid
        for ($i = 0; $i -lt ($w - 13); $i += 3) { IA-Fill $g ($x + 6 + $i) ($y + $h - 11) 2 1 $p.paperLine }
        IA-Led $g ($x + $w - 6) ($y + 3) 2 1
        Draw-TextCentered $g 'CUSTOM' (Font 7) (IA-Shade $p.ink 0.30) $x ($y + 3) $w
        Draw-TextCentered $g 'DOCUMENT' (Font 6) (IA-Shade $p.ink 0.15) $x ($y + 42) $w
    } else {
        $ex = $x + 7; $ey = $y + 7
        $pen = [System.Drawing.Pen]::new($p.metalDk, 1.2)
        $g.DrawEllipse($pen, $ex, $ey, 10, 10)
        $g.DrawLine($pen, ($ex + 5), ($ey + 1), ($ex + 5), ($ey + 9))
        $g.DrawLine($pen, ($ex + 1), ($ey + 5), ($ex + 9), ($ey + 5))
        $pen.Dispose()
        Draw-TextCentered $g 'CUSTOM' (Font (Get-FitFontSize $g 'CUSTOM' 7 4 ($w - 4))) (IA-Shade $p.ink 0.30) $x ($y + 5) $w

        $px = $x + 6; $py = $y + 14
        IA-Fill $g $px $py 13 18 $p.paperMid
        IA-Edge $g $px $py 13 18 $p.paperDk
        IA-Fill $g ($px + 1) ($py + 1) 11 16 $p.white
        $face = [System.Drawing.SolidBrush]::new($p.whiteDk)
        $g.FillEllipse($face, ($px + 4), ($py + 3), 5, 5)
        $g.FillRectangle($face, ($px + 2), ($py + 9), 9, 7)
        $face.Dispose()
        for ($i = 0; $i -lt 4; $i++) {
            $ry = $y + 16 + $i * 4
            IA-HLine $g ($x + 22) ($x + 25) $ry $p.paperLine
            IA-HLine $g ($x + 27) ($x + $w - 8) $ry $p.paperLine
        }
        IA-Led $g ($x + $w - 6) ($y + 3) 2 1
        IA-Fill $g ($x + 6) ($y + $h - 9) ($w - 12) 6 $p.white
        IA-Edge $g ($x + 6) ($y + $h - 9) ($w - 12) 6 $p.whiteMid
        for ($i = 0; $i -lt ($w - 20); $i += 2) {
            if ((IA-Noise $i 5 13) % 3 -ne 0) { IA-Fill $g ($x + 7 + $i) ($y + $h - 8) 1 1 $p.whiteDk }
        }
        Draw-Text $g 'TYPE' (Font 6) (IA-Shade $p.ink 0.30) ($x + $w - 21) ($y + $h - 10)
    }

    $fixes = IA-Save $bmp $g (Join-Path $Script:IA_OutDir "$name.png")
    return $fixes
}

# ---------------------------------------------------------------------------
#  BRUSHES (32x32, item/handheld)
#  Diagonal tool icon: bristle fan top-right, brushed ferrule, tapered wooden
#  handle bottom-left. Each brush gets its own accent band so the four are
#  distinguishable at 1x. Built row by row so a 1 px outline falls out naturally.
# ---------------------------------------------------------------------------
function New-IaBrush([string]$name, [string]$accentHex, [string]$bristleHex, [int]$salt) {
    $p = IA-P
    $bmp, $g = IA-NewBitmap 32 32
    $accent = [System.Drawing.ColorTranslator]::FromHtml($accentHex)
    $bristle = [System.Drawing.ColorTranslator]::FromHtml($bristleHex)
    $bristleHi = IA-Blend $bristle ($Script:IA_White) 0.45
    $bristleDk = IA-Blend $bristle ($Script:IA_Black) 0.35

    # row geometry: centre line + half width, bristles 2..12, ferrule 12..18, handle 18..30
    $rows = @()
    for ($y = 2; $y -le 29; $y++) {
        if ($y -le 12) {
            # bristle fan: centre 20 -> 15, half width 3 -> 5
            $t = ($y - 2) / 10.0
            $c = [int][Math]::Round(20 - 5 * $t)
            $hw = [int][Math]::Round(3 + 2 * $t)
            $rows += , @($y, $c, $hw, 'bristle')
        } elseif ($y -le 18) {
            # ferrule: centre 15 -> 13, half width 5 -> 4
            $t = ($y - 13) / 5.0
            $c = [int][Math]::Round(15 - 2 * $t)
            $hw = [int][Math]::Round(5 - 1 * $t)
            $rows += , @($y, $c, $hw, 'ferrule')
        } else {
            # handle: centre 13 -> 7, half width 4 -> 2 (tapered grip)
            $t = ($y - 19) / 11.0
            $c = [int][Math]::Round(13 - 6 * $t)
            $hw = [int][Math]::Round(4 - 2 * $t)
            if ($hw -lt 2) { $hw = 2 }
            $rows += , @($y, $c, $hw, 'handle')
        }
    }

    # (the 1 px outline is added after the fill pass, as a real silhouette pass)
    # 2) fill pass: lit left, dark right
    foreach ($r in $rows) {
        $y = $r[0]; $c = $r[1]; $hw = $r[2]; $part = $r[3]
        $x0 = $c - $hw; $x1 = $c + $hw
        switch ($part) {
            'bristle' {
                IA-Fill $g $x0 $y ($x1 - $x0 + 1) 1 $bristle
                IA-Fill $g $x0 $y 2 1 $bristleHi
                IA-Fill $g ($x1 - 1) $y 2 1 $bristleDk
            }
            'ferrule' {
                IA-Fill $g $x0 $y ($x1 - $x0 + 1) 1 $p.metal
                IA-Fill $g $x0 $y 1 1 $p.metalHi
                IA-Fill $g ($x1 - 1) $y 2 1 $p.metalDk
                if ((($y - 13) % 2) -eq 0) { IA-Fill $g ($x0 + 1) $y ($x1 - $x0 - 1) 1 $p.metalMid }
            }
            'handle' {
                IA-Fill $g $x0 $y ($x1 - $x0 + 1) 1 $p.wood
                IA-Fill $g $x0 $y 1 1 $p.woodHi
                IA-Fill $g ($x1 - 1) $y 2 1 $p.woodDk
                if ((IA-Noise $y 3 $salt) % 4 -eq 0) { IA-Fill $g ($x0 + 1) $y 1 1 $p.woodMid }
                if ((IA-Noise $y 5 $salt) % 6 -eq 0) { IA-Fill $g ($x0 + 2) $y 1 1 $p.woodMid }
            }
        }
    }
    # 3) bristle tips: serrate the top two rows so the fan does not read as a slab
    for ($i = 0; $i -lt 9; $i++) {
        $x = 16 + $i
        if ((IA-Noise $i 1 $salt) % 3 -ne 0) {
            IA-Fill $g $x 1 1 1 $p.outline
        } else {
            IA-Fill $g $x 1 1 1 $bristleHi
        }
    }
    # 4) accent band across the ferrule + rivets
    foreach ($r in $rows) {
        $y = $r[0]
        if ($y -lt 16 -or $y -gt 17) { continue }
        $c = $r[1]; $hw = $r[2]
        IA-Fill $g ($c - $hw + 1) $y ($hw * 2 - 1) 1 $accent
        IA-Fill $g ($c - $hw + 1) $y 1 1 (IA-Shade $accent 0.35)
        IA-Fill $g ($c + $hw - 2) $y 1 1 (IA-Shade $accent -0.35)
    }
    IA-Fill $g 13 15 1 1 $p.metalHi
    IA-Fill $g 17 17 1 1 $p.metalHi
    # 5) heel cap on the handle
    IA-Fill $g 6 29 5 1 $p.outline
    IA-Fill $g 7 29 3 1 $p.woodMid
    # 6) silhouette outline: every transparent pixel touching the shape becomes
    # the 1 px dark outline. Row-wise halos left diagonal gaps, so the outline is
    # derived from the finished alpha instead.
    $solid = New-Object 'bool[]' 1024
    for ($yy = 0; $yy -lt 32; $yy++) {
        for ($xx = 0; $xx -lt 32; $xx++) {
            $solid[$yy * 32 + $xx] = ($bmp.GetPixel($xx, $yy).A -gt 0)
        }
    }
    for ($yy = 0; $yy -lt 32; $yy++) {
        for ($xx = 0; $xx -lt 32; $xx++) {
            if ($solid[$yy * 32 + $xx]) { continue }
            $touch = $false
            for ($dy = -1; $dy -le 1; $dy++) {
                for ($dx = -1; $dx -le 1; $dx++) {
                    if ($dx -eq 0 -and $dy -eq 0) { continue }
                    $nx = $xx + $dx; $ny = $yy + $dy
                    if ($nx -lt 0 -or $ny -lt 0 -or $nx -ge 32 -or $ny -ge 32) { continue }
                    if ($solid[$ny * 32 + $nx]) { $touch = $true; break }
                }
                if ($touch) { break }
            }
            if ($touch) { $bmp.SetPixel($xx, $yy, $p.outline) }
        }
    }

    $fixes = IA-Save $bmp $g (Join-Path $Script:IA_OutDir "$name.png")
    return $fixes
}

# ---------------------------------------------------------------------------
#  CREDENTIAL REGISTRY (32x32) - ledger book, dark cover, gold bands, seal
# ---------------------------------------------------------------------------
function New-IaRegistryIcon {
    $p = IA-P
    $bmp, $g = IA-NewBitmap 32 32
    $cover = [System.Drawing.ColorTranslator]::FromHtml('#1E3A5F')
    $coverHi = [System.Drawing.ColorTranslator]::FromHtml('#2B4E7C')

    # page block (right) then the cover slab, both outlined
    IA-Edge $g 3 3 27 27 $p.outline
    IA-Fill $g 22 4 6 24 $p.paperMid
    for ($i = 5; $i -lt 27; $i += 3) { IA-Fill $g 23 $i 4 1 $p.paperDk }
    IA-Fill $g 23 4 5 24 $p.paper
    for ($i = 5; $i -lt 27; $i += 3) { IA-Fill $g 23 $i 4 1 $p.paperHi }

    IA-Fill $g 4 4 19 24 $cover
    IA-HLine $g 4 22 4 (IA-Shade $cover 0.35)
    IA-VLine $g 4 4 27 (IA-Shade $cover 0.45)
    IA-VLine $g 22 4 27 (IA-Shade $cover -0.35)
    IA-HLine $g 4 22 27 (IA-Shade $cover -0.30)
    IA-Grain $g 5 5 17 22 (IA-Shade $cover 0.10) (IA-Shade $cover -0.12) 71 9

    # spine
    IA-Fill $g 4 4 2 24 (IA-Shade $cover -0.55)
    IA-VLine $g 5 5 26 (IA-Shade $cover -0.20)

    # gold bands + bookmark ribbon
    IA-Fill $g 7 6 14 1 $p.gold
    IA-Fill $g 7 25 14 1 $p.goldDk
    IA-Fill $g 19 7 2 9 $p.gold
    IA-Fill $g 19 7 1 8 $p.goldHi
    IA-Fill $g 19 15 2 2 (IA-Shade $p.gold -0.25)

    # page rules
    for ($i = 0; $i -lt 3; $i++) { IA-HLine $g 8 (14 - $i) (9 + $i * 3) $p.paperMid }
    IA-HLine $g 8 12 18 $p.paperDk

    # wax seal + LED
    $seal = [System.Drawing.SolidBrush]::new($p.goldDk)
    $g.FillEllipse($seal, 14, 19, 8, 7)
    $seal.Dispose()
    $sealHi = [System.Drawing.SolidBrush]::new($p.gold)
    $g.FillEllipse($sealHi, 15, 20, 6, 5)
    $sealHi.Dispose()
    IA-Fill $g 18 22 2 2 $p.goldHi
    IA-Led $g 18 5 2 1

    $fixes = IA-Save $bmp $g (Join-Path $Script:IA_OutDir 'credential_registry.png')
    return $fixes
}

# ---------------------------------------------------------------------------
#  BORDER TERMINAL (32x32) - admin console icon: shell + glass + LED + vents
# ---------------------------------------------------------------------------
function New-IaBorderTerminalIcon {
    $p = IA-P
    $bmp, $g = IA-NewBitmap 32 32
    # silhouette outline
    IA-Edge $g 2 2 28 27 $p.outline
    # matte white shell, lit top-left
    IA-Lit $g 3 3 26 25 $p.whiteHi $p.whiteLite $p.whiteMid
    IA-Fill $g 3 3 26 25 $p.whiteLite
    IA-HLine $g 4 27 4 $p.whiteHi
    IA-VLine $g 4 4 26 $p.whiteHi
    IA-HLine $g 4 27 26 $p.whiteMid
    IA-VLine $g 27 4 26 $p.whiteMid
    IA-Grain $g 5 5 23 21 $p.whiteHi $p.whiteMid 31 13

    # brushed metal top cap + chamfer steps
    IA-Brushed $g 5 5 22 2 $p.metalHi $p.metal $p.metalMid 7
    IA-HLine $g 5 26 7 (IA-Shade $p.metalDk 0.0)

    # glass screen
    IA-Glass $g 7 9 18 11 3
    # roster lines on the glass
    for ($i = 0; $i -lt 3; $i++) {
        IA-Fill $g 8 (11 + $i * 3) 3 1 $p.ledHalo
        IA-Fill $g 12 (11 + $i * 3) (11 - $i) 1 $p.glassHi
    }
    # header bar with the accent + LED
    IA-Fill $g 7 9 18 1 (IA-Shade $p.blue 0.25)
    IA-Led $g 24 5 2 1

    # lower deck: vent slots + label plate
    for ($i = 0; $i -lt 4; $i++) { IA-Fill $g 7 (22 + $i) 9 1 $p.whiteDk }
    IA-Fill $g 19 22 6 3 (IA-Shade $p.blue -0.25)
    IA-PlotText $g 'ADM' 19 22 1 $p.whiteHi
    # feet
    IA-Fill $g 4 28 4 2 $p.metalDk
    IA-Fill $g 24 28 4 2 $p.metalDk

    $fixes = IA-Save $bmp $g (Join-Path $Script:IA_OutDir 'border_terminal.png')
    return $fixes
}

# ---------------------------------------------------------------------------
#  DOCUMENT MODIFIER (32x32) - a document plus a metal pen with a gold nib
# ---------------------------------------------------------------------------
function New-IaModifierIcon {
    $p = IA-P
    $bmp, $g = IA-NewBitmap 32 32
    # sheet
    IA-Edge $g 2 4 22 24 $p.outline
    IA-Fill $g 3 5 20 22 $p.paper
    IA-HLine $g 3 22 5 $p.paperHi
    IA-VLine $g 3 5 26 $p.paperHi
    IA-HLine $g 3 22 26 $p.paperMid
    IA-VLine $g 22 5 26 $p.paperMid
    IA-Grain $g 5 7 16 18 $p.paperHi $p.paperDk 53 11
    # folded corner
    IA-Fill $g 19 5 4 4 $p.paperMid
    IA-Fill $g 20 5 3 1 $p.paperHi
    # text rules
    for ($i = 0; $i -lt 4; $i++) { IA-HLine $g 6 19 (9 + $i * 3) $p.paperLine }
    # document header band
    IA-Fill $g 6 6 10 2 (IA-Shade $p.blue -0.10)
    IA-Led $g 17 6 2 1

    # pen: diagonal metal barrel + gold nib, crossing the sheet
    for ($i = 0; $i -lt 13; $i++) {
        $x = 12 + $i
        $y = 25 - $i
        IA-Fill $g ($x - 1) ($y - 1) 4 4 $p.outline
        if ($i -ge 9) {
            IA-Fill $g $x $y 3 3 $p.gold
            IA-Fill $g $x $y 1 1 $p.goldHi
        } else {
            IA-Fill $g $x $y 3 3 $p.metal
            IA-Fill $g $x $y 1 1 $p.metalHi
            IA-Fill $g ($x + 1) ($y + 1) 1 1 $p.metalMid
        }
    }
    # pen tip
    IA-Fill $g 25 11 2 2 $p.outline
    IA-Fill $g 25 11 1 1 $p.goldHi
    # grip band
    IA-Fill $g 18 17 3 3 (IA-Shade $p.blue -0.25)
    IA-Fill $g 18 17 1 1 $p.ledHalo

    $fixes = IA-Save $bmp $g (Join-Path $Script:IA_OutDir 'document_modifier.png')
    return $fixes
}

# ---------------------------------------------------------------------------
#  ITEM SCREEN TEXTURES (32x32)
#  The little screen on a 3D item model, painted in the SAME visual language as
#  the matching block device screen, but category-specific:
#
#    issuer    document thumbnail + "ISSUE"          (id / passport / permit)
#    reader    document card + green tick + red cross
#    stamp     wax seal ring + date
#    terminal  roster rows + IN / OUT badges
#    brush     (no screen - brushes keep material detail only)
#
#  $variant picks the thumbnail inside a category (card / booklet / permit).
# ---------------------------------------------------------------------------
function New-IaItemScreen([string]$name, [string]$category, [string]$accentHex, [string]$variant) {
    $p = IA-P
    $bmp, $g = IA-NewBitmap 32 32
    $accent = [System.Drawing.ColorTranslator]::FromHtml($accentHex)
    $accentHi = IA-Blend $accent ($Script:IA_White) 0.45
    $accentDk = IA-Blend $accent ($Script:IA_Black) 0.45

    # glass backing + 1 px bezel
    IA-Glass $g 0 0 32 32 5 $false
    IA-Edge $g 0 0 32 32 $p.glassEdge

    switch ($category) {
        'issuer' {
            # header bar
            IA-Fill $g 0 0 32 10 $accentDk
            IA-HLine $g 0 31 0 $accentHi
            IA-HLine $g 0 31 9 $accent
            IA-PlotTextCentered $g 'ISSUE' 16 2 1 $Script:IA_White
            # document thumbnail on the left
            if ($variant -eq 'booklet') {
                IA-Fill $g 3 9 9 15 $accentDk
                IA-Edge $g 3 9 9 15 $accent
                IA-Fill $g 4 10 7 13 (IA-Blend $accentDk $p.glass1 0.35)
                IA-Fill $g 5 12 5 1 $p.goldHi
                IA-Fill $g 5 15 5 1 $p.glassHi
                IA-Fill $g 5 17 4 1 $p.glassHi
                IA-Fill $g 5 19 5 1 $p.glassHi
            } elseif ($variant -eq 'permit') {
                IA-Fill $g 3 12 11 8 $p.paperHi
                IA-Edge $g 3 12 11 8 $p.paperDk
                IA-Fill $g 5 14 4 4 $p.glass3
                for ($i = 0; $i -lt 3; $i++) { IA-Fill $g 10 (14 + $i * 2) 3 1 $p.paperLine }
                IA-Fill $g 3 12 11 2 $accent
            } elseif ($variant -eq 'visa') {
                # visa page: coloured diagonal band, 'VISA' wordmark and a QR block
                IA-Fill $g 3 10 11 14 $p.paperHi
                IA-Edge $g 3 10 11 14 $p.paperDk
                for ($i = 0; $i -lt 10; $i++) { IA-Fill $g (3 + $i) (21 - $i) 1 1 $accent }
                for ($i = 0; $i -lt 10; $i++) { IA-Fill $g (3 + $i) (22 - $i) 1 1 $accentDk }
                IA-PlotText $g 'VISA' 4 12 1 $p.ink
                for ($i = 0; $i -lt 3; $i++) { IA-Fill $g 5 (18 + $i * 2) 6 1 $p.paperLine }
                for ($iy = 0; $iy -lt 4; $iy++) {
                    for ($ix = 0; $ix -lt 4; $ix++) {
                        if ((IA-Noise $ix $iy 41) % 3 -ne 0) { IA-Fill $g (9 + $ix) (20 + $iy) 1 1 $p.ink }
                    }
                }
            } elseif ($variant -eq 'endorse') {
                # endorsement page: a form with a wax seal ring in the lower corner
                IA-Fill $g 3 10 11 14 $p.paperHi
                IA-Edge $g 3 10 11 14 $p.paperDk
                for ($i = 0; $i -lt 4; $i++) { IA-Fill $g 4 (12 + $i * 2) 7 1 $p.paperLine }
                $pen = [System.Drawing.Pen]::new($accent, 2)
                $g.DrawEllipse($pen, 9, 17, 6, 6)
                $pen.Dispose()
                IA-Fill $g 11 19 2 2 $p.goldHi
            } else {
                IA-Fill $g 3 11 11 8 $p.paperHi
                IA-Edge $g 3 11 11 8 $p.paperDk
                IA-Fill $g 4 12 9 2 $accent
                IA-Fill $g 5 15 3 3 $p.glass3
                IA-Fill $g 9 15 4 1 $p.paperLine
                IA-Fill $g 9 17 3 1 $p.paperLine
            }
            # data rows + MRZ + status LED
            for ($i = 0; $i -lt 4; $i++) { IA-Fill $g 16 (10 + $i * 3) (13 - $i) 1 $p.glass3 }
            IA-Fill $g 16 23 13 4 (IA-Blend $p.glass1 $accentDk 0.4)
            for ($i = 0; $i -lt 12; $i += 2) { IA-Fill $g (17 + $i) 24 1 2 $p.glassHi }
            IA-Fill $g 28 25 2 2 $p.green
            IA-Fill $g 28 25 1 1 $p.whiteHi
        }
        'reader' {
            IA-Fill $g 0 0 32 10 $accentDk
            IA-HLine $g 0 31 0 $accentHi
            IA-HLine $g 0 31 9 $accent
            # Each reader shows the interaction it actually performs, not one
            # shared "card + tick" panel:
            #   card     desk scanner      VERIFY  card + scan line + tick/cross
            #   mrz      hotel reader      SCAN    MRZ strip + magnifier
            #   keypad   manual terminal   INPUT   card + numeric keypad
            #   queue    station verifier  QUEUE   card + tick + queue counter
            #   boarding airport check-in  CHECK   boarding pass + barcode
            $hdr = switch ($variant) {
                'mrz' { 'SCAN' }
                'keypad' { 'INPUT' }
                'queue' { 'QUEUE' }
                'boarding' { 'CHECK' }
                default { 'VERIFY' }
            }
            IA-PlotTextCentered $g $hdr 16 2 1 $Script:IA_White
            if ($variant -eq 'boarding') {
                # boarding pass: white stub with a notch, route row and barcode
                IA-Fill $g 3 11 20 11 $p.paperHi
                IA-Edge $g 3 11 20 11 $p.paperDk
                IA-Fill $g 3 11 20 3 $accent
                IA-PlotText $g 'VB' 5 12 1 $Script:IA_White
                IA-Fill $g 5 16 6 1 $p.paperLine
                IA-Fill $g 5 18 4 1 $p.paperLine
                IA-Fill $g 15 16 6 4 $p.glass3
                IA-Fill $g 17 11 1 2 $p.glass1
                for ($i = 0; $i -lt 12; $i += 2) { IA-Fill $g (4 + $i) 20 1 2 $p.ink }
                IA-PlotTextCentered $g 'READY' 16 24 1 $accentHi
            } elseif ($variant -eq 'mrz') {
                # passport page + MRZ + magnifier over the zone
                IA-Fill $g 3 11 16 11 $p.paperHi
                IA-Edge $g 3 11 16 11 $p.paperDk
                IA-Fill $g 4 12 14 5 $p.paper
                IA-Fill $g 5 13 4 3 $p.glass3
                IA-Fill $g 10 13 7 1 $p.paperLine
                IA-Fill $g 10 15 5 1 $p.paperLine
                for ($i = 0; $i -lt 2; $i++) { IA-Fill $g 4 (18 + $i * 2) 14 1 $p.ink }
                $pen = [System.Drawing.Pen]::new($accent, 2)
                $g.DrawEllipse($pen, 17, 14, 8, 8)
                $pen.Dispose()
                IA-Fill $g 24 21 3 1 $accent
                IA-Fill $g 25 22 3 1 $accentHi
            } elseif ($variant -eq 'keypad') {
                # card on the left, numeric keypad on the right (manual entry)
                IA-Fill $g 3 11 13 11 $p.paperHi
                IA-Edge $g 3 11 13 11 $p.paperDk
                IA-Fill $g 4 12 11 2 $accent
                IA-Fill $g 5 15 3 4 $p.glass3
                for ($i = 0; $i -lt 3; $i++) { IA-Fill $g 9 (15 + $i * 2) 5 1 $p.paperLine }
                for ($ky = 0; $ky -lt 3; $ky++) {
                    for ($kx = 0; $kx -lt 3; $kx++) {
                        IA-Fill $g (18 + $kx * 4) (12 + $ky * 4) 3 3 (IA-Blend $p.glass3 $accent 0.25)
                        IA-Fill $g (18 + $kx * 4) (12 + $ky * 4) 3 1 (IA-Blend $p.glass2 $accent 0.45)
                    }
                }
            } elseif ($variant -eq 'queue') {
                # card + tick + a queue counter strip along the bottom
                IA-Fill $g 3 11 16 9 $p.paperHi
                IA-Edge $g 3 11 16 9 $p.paperDk
                IA-Fill $g 5 13 5 5 $p.glass3
                for ($i = 0; $i -lt 3; $i++) { IA-Fill $g 11 (13 + $i * 2) (6 - $i) 1 $p.paperLine }
                $pen = [System.Drawing.Pen]::new($p.green, 2)
                $g.DrawLine($pen, 21, 13, 23, 16)
                $g.DrawLine($pen, 23, 16, 27, 10)
                $pen.Dispose()
                for ($i = 0; $i -lt 5; $i++) {
                    IA-Fill $g (4 + $i * 4) 22 3 4 (IA-Blend $p.glass3 $accent (0.15 * $i))
                }
                IA-Fill $g 26 22 2 4 $p.green
            } else {
                # card under the scan head
                IA-Fill $g 3 11 16 10 $p.paperHi
                IA-Edge $g 3 11 16 10 $p.paperDk
                IA-Fill $g 5 13 5 6 $p.glass3
                IA-Fill $g 11 13 6 1 $p.paperLine
                IA-Fill $g 11 15 5 1 $p.paperLine
                IA-Fill $g 11 17 6 1 $p.paperLine
                IA-Fill $g 3 19 16 1 $accent
                # scan line sweeping the card
                IA-Fill $g 3 16 16 1 (IA-Blend $p.ledCore $accent 0.35)
                IA-Fill $g 4 16 14 1 $p.ledCore
                # tick and cross
                $pen = [System.Drawing.Pen]::new($p.green, 2)
                $g.DrawLine($pen, 21, 14, 23, 17)
                $g.DrawLine($pen, 23, 17, 27, 11)
                $pen.Dispose()
                $pen = [System.Drawing.Pen]::new($p.red, 2)
                $g.DrawLine($pen, 22, 22, 26, 26)
                $g.DrawLine($pen, 26, 22, 22, 26)
                $pen.Dispose()
            }
            IA-Fill $g 28 25 2 2 $p.green
            IA-Fill $g 28 25 1 1 $p.whiteHi
        }
        'stamp' {
            IA-Fill $g 0 0 32 10 $accentDk
            IA-HLine $g 0 31 0 $accentHi
            IA-HLine $g 0 31 9 $accent
            if ($variant -eq 'history') {
                # stamp history: a dated log of past stamps (stamp_history UI)
                IA-PlotTextCentered $g 'HISTORY' 16 2 1 $Script:IA_White
                for ($i = 0; $i -lt 4; $i++) {
                    $yy = 12 + $i * 5
                    IA-Fill $g 3 $yy 4 4 (IA-Blend $accentDk $p.glass1 0.35)
                    IA-Edge $g 3 $yy 4 4 $accent
                    IA-Fill $g 9 $yy 10 1 $p.glassHi
                    IA-Fill $g 9 ($yy + 2) 7 1 $p.glass3
                    IA-Fill $g 21 $yy 8 2 (IA-Blend $accentDk $p.glass1 0.2)
                    IA-Fill $g 22 ($yy) (2 + $i) 1 $accentHi
                }
                IA-Fill $g 28 4 2 2 $p.green
                IA-Fill $g 28 4 1 1 $p.whiteHi
            } else {
                IA-PlotTextCentered $g 'STAMP' 16 2 1 $Script:IA_White
                # seal ring with a star centre
                $pen = [System.Drawing.Pen]::new($accent, 2)
                $g.DrawEllipse($pen, 9, 9, 14, 14)
                $pen.Dispose()
                $pen = [System.Drawing.Pen]::new($accentHi, 1)
                $g.DrawEllipse($pen, 11, 11, 10, 10)
                $pen.Dispose()
                for ($i = 0; $i -lt 3; $i++) {
                    $pen = [System.Drawing.Pen]::new($accentHi, 1)
                    $g.DrawLine($pen, 16, (11 + $i * 5), (14 - $i), (13 + $i * 5))
                    $g.DrawLine($pen, 16, (11 + $i * 5), (18 + $i), (13 + $i * 5))
                    $pen.Dispose()
                }
                # date line under the seal
                IA-Fill $g 6 25 20 4 (IA-Blend $p.glass1 $accentDk 0.45)
                IA-PlotTextCentered $g '03/14' 16 25 1 $accentHi
                IA-Fill $g 28 8 2 2 $p.amber
                IA-Fill $g 28 8 1 1 $p.whiteHi
            }
        }
        'print' {
            # print preview: a sheet coming out of the printer + a progress bar
            IA-Fill $g 0 0 32 10 $accentDk
            IA-HLine $g 0 31 0 $accentHi
            IA-HLine $g 0 31 9 $accent
            IA-PlotTextCentered $g 'PRINT' 16 2 1 $Script:IA_White
            IA-Fill $g 4 12 24 12 $p.paperHi
            IA-Edge $g 4 12 24 12 $p.paperDk
            IA-Fill $g 7 15 18 1 $p.paperLine
            IA-Fill $g 7 18 14 1 $p.paperLine
            IA-Fill $g 7 21 16 1 $p.paperLine
            IA-Fill $g 3 25 26 4 (IA-Blend $p.glass1 $accentDk 0.4)
            IA-Fill $g 4 26 24 1 $p.glass3
            IA-Fill $g 4 26 16 1 $accentHi
            IA-Fill $g 4 26 16 1 $accent
            IA-Fill $g 28 4 2 2 $p.amber
            IA-Fill $g 28 4 1 1 $p.whiteHi
        }
        'terminal' {
            IA-Fill $g 0 0 32 10 $accentDk
            IA-HLine $g 0 31 0 $accentHi
            IA-HLine $g 0 31 9 $accent
            # Service terminals share the roster grammar but not the content:
            #   roster  (default)   ROSTER  rows + IN/OUT badges
            #   lost                LOST    lost-item rows + a magnifier
            #   renew               RENEW   expiry calendar grid
            #   black               BLACK   rows struck out with red crosses
            $hdr = switch ($variant) {
                'lost' { 'LOST' }
                'renew' { 'RENEW' }
                'black' { 'BLACK' }
                default { 'ROSTER' }
            }
            IA-PlotTextCentered $g $hdr 16 2 1 $Script:IA_White
            if ($variant -eq 'renew') {
                # expiry calendar: 4x3 day grid, one column highlighted
                for ($cy = 0; $cy -lt 3; $cy++) {
                    for ($cx = 0; $cx -lt 4; $cx++) {
                        $on = ($cx -eq 2 -and $cy -eq 1)
                        $col = if ($on) { $accent } else { (IA-Blend $p.glass3 $accent 0.15) }
                        IA-Fill $g (3 + $cx * 7) (12 + $cy * 4) 5 3 $col
                    }
                }
                IA-Fill $g 2 26 28 5 (IA-Blend $p.glass1 $accentDk 0.35)
                IA-PlotTextCentered $g 'EXP27' 16 27 1 $accentHi
            } elseif ($variant -eq 'lost') {
                # lost-property rows with a magnifier over them
                for ($i = 0; $i -lt 4; $i++) {
                    $y = 12 + $i * 3
                    IA-Fill $g 3 $y 2 2 (IA-Blend $p.glass3 $accent 0.3)
                    IA-Fill $g 7 $y (12 - ($i % 3)) 1 $p.glassHi
                }
                $pen = [System.Drawing.Pen]::new($accent, 2)
                $g.DrawEllipse($pen, 18, 16, 8, 8)
                $pen.Dispose()
                IA-Fill $g 25 23 4 1 $accent
                IA-Fill $g 25 24 3 1 $accentHi
                IA-Fill $g 27 26 2 2 $p.amber
            } elseif ($variant -eq 'black') {
                # blacklist: rows, each struck through with a red cross
                for ($i = 0; $i -lt 4; $i++) {
                    $y = 12 + $i * 3
                    IA-Fill $g 3 $y (14 - ($i % 3)) 1 (IA-Blend $p.red $p.glass1 0.45)
                    IA-Fill $g 3 ($y + 1) (9 + ($i % 4)) 1 $p.glass3
                    IA-Fill $g 21 $y 2 2 $p.red
                }
                IA-Fill $g 18 11 13 13 $p.glassEdge
                IA-Edge $g 18 11 13 13 $p.red
                $pen = [System.Drawing.Pen]::new($p.red, 2)
                $g.DrawLine($pen, 20, 13, 28, 22)
                $g.DrawLine($pen, 28, 13, 20, 22)
                $pen.Dispose()
            } else {
                # roster rows with per-row status dots
                for ($i = 0; $i -lt 4; $i++) {
                    $y = 9 + $i * 4
                    IA-Fill $g 2 $y 2 2 (IA-Blend $p.glass3 $accent 0.3)
                    IA-Fill $g 6 $y (14 - ($i % 3)) 1 $p.glassHi
                    IA-Fill $g 6 ($y + 1) (8 + ($i % 4)) 1 $p.glass3
                    $c = if ($i % 3 -eq 0) { $p.red } elseif ($i % 3 -eq 1) { $p.green } else { $p.amber }
                    IA-Fill $g 23 $y 2 2 $c
                }
                # IN / OUT badges
                IA-Fill $g 2 26 13 5 (IA-Blend $p.greenDk $p.glass1 0.4)
                IA-Edge $g 2 26 13 5 $p.green
                IA-PlotText $g 'IN' 6 27 1 $p.green
                IA-Fill $g 17 26 13 5 (IA-Blend $p.redDk $p.glass1 0.4)
                IA-Edge $g 17 26 13 5 $p.red
                IA-PlotText $g 'OUT' 20 27 1 $p.red
            }
        }
        default {
            # 'none' / unknown: keep a neutral dark panel (never used by brushes)
            IA-Fill $g 2 2 28 28 $p.glass2
            IA-Edge $g 2 2 28 28 $p.glassEdge
        }
    }

    $fixes = IA-Save $bmp $g (Join-Path $Script:IA_OutDir "$name.png")
    return $fixes
}

# ---------------------------------------------------------------------------
#  ITEM MATERIAL TILES (32x32)
#
#  The dedicated 3D item models deliberately do NOT reuse textures\block. Those
#  chassis textures are per-device and wildly different in value (id_issuer_body
#  averages rgb 50,77,103 = dark navy, desk_scanner_body 205,214,217 = near
#  white, entry_stamp_body 167,85,28 = orange-brown), so an item icon built from
#  them would be an incoherent set and the issuers would sit on a dark body
#  instead of the matte white shell the upgrade calls for.
#
#  These tiles are the ITEM family material set: one palette, one top-left key
#  light, opaque, binary alpha. Every 3D item model draws from exactly these, so
#  the whole device line reads as one product family no matter what happens to
#  the block art.
# ---------------------------------------------------------------------------
function New-IaMaterialTile([string]$name, [string]$kind, [string]$tintHex) {
    $p = IA-P
    $bmp, $g = IA-NewBitmap 32 32
    $tint = [System.Drawing.ColorTranslator]::FromHtml($tintHex)
    switch ($kind) {
        'shell' {
            # matte white shell: soft top-down ramp + faint grain, lit left
            for ($y = 0; $y -lt 32; $y++) {
                $t = $y / 31.0
                $row = if ($t -lt 0.16) { IA-Shade $tint 0.10 }
                elseif ($t -lt 0.78) { IA-Blend (IA-Shade $tint 0.06) $tint (($t - 0.16) / 0.62) }
                else { IA-Blend $tint (IA-Shade $tint -0.13) (($t - 0.78) / 0.22) }
                IA-HLine $g 0 31 $y $row
            }
            IA-VLine $g 0 0 31 (IA-Shade $tint 0.16)
            IA-VLine $g 31 0 31 (IA-Shade $tint -0.16)
            IA-Grain $g 1 1 30 30 (IA-Shade $tint 0.12) (IA-Shade $tint -0.08) 401 23
        }
        'trim' {
            # brushed metal: horizontal streaks, bright top edge, dark bottom
            IA-Brushed $g 0 0 32 32 $p.metalHi $p.metal $p.metalMid 907
            IA-HLine $g 0 31 0 $p.metalHi
            IA-HLine $g 0 31 31 $p.metalDeep
            IA-VLine $g 0 0 31 (IA-Shade $p.metalHi 0.2)
            IA-VLine $g 31 0 31 $p.metalDk
            for ($i = 4; $i -lt 32; $i += 8) { IA-HLine $g 2 29 $i $p.metalDk }
        }
        'glass' {
            # deep-blue glass: vertical gradient, specular sheen, faint scanlines
            IA-Glass $g 0 0 32 32 17
            IA-Fill $g 2 2 12 2 (IA-Shade $p.glassHi 0.30)
            IA-Fill $g 4 4 6 1 (IA-Shade $p.glassHi 0.12)
            IA-Fill $g 2 6 2 6 (IA-Shade $p.glassHi 0.06)
        }
        'led' {
            # cyan LED strip: bright core row, halo above/below
            IA-Fill $g 0 0 32 32 $p.ledDim
            IA-Fill $g 0 4 32 24 $p.ledHalo
            IA-Fill $g 0 12 32 8 $p.ledCore
            IA-Fill $g 0 14 32 3 (IA-Shade $p.ledCore 0.55)
            IA-HLine $g 0 31 12 $Script:IA_White
        }
        'paper' {
            # document stock: cream, faint horizontal rules
            IA-Fill $g 0 0 32 32 $p.paper
            IA-HLine $g 0 31 0 $p.paperHi
            IA-HLine $g 0 31 31 $p.paperMid
            IA-Grain $g 1 1 30 30 $p.paperHi $p.paperDk 613 19
            for ($i = 5; $i -lt 30; $i += 4) { IA-HLine $g 3 28 $i (IA-Blend $p.paperLine $p.paper 0.45) }
        }
        'panel' {
            # dark inset panel for vents / slots: fine vertical grooves
            IA-Fill $g 0 0 32 32 (IA-Shade $p.metalDeep -0.35)
            for ($x = 1; $x -lt 32; $x += 4) {
                IA-VLine $g $x 0 31 (IA-Shade $p.metalDeep 0.05)
                IA-VLine $g ($x + 1) 0 31 (IA-Shade $p.metalDeep -0.55)
            }
            IA-HLine $g 0 31 0 (IA-Shade $p.metalDeep 0.15)
            IA-HLine $g 0 31 31 (IA-Shade $p.metalDeep -0.75)
        }
        'accent' {
            # family accent plate: saturated, lit top, dark bottom
            for ($y = 0; $y -lt 32; $y++) {
                $t = $y / 31.0
                $row = if ($t -lt 0.14) { IA-Shade $tint 0.34 }
                elseif ($t -lt 0.72) { IA-Blend (IA-Shade $tint 0.16) $tint (($t - 0.14) / 0.58) }
                else { IA-Blend $tint (IA-Shade $tint -0.35) (($t - 0.72) / 0.28) }
                IA-HLine $g 0 31 $y $row
            }
            IA-VLine $g 0 0 31 (IA-Shade $tint 0.42)
            IA-VLine $g 31 0 31 (IA-Shade $tint -0.40)
            IA-Grain $g 1 1 30 30 (IA-Shade $tint 0.22) (IA-Shade $tint -0.22) 733 27
        }
        default {
            IA-Fill $g 0 0 32 32 $tint
        }
    }
    $fixes = IA-Save $bmp $g (Join-Path $Script:IA_OutDir "$name.png")
    return $fixes
}