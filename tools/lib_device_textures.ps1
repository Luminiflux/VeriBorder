# VeriBorder - device texture library (18 machines, 12 textures each)
# Dot-sourced by tools/generate_assets.ps1. Requires the helpers defined there:
#   C, Blend, New-Bitmap, Save-Bitmap, Font, Brush, Pen, Fill-Rect, Draw-Line,
#   Draw-Text, Draw-TextCentered, $blockTex

$script:F4 = Font 4
$script:F5 = Font 5
$script:F6 = Font 6
$script:F8 = Font 8
$script:WHITE = C 242 249 252

function Col([string]$hex) { return [System.Drawing.ColorTranslator]::FromHtml($hex) }

function Screw($g, [int]$x, [int]$y, $base) {
    Fill-Rect $g $x $y 2 2 (Blend $base (C 20 24 28) 0.45)
    Fill-Rect $g $x $y 1 1 (Blend $base (C 255 255 255) 0.8)
}

function VentPanel($g, [int]$x, [int]$y, [int]$w, [int]$h, $dark) {
    Fill-Rect $g $x $y $w $h (Blend $dark (C 0 0 0) 0.2)
    $slots = [int](($h - 2) / 2)
    for ($i = 0; $i -lt $slots; $i++) {
        Fill-Rect $g ($x + 1) ($y + 1 + $i * 2) ($w - 2) 1 (Blend $dark (C 255 255 255) 0.22)
        Fill-Rect $g ($x + 1) ($y + 2 + $i * 2) ($w - 2) 1 (Blend $dark (C 0 0 0) 0.45)
    }
}

function Leds($g, [int]$x, [int]$y, [int]$n, $a, $b, $c) {
    $cols = @($a, $b, $c)
    for ($i = 0; $i -lt $n; $i++) {
        $col = $cols[$i % 3]
        Fill-Rect $g ($x + $i * 3) $y 2 2 (Blend $col (C 0 0 0) 0.35)
        Fill-Rect $g ($x + $i * 3) ($y + 1) 2 1 $col
        Fill-Rect $g ($x + $i * 3) $y 1 1 (Blend $col (C 255 255 255) 0.65)
    }
}

# ------------------------------------------------------------------ panels
function New-BodyTexture([string]$path, $d) {
    $base = Col $d.base
    $base2 = Col $d.base2
    $trim = Col $d.trim
    $accent = Col $d.accent
    $v = [int]$d.variant
    $bmp, $g = New-Bitmap 32 32
    for ($i = 0; $i -lt 16; $i++) {
        Fill-Rect $g 0 ($i * 2) 32 2 (Blend $base $base2 ($i / 26.0))
    }
    Fill-Rect $g 0 0 32 1 (Blend $base (C 255 255 255) 0.45)
    Fill-Rect $g 0 31 32 1 (Blend $base2 (C 32 38 44) 0.35)
    Fill-Rect $g 0 0 1 32 (Blend $base2 (C 32 38 44) 0.22)
    Fill-Rect $g 31 0 1 32 (Blend $base2 (C 32 38 44) 0.22)
    $seam = 7 + $v * 3
    Fill-Rect $g 2 $seam 28 1 (Blend $base2 (C 24 30 36) 0.28)
    Fill-Rect $g 2 ($seam + 1) 28 1 (Blend $base (C 255 255 255) 0.55)
    Fill-Rect $g 2 3 9 2 $accent
    Fill-Rect $g 11 3 2 2 (C 96 232 156)
    Fill-Rect $g 3 26 12 3 (Blend $base2 (C 255 255 255) 0.35)
    Fill-Rect $g 4 27 7 1 (Blend $trim (C 255 255 255) 0.25)
    Screw $g 27 ($seam + 4) $base
    Screw $g 4 ($seam + 4) $base
    if ($v -le 1) { VentPanel $g 21 21 8 7 $trim } else { VentPanel $g 3 20 8 7 $trim }
    Fill-Rect $g 2 30 28 1 (Blend $base2 (C 0 0 0) 0.18)
    Save-Bitmap $bmp $g $path
}

function New-Body2Texture([string]$path, $d) {
    $base2 = Col $d.base2
    $accent = Col $d.accent
    $trim = Col $d.trim
    $v = [int]$d.variant
    $bmp, $g = New-Bitmap 32 32
    for ($i = 0; $i -lt 16; $i++) {
        Fill-Rect $g 0 ($i * 2) 32 2 (Blend (Blend $base2 (C 255 255 255) 0.25) $base2 ($i / 20.0))
    }
    Fill-Rect $g 0 0 32 1 (Blend $base2 (C 255 255 255) 0.7)
    Fill-Rect $g 0 31 32 1 (Blend $base2 (C 30 36 42) 0.3)
    Fill-Rect $g 0 0 1 32 (Blend $base2 (C 30 36 42) 0.15)
    Fill-Rect $g 31 0 1 32 (Blend $base2 (C 30 36 42) 0.15)
    $ix = 3 + ($v % 2) * 5
    Fill-Rect $g $ix 6 (32 - $ix * 2) 1 (Blend $base2 (C 30 36 42) 0.25)
    Fill-Rect $g $ix 24 (32 - $ix * 2) 1 (Blend $base2 (C 30 36 42) 0.25)
    for ($i = 0; $i -lt 6; $i++) {
        Fill-Rect $g ($ix + 2 + $i * 3) 10 1 12 (Blend $base2 (C 255 255 255) 0.32)
        Fill-Rect $g ($ix + 3 + $i * 3) 10 1 12 (Blend $base2 (C 40 46 52) 0.14)
    }
    Fill-Rect $g 3 28 10 2 $accent
    Fill-Rect $g 24 4 5 2 (Blend $accent (C 255 255 255) 0.35)
    Screw $g 27 27 $base2
    Save-Bitmap $bmp $g $path
}

function New-TrimTexture([string]$path, $d) {
    $trim = Col $d.trim
    $accent = Col $d.accent
    $bmp, $g = New-Bitmap 32 32
    for ($i = 0; $i -lt 16; $i++) {
        Fill-Rect $g 0 ($i * 2) 32 2 (Blend (Blend $trim (C 0 0 0) 0.25) (Blend $trim (C 255 255 255) 0.12) ($i / 22.0))
    }
    Fill-Rect $g 0 0 32 1 (Blend $trim (C 255 255 255) 0.3)
    Fill-Rect $g 0 31 32 1 (C 22 26 30)
    for ($y = 3; $y -lt 30; $y += 3) {
        Fill-Rect $g 1 $y 30 1 (Blend $trim (C 0 0 0) 0.38)
        Fill-Rect $g 1 ($y + 1) 30 1 (Blend $trim (C 255 255 255) 0.13)
    }
    Fill-Rect $g 0 0 3 32 (Blend $accent (C 0 0 0) 0.15)
    Fill-Rect $g 3 0 1 32 (Blend $accent (C 255 255 255) 0.4)
    Screw $g 27 4 $trim
    Screw $g 27 26 $trim
    Save-Bitmap $bmp $g $path
}

function New-TopTexture([string]$path, $d) {
    $base = Col $d.base
    $base2 = Col $d.base2
    $trim = Col $d.trim
    $accent = Col $d.accent
    $v = [int]$d.variant
    $bmp, $g = New-Bitmap 32 32
    for ($i = 0; $i -lt 16; $i++) {
        Fill-Rect $g 0 ($i * 2) 32 2 (Blend (Blend $base (C 255 255 255) 0.2) $base2 ($i / 24.0))
    }
    Fill-Rect $g 0 0 32 1 (Blend $base (C 255 255 255) 0.55)
    Fill-Rect $g 0 31 32 1 (Blend $base2 (C 30 36 42) 0.3)
    Fill-Rect $g 0 0 1 32 (Blend $base2 (C 30 36 42) 0.2)
    Fill-Rect $g 31 0 1 32 (Blend $base2 (C 30 36 42) 0.2)
    if ($v -eq 2) {
        for ($i = 0; $i -lt 7; $i++) {
            Fill-Rect $g 4 (5 + $i * 3) 24 1 (Blend $trim (C 0 0 0) 0.35)
        }
    } else {
        for ($i = 0; $i -lt 6; $i++) {
            Fill-Rect $g 4 (6 + $i * 3) 24 1 (Blend $trim (C 0 0 0) 0.3)
            Fill-Rect $g 4 (7 + $i * 3) 24 1 (Blend $base (C 255 255 255) 0.25)
        }
    }
    Fill-Rect $g 0 28 32 2 (Blend $accent (C 0 0 0) 0.1)
    Fill-Rect $g 0 28 32 1 (Blend $accent (C 255 255 255) 0.45)
    Screw $g 3 3 $base
    Screw $g 27 3 $base
    Save-Bitmap $bmp $g $path
}

function New-AccentTexture([string]$path, $d) {
    $accent = Col $d.accent
    $bg = Col $d.bg
    $bmp, $g = New-Bitmap 32 32
    for ($i = 0; $i -lt 16; $i++) {
        Fill-Rect $g 0 ($i * 2) 32 2 (Blend (Blend $accent (C 255 255 255) 0.28) (Blend $accent (C 0 0 0) 0.2) ($i / 18.0))
    }
    Fill-Rect $g 0 0 32 1 (Blend $accent (C 255 255 255) 0.6)
    Fill-Rect $g 0 31 32 1 (Blend $accent (C 0 0 0) 0.45)
    for ($i = 0; $i -lt 3; $i++) {
        $x = 5 + $i * 6
        Fill-Rect $g $x 12 3 6 (Blend $accent (C 0 0 0) 0.45)
        Fill-Rect $g $x 15 3 3 (Blend $accent (C 255 255 255) 0.55)
        Fill-Rect $g $x 15 1 1 (C 255 255 255)
    }
    Fill-Rect $g 2 25 20 2 (Blend $accent (C 0 0 0) 0.3)
    Fill-Rect $g 2 26 12 1 (Blend $accent (C 255 255 255) 0.4)
    Fill-Rect $g 27 26 2 2 (C 120 240 170)
    Fill-Rect $g 27 26 1 1 (C 230 255 240)
    # embossed device code strip
    Fill-Rect $g 2 4 8 2 (Blend $accent (C 0 0 0) 0.25)
    Save-Bitmap $bmp $g $path
}

function New-DetailTexture([string]$path, $d) {
    $base2 = Col $d.base2
    $trim = Col $d.trim
    $accent = Col $d.accent
    $bmp, $g = New-Bitmap 32 32
    Fill-Rect $g 0 0 32 32 (Blend $base2 (C 30 36 42) 0.12)
    Fill-Rect $g 0 0 32 1 (Blend $base2 (C 255 255 255) 0.55)
    Fill-Rect $g 0 31 32 1 (Blend $trim (C 0 0 0) 0.35)
    Fill-Rect $g 0 0 1 32 (Blend $trim (C 0 0 0) 0.25)
    Fill-Rect $g 31 0 1 32 (Blend $trim (C 0 0 0) 0.25)
    # three physical buttons with shadow
    $btnCols = @($accent, (C 74 206 138), (C 236 242 246))
    for ($i = 0; $i -lt 3; $i++) {
        $x = 4 + $i * 8
        Fill-Rect $g $x 9 6 6 (Blend $trim (C 0 0 0) 0.4)
        Fill-Rect $g $x 8 6 6 $btnCols[$i]
        Fill-Rect $g $x 8 6 1 (Blend $btnCols[$i] (C 255 255 255) 0.45)
        Fill-Rect $g $x 13 6 1 (Blend $btnCols[$i] (C 0 0 0) 0.3)
    }
    Leds $g 4 19 7 $accent (C 96 232 156) (C 236 242 246)
    Fill-Rect $g 4 24 24 1 (Blend $trim (C 255 255 255) 0.2)
    Fill-Rect $g 4 26 13 1 (Blend $trim (C 255 255 255) 0.35)
    Fill-Rect $g 20 26 8 1 (Blend $trim (C 255 255 255) 0.18)
    Screw $g 27 3 $base2
    Save-Bitmap $bmp $g $path
}

function New-LedTexture([string]$path, $d) {
    $accent = Col $d.accent
    $trim = Col $d.trim
    $bg = Col $d.bg
    $bmp, $g = New-Bitmap 32 32
    Fill-Rect $g 0 0 32 32 (Blend $bg (C 10 22 26) 0.32)
    Fill-Rect $g 0 0 32 1 (Blend $trim (C 255 255 255) 0.22)
    Fill-Rect $g 0 31 32 1 (Blend $trim (C 0 0 0) 0.45)
    for ($i = 0; $i -lt 6; $i++) {
        $x = 1 + $i * 5
        Fill-Rect $g $x 5 4 22 (Blend $accent (C 0 0 0) 0.58)
        Fill-Rect $g ($x + 1) 8 2 16 (Blend $accent (C 255 255 255) 0.32)
        Fill-Rect $g ($x + 1) 13 2 6 (C 255 255 255)
    }
    Fill-Rect $g 0 2 32 1 (Blend $accent (C 0 0 0) 0.25)
    Save-Bitmap $bmp $g $path
}

function New-GlassTexture([string]$path, $d) {
    $accent = Col $d.accent
    $tint = Blend $accent (C 235 250 255) 0.55
    $bmp, $g = New-Bitmap 32 32
    $g.Clear((C $tint.R $tint.G $tint.B 58))
    Fill-Rect $g 0 0 32 2 (C 226 236 242 235)
    Fill-Rect $g 0 30 32 2 (C 226 236 242 235)
    Fill-Rect $g 0 0 2 32 (C 226 236 242 235)
    Fill-Rect $g 30 0 2 32 (C 226 236 242 235)
    Fill-Rect $g 3 3 26 1 (C 255 255 255 110)
    Fill-Rect $g 3 3 1 26 (C 255 255 255 90)
    for ($i = 0; $i -lt 4; $i++) {
        Draw-Line $g (5 + $i * 7) 27 (16 + $i * 7) 6 (C 255 255 255 55) 1
    }
    Fill-Rect $g 12 10 8 6 (C 255 255 255 26)
    Save-Bitmap $bmp $g $path
}

function New-PaperTexture([string]$path, $d) {
    $accent = Col $d.accent
    $bmp, $g = New-Bitmap 32 32
    Fill-Rect $g 0 0 32 32 (C 246 244 236)
    Fill-Rect $g 0 0 32 1 (C 255 255 255)
    Fill-Rect $g 0 31 32 1 (C 196 194 186)
    Fill-Rect $g 0 0 1 32 (C 214 212 204)
    Fill-Rect $g 2 2 28 4 $accent
    Fill-Rect $g 2 2 28 1 (Blend $accent (C 255 255 255) 0.4)
    for ($i = 0; $i -lt 5; $i++) {
        Fill-Rect $g 3 (9 + $i * 3) (20 - $i) 1 (C 120 124 128)
    }
    for ($i = 0; $i -lt 9; $i++) {
        Fill-Rect $g (3 + $i * 3) 25 1 4 (C 40 44 48)
        Fill-Rect $g (4 + $i * 3) 25 1 4 (C 40 44 48)
    }
    $p = Pen (C 176 132 110) 1
    $g.DrawEllipse($p, 21, 6, 7, 7)
    $p.Dispose()
    Save-Bitmap $bmp $g $path
}

function New-WarningTexture([string]$path, $d) {
    $accent = Col $d.accent
    $trim = Col $d.trim
    $bmp, $g = New-Bitmap 32 32
    Fill-Rect $g 0 0 32 32 (Blend $trim (C 20 20 22) 0.55)
    for ($i = -32; $i -lt 64; $i += 8) {
        $pen = Pen (Blend $accent (C 0 0 0) 0.05) 4
        $g.DrawLine($pen, $i, 32, $i + 32, 0)
        $pen.Dispose()
    }
    Fill-Rect $g 0 0 32 1 (Blend $accent (C 255 255 255) 0.3)
    Fill-Rect $g 0 31 32 1 (C 16 16 18)
    Fill-Rect $g 0 0 1 32 (C 16 16 18)
    Fill-Rect $g 31 0 1 32 (C 16 16 18)
    Save-Bitmap $bmp $g $path
}

# --------------------------------------------------------------- screen kit
function Sc-Frame($g, [int]$w, [int]$h, $d) {
    $bg = Col $d.bg
    $fg = Col $d.fg
    $accent = Col $d.accent
    Fill-Rect $g 0 0 64 64 $bg
    Fill-Rect $g 0 0 $w $h $bg
    for ($y = 2; $y -lt $h; $y += 3) {
        Fill-Rect $g 1 $y ($w - 2) 1 (Blend $bg (C 255 255 255) 0.05)
    }
    Fill-Rect $g 0 0 $w 1 (Blend $accent (C 255 255 255) 0.55)
    Fill-Rect $g 0 ($h - 1) $w 1 (Blend $accent (C 0 0 0) 0.45)
    Fill-Rect $g 0 0 1 $h (Blend $accent (C 0 0 0) 0.3)
    Fill-Rect $g ($w - 1) 0 1 $h (Blend $accent (C 0 0 0) 0.3)
}

function Sc-Header($g, [int]$w, $d, [string]$text) {
    $accent = Col $d.accent
    $bg = Col $d.bg
    $white = $script:WHITE
    Fill-Rect $g 0 0 $w 6 $accent
    Fill-Rect $g 0 6 $w 1 (Blend $accent $bg 0.5)
    Fill-Rect $g 0 0 $w 1 (Blend $accent $white 0.5)
    Draw-Text $g $text $script:F5 $bg 2 1
}

function Sc-Bars($g, [int]$x, [int]$y, [int]$w, [int]$n, [int]$step, $col) {
    for ($i = 0; $i -lt $n; $i++) {
        Fill-Rect $g $x ($y + $i * $step) $w 1 $col
    }
}

function Sc-Photo($g, [int]$x, [int]$y, [int]$w, [int]$h, $fg, $bg) {
    Fill-Rect $g $x $y $w $h (Blend $fg $bg 0.62)
    $b = Brush (Blend $fg $bg 0.3)
    $g.FillEllipse($b, ($x + [int]($w / 2) - 2), ($y + 2), 5, 5)
    $g.FillRectangle($b, ($x + 2), ($y + [int]($h / 2) + 1), ($w - 4), [int]($h / 2) - 2)
    $b.Dispose()
}

function Sc-Mrz($g, [int]$x, [int]$y, [int]$w, [int]$rows, $col, $bg, [int]$seed) {
    Fill-Rect $g $x $y $w ($rows * 3 + 1) (Blend $bg (C 255 255 255) 0.1)
    $s = $seed
    for ($r = 0; $r -lt $rows; $r++) {
        $cx = $x + 2
        while ($cx -lt ($x + $w - 2)) {
            $s = ($s * 1103515245 + 12345) -band 0x7fffffff
            $bw = 1 + ($s % 3)
            if (($s % 5) -ne 0) { Fill-Rect $g $cx ($y + 1 + $r * 3) $bw 2 $col }
            $cx += $bw + 1
        }
    }
}

function Sc-Seal($g, [int]$cx, [int]$cy, [int]$r, $col, $bg, [string]$text, [int]$fs) {
    $p = Pen $col 2
    $g.DrawEllipse($p, ($cx - $r), ($cy - $r), ($r * 2), ($r * 2))
    $p.Dispose()
    $p = Pen (Blend $col $bg 0.35) 1
    $g.DrawEllipse($p, ($cx - $r + 3), ($cy - $r + 3), (($r - 3) * 2), (($r - 3) * 2))
    $g.DrawLine($p, ($cx - $r + 3), $cy, ($cx + $r - 3), $cy)
    $p.Dispose()
    Draw-TextCentered $g $text (Font $fs) $col ($cx - $r) ($cy - 4) ($r * 2)
}

function Sc-Check($g, [int]$x, [int]$y, [int]$s, $col) {
    $p = Pen $col ([Math]::Max(1, [int]($s / 4)))
    $g.DrawLine($p, $x, ($y + [int]($s * 0.55)), ($x + [int]($s * 0.38)), ($y + $s))
    $g.DrawLine($p, ($x + [int]($s * 0.38)), ($y + $s), ($x + $s), $y)
    $p.Dispose()
}

function Sc-Cross($g, [int]$x, [int]$y, [int]$s, $col) {
    $p = Pen $col ([Math]::Max(1, [int]($s / 5)))
    $g.DrawLine($p, $x, $y, ($x + $s), ($y + $s))
    $g.DrawLine($p, ($x + $s), $y, $x, ($y + $s))
    $p.Dispose()
}

function Sc-QR($g, [int]$x, [int]$y, [int]$s, $col, $bg, [int]$seed) {
    Fill-Rect $g $x $y $s $s (Blend $bg (C 255 255 255) 0.1)
    $cell = [Math]::Max(1, [int]($s / 7))
    $st = $seed
    for ($iy = 0; $iy -lt 7; $iy++) {
        for ($ix = 0; $ix -lt 7; $ix++) {
            $st = ($st * 1103515245 + 12345) -band 0x7fffffff
            $on = (($st -shr 6) % 3) -ne 0
            if (($ix -lt 3 -and $iy -lt 3) -or ($ix -gt 3 -and $iy -lt 3) -or ($ix -lt 3 -and $iy -gt 3)) { $on = (($ix + $iy) % 2 -eq 0) }
            if ($on) { Fill-Rect $g ($x + $ix * $cell) ($y + $iy * $cell) $cell $cell $col }
        }
    }
}

function Sc-Barcode($g, [int]$x, [int]$y, [int]$w, [int]$h, $col, [int]$seed) {
    $s = $seed
    $cx = $x
    while ($cx -lt ($x + $w)) {
        $s = ($s * 1103515245 + 12345) -band 0x7fffffff
        $bw = 1 + ($s % 2)
        if (($s % 4) -ne 0) { Fill-Rect $g $cx $y $bw $h $col }
        $cx += $bw + 1
    }
}

function Sc-Table($g, [int]$x, [int]$y, [int]$w, [int]$rows, [int]$step, $headCol, $lineCol, $bg) {
    Fill-Rect $g $x $y $w 3 (Blend $headCol $bg 0.55)
    for ($i = 0; $i -lt $rows; $i++) {
        Fill-Rect $g $x ($y + 4 + $i * $step) $w 1 $lineCol
    }
}

# --------------------------------------------------------------- screen 1/2
function New-ScreenTexture([string]$path, [int]$w, [int]$h, $d, [string]$style) {
    $bg = Col $d.bg
    $fg = Col $d.fg
    $ac = Col $d.accent
    $tr = Col $d.trim
    $dim = Blend $fg $bg 0.55
    $pale = Blend $fg $bg 0.25
    $bmp, $g = New-Bitmap 64 64
    Sc-Frame $g $w $h $d
    switch ($style) {
        'idIssue' {
            Sc-Header $g $w $d 'ID ISSUE'
            Sc-Photo $g 3 9 14 19 $fg $bg
            for ($i = 0; $i -lt 4; $i++) {
                $yy = 9 + $i * 4
                Fill-Rect $g 21 $yy 9 1 (Blend $fg $bg 0.45)
                Fill-Rect $g 32 $yy ($w - 36) 2 $dim
            }
            Fill-Rect $g 3 31 ($w - 6) 7 (Blend $bg (C 255 255 255) 0.08)
            Sc-Mrz $g 5 32 ($w - 12) 2 $pale $bg 77
            Fill-Rect $g ($w - 9) 2 2 2 (C 96 232 156)
            Fill-Rect $g ($w - 9) 2 1 1 (C 220 255 235)
        }
        'passport' {
            Sc-Header $g $w $d 'PASSPORT'
            Fill-Rect $g 3 9 26 23 (Blend $fg $bg 0.78)
            Fill-Rect $g 3 9 26 1 (Blend $fg $bg 0.35)
            Sc-Photo $g 5 12 10 12 $bg (Blend $fg $bg 0.78)
            for ($i = 0; $i -lt 4; $i++) { Fill-Rect $g 5 (26 + $i) 22 1 (Blend $fg $bg 0.4) }
            $p = Pen $ac 1
            $g.DrawEllipse($p, 38, 12, 13, 13)
            $g.DrawEllipse($p, 42, 12, 5, 13)
            $g.DrawLine($p, 38, 18, 51, 18)
            $p.Dispose()
            for ($i = 0; $i -lt 3; $i++) {
                Fill-Rect $g 34 (30 + $i * 3) 26 1 (Blend $fg $bg 0.5)
            }
            Sc-Mrz $g 3 33 ($w - 6) 2 $pale $bg 191
        }
        'permit' {
            Sc-Header $g $w $d 'PERMIT'
            for ($i = 0; $i -lt 4; $i++) {
                Fill-Rect $g 4 (10 + $i * 5) 8 1 (Blend $fg $bg 0.4)
                Fill-Rect $g 14 (10 + $i * 5) 26 2 $dim
            }
            Fill-Rect $g 45 9 15 18 (Blend $bg (C 255 255 255) 0.1)
            for ($i = 1; $i -lt 5; $i++) {
                $p = Pen (Blend $ac $bg 0.35) 1
                $g.DrawEllipse($p, (45 - $i), (9 + $i), (14 + $i * 2), (16 + $i * 2))
                $p.Dispose()
            }
            Fill-Rect $g 3 31 24 6 (Blend $ac $bg 0.55)
            Fill-Rect $g 5 33 20 2 $ac
            Fill-Rect $g 31 32 14 5 (Blend $ac $bg 0.25)
            Draw-Text $g 'VISA' $script:F5 $fg 34 32
        }
        'visa' {
            Fill-Rect $g 0 0 $w 5 $ac
            Fill-Rect $g 0 5 $w 1 (Blend $ac $bg 0.4)
            Draw-Text $g 'VISA' $script:F8 $bg 4 6
            for ($i = 0; $i -lt 5; $i++) {
                $p = Pen (Blend $ac $bg 0.72) 1
                $g.DrawEllipse($p, (2 + $i * 2), (8 + $i * 3), ($w - 4 - $i * 4), 10 + $i * 2)
                $p.Dispose()
            }
            Fill-Rect $g 4 18 12 8 (Blend $ac $bg 0.35)
            Fill-Rect $g 5 19 10 1 (Blend $fg $bg 0.3)
            for ($i = 0; $i -lt 4; $i++) { Fill-Rect $g 4 (28 + $i * 4) 30 1 $dim }
            Sc-QR $g 38 27 15 $pale $bg 23
            Draw-Text $g 'CHN' $script:F5 $pale 4 45
            Sc-Mrz $g 2 52 ($w - 4) 2 $pale $bg 411
            Fill-Rect $g 0 61 $w 1 (Blend $ac $bg 0.4)
            Fill-Rect $g 3 4 2 2 (C 96 232 156)
        }
        'endorse' {
            Sc-Header $g $w $d 'ENDORSE'
            Fill-Rect $g 3 8 23 21 (Blend $fg $bg 0.75)
            Fill-Rect $g 3 8 23 1 (Blend $ac $bg 0.4)
            Sc-Photo $g 5 11 9 11 $bg (Blend $fg $bg 0.75)
            for ($i = 0; $i -lt 3; $i++) { Fill-Rect $g 16 (11 + $i * 4) 8 1 (Blend $fg $bg 0.4) }
            for ($i = 0; $i -lt 3; $i++) { Fill-Rect $g 5 (24 + $i * 2) 19 1 (Blend $fg $bg 0.4) }
            for ($i = 0; $i -lt 4; $i++) {
                $yy = 8 + $i * 6
                Fill-Rect $g 30 $yy 2 2 @($ac, (C 96 232 156), (C 240 190 80), $ac)[$i]
                Fill-Rect $g 34 ($yy + 1) 20 1 $dim
                Fill-Rect $g 34 ($yy + 3) 12 1 (Blend $fg $bg 0.35)
            }
            Sc-Seal $g 51 32 8 $ac $bg 'OK' 5
        }
        'hotel' {
            Sc-Header $g $w $d 'CHECK-IN'
            Fill-Rect $g 3 9 30 3 $pale
            Fill-Rect $g 3 14 22 2 $dim
            Fill-Rect $g 3 18 26 2 $dim
            Sc-Check $g 42 9 13 (C 96 232 156)
            Draw-Text $g 'VALID' $script:F5 $pale 39 22
            Fill-Rect $g 3 23 30 2 (Blend $fg $bg 0.3)
            Leds $g 37 24 4 (C 96 232 156) $ac (C 240 190 80)
        }
        'scanner' {
            Sc-Header $g $w $d 'SCAN BED'
            Fill-Rect $g 3 9 41 27 (Blend $bg (C 255 255 255) 0.07)
            Fill-Rect $g 8 12 31 21 (Blend $fg $bg 0.82)
            Fill-Rect $g 8 12 31 3 $ac
            for ($i = 0; $i -lt 5; $i++) { Fill-Rect $g 10 (17 + $i * 3) (27 - $i * 2) 1 (Blend $bg $fg 0.35) }
            Sc-Photo $g 27 20 9 10 $bg (Blend $fg $bg 0.82)
            Fill-Rect $g 4 20 39 1 (C 180 250 255)
            Fill-Rect $g 4 21 39 1 (C 90 220 235)
            for ($i = 0; $i -lt 3; $i++) {
                Fill-Rect $g 47 (10 + $i * 4) 2 2 @((C 96 232 156), $ac, (C 240 190 80))[$i]
                Fill-Rect $g 51 (10 + $i * 4) 9 1 $dim
            }
            Draw-Text $g '100%' $script:F5 $pale 46 24
            Fill-Rect $g 47 30 12 4 (Blend $ac $bg 0.35)
            Draw-Text $g 'BED' $script:F5 $fg 49 31
        }
        'manual' {
            Sc-Header $g $w $d 'OFFICER DESK'
            Sc-Table $g 3 8 39 4 5 (Blend $ac $bg 0.5) $dim $bg
            for ($i = 0; $i -lt 4; $i++) {
                Fill-Rect $g 5 (12 + $i * 5) 9 1 $pale
                Fill-Rect $g 16 (12 + $i * 5) 10 1 (Blend $fg $bg 0.35)
                Fill-Rect $g 28 (12 + $i * 5) 11 1 (Blend $fg $bg 0.5)
            }
            Sc-Photo $g 46 8 14 14 $fg $bg
            $p = Pen (Blend $ac $bg 0.25) 2
            $g.DrawArc($p, 45, 24, 16, 14, 180, 180)
            $p.Dispose()
            Fill-Rect $g 47 30 12 6 (Blend $bg (C 255 255 255) 0.1)
            Fill-Rect $g 49 32 8 2 (C 96 232 156)
            for ($i = 0; $i -lt 3; $i++) { Fill-Rect $g (4 + $i * 13) 34 11 4 (Blend $ac $bg (0.5 - $i * 0.1)) }
        }
        'airport' {
            Sc-Header $g $w $d 'BOARDING PASS'
            Fill-Rect $g 3 8 ($w - 6) 21 (Blend $fg $bg 0.86)
            Fill-Rect $g 3 8 14 21 $ac
            Fill-Rect $g 5 11 10 2 (Blend $bg (C 255 255 255) 0.4)
            Fill-Rect $g 5 15 8 1 (Blend $bg $fg 0.35)
            Fill-Rect $g 5 18 9 1 (Blend $bg $fg 0.35)
            Draw-Text $g 'VB' $script:F6 $bg 5 23
            Fill-Rect $g 20 11 18 2 (Blend $bg $fg 0.4)
            Fill-Rect $g 20 15 24 2 (Blend $bg $fg 0.55)
            Fill-Rect $g 20 20 10 1 (Blend $bg $fg 0.3)
            Fill-Rect $g 20 23 10 1 (Blend $bg $fg 0.3)
            Fill-Rect $g 41 11 17 7 (Blend $ac $bg 0.6)
            Draw-Text $g '12A' $script:F6 $bg 43 12
            Fill-Rect $g 41 20 17 2 (Blend $bg $fg 0.3)
            Sc-Barcode $g 41 24 17 4 (Blend $bg $fg 0.7) 9
            Sc-Barcode $g 4 31 26 3 $pale 31
            Draw-Text $g 'GATE 12' $script:F5 $pale 37 31
        }
        'station' {
            Fill-Rect $g 0 0 $w 3 $ac
            Fill-Rect $g 3 5 15 13 (Blend $fg $bg 0.75)
            Sc-Photo $g 5 7 11 8 $bg (Blend $fg $bg 0.75)
            Fill-Rect $g 4 15 13 1 (Blend $ac $bg 0.3)
            Sc-Check $g 23 5 11 (C 96 232 156)
            Draw-Text $g 'PASS' $script:F6 (C 150 245 190) 37 6
            Fill-Rect $g 37 13 18 2 (Blend $fg $bg 0.45)
            Fill-Rect $g 37 17 12 2 (Blend $fg $bg 0.3)
            Fill-Rect $g 24 18 8 2 (Blend $ac $bg 0.35)
            Fill-Rect $g 56 4 5 12 $ac
            Fill-Rect $g 58 6 1 8 $bg
            Fill-Rect $g 58 7 3 2 $bg
        }
        'egate' {
            Sc-Header $g $w $d 'E-GATE VERIFY'
            $p = Pen (Blend $ac $bg 0.1) 1
            $g.DrawRectangle($p, 4, 9, 27, 26)
            $p.Dispose()
            $b = Pen $ac 2
            $g.DrawArc($b, 11, 13, 13, 16, 0, 180)
            $b.Dispose()
            Fill-Rect $g 11 13 2 2 $ac
            Fill-Rect $g 22 13 2 2 $ac
            Fill-Rect $g 17 20 1 4 $ac
            Fill-Rect $g 15 24 5 1 $ac
            $corner = Pen (C 150 250 255) 1
            foreach ($c in @(@(4, 9), @(29, 9), @(4, 33), @(29, 33))) {
                $g.DrawLine($corner, $c[0], $c[1], ($c[0] + 4), $c[1])
                $g.DrawLine($corner, $c[0], $c[1], $c[0], ($c[1] + 4))
            }
            $corner.Dispose()
            for ($i = 0; $i -lt 4; $i++) { Fill-Rect $g 36 (10 + $i * 4) 22 2 $dim }
            Fill-Rect $g 36 27 24 4 (Blend $bg (C 255 255 255) 0.12)
            Fill-Rect $g 37 28 15 2 $ac
            Fill-Rect $g 37 28 8 2 (C 150 250 255)
            Draw-Text $g 'SCAN' $script:F5 $pale 36 33
            Fill-Rect $g 36 37 24 1 (Blend $ac $bg 0.4)
        }
        'egateExit' {
            Sc-Header $g $w $d 'EXIT GATE'
            $p = Pen (Blend $ac $bg 0.18) 1
            $g.DrawRectangle($p, 4, 9, 27, 26)
            $p.Dispose()
            Fill-Rect $g 5 10 12 24 (Blend $bg (C 255 255 255) 0.07)
            Fill-Rect $g 18 10 12 24 (Blend $bg (C 255 255 255) 0.07)
            Fill-Rect $g 17 10 1 24 (Blend $ac $bg 0.55)
            # outbound chevrons pointing left
            $p = Pen $ac 2
            $g.DrawLine($p, 13, 15, 5, 22); $g.DrawLine($p, 5, 22, 13, 29)
            $g.DrawLine($p, 21, 15, 13, 22); $g.DrawLine($p, 13, 22, 21, 29)
            $p.Dispose()
            Fill-Rect $g 5 31 25 2 (Blend $ac $bg 0.35)
            for ($i = 0; $i -lt 4; $i++) { Fill-Rect $g 36 (10 + $i * 4) 22 2 $dim }
            Fill-Rect $g 36 27 24 4 (Blend $bg (C 255 255 255) 0.12)
            Fill-Rect $g 37 28 16 2 $ac
            Fill-Rect $g 37 28 6 2 (C 255 210 160)
            Draw-Text $g 'OUT' $script:F5 $pale 36 33
            Fill-Rect $g 36 37 24 1 (Blend $ac $bg 0.4)
            Fill-Rect $g 58 3 4 3 $ac
        }
        'entry' {
            Sc-Header $g $w $d 'ENTRY STAMP'
            Sc-Seal $g 18 22 12 $ac $bg 'IN' 6
            for ($i = 0; $i -lt 4; $i++) {
                Fill-Rect $g 34 (10 + $i * 5) 8 1 (Blend $fg $bg 0.4)
                Fill-Rect $g 44 (10 + $i * 5) 16 2 $dim
            }
            Fill-Rect $g 3 35 ($w - 6) 5 (Blend $ac $bg 0.2)
            Draw-Text $g 'PORT / DATE' $script:F5 (Blend $ac $bg 0.15) 5 36
            Fill-Rect $g 3 33 9 2 (C 96 232 156)
        }
        'exit' {
            Sc-Header $g $w $d 'EXIT STAMP'
            for ($i = 0; $i -lt 4; $i++) {
                Fill-Rect $g 3 (10 + $i * 5) 8 1 (Blend $fg $bg 0.4)
                Fill-Rect $g 13 (10 + $i * 5) 21 2 $dim
            }
            Sc-Seal $g 45 21 13 $ac $bg 'OUT' 6
            Fill-Rect $g 36 33 26 1 $ac
            Fill-Rect $g 46 15 18 3 (Blend $ac $bg 0.25)
            Fill-Rect $g 3 34 30 6 (Blend $bg (C 255 255 255) 0.08)
            Fill-Rect $g 5 36 12 2 $pale
            Fill-Rect $g 19 36 10 2 $dim
            Fill-Rect $g 55 3 6 3 $ac
        }
        'history' {
            Sc-Header $g $w $d 'STAMP LOG'
            Sc-Table $g 3 8 ($w - 9) 5 4 (Blend $ac $bg 0.55) $dim $bg
            for ($i = 0; $i -lt 5; $i++) {
                $yy = 13 + $i * 4
                Fill-Rect $g 5 $yy 11 1 $pale
                Fill-Rect $g 18 $yy 13 1 (Blend $fg $bg 0.4)
                Fill-Rect $g 34 $yy 15 1 (Blend $fg $bg 0.28)
                Fill-Rect $g 52 $yy 2 2 @((C 96 232 156), (C 240 190 80))[$i % 2]
            }
            Fill-Rect $g ($w - 5) 8 2 ($h - 11) (Blend $bg (C 255 255 255) 0.08)
            Fill-Rect $g ($w - 5) 8 2 10 $ac
        }
        'loss' {
            Sc-Header $g $w $d 'LOST / CANCEL'
            Fill-Rect $g 3 9 25 23 (Blend $fg $bg 0.78)
            Fill-Rect $g 3 9 25 3 (Blend $ac $bg 0.35)
            Sc-Photo $g 5 14 9 10 $bg (Blend $fg $bg 0.78)
            for ($i = 0; $i -lt 3; $i++) { Fill-Rect $g 16 (15 + $i * 4) 9 1 (Blend $fg $bg 0.4) }
            Sc-Cross $g 7 25 17 $ac
            for ($i = 0; $i -lt 3; $i++) {
                Fill-Rect $g 32 (10 + $i * 6) 2 2 @((C 226 80 76), (C 240 190 80), (C 96 232 156))[$i]
                Fill-Rect $g 36 (11 + $i * 6) 23 1 $dim
            }
            Fill-Rect $g 32 30 27 5 $ac
            Draw-Text $g 'STATUS: LOST' $script:F5 $bg 34 31
        }
        'renewal' {
            Fill-Rect $g 0 0 $w 5 $ac
            Draw-Text $g 'RENEW' $script:F5 $bg 3 0
            Fill-Rect $g 4 9 40 9 (Blend $fg $bg 0.68)
            Fill-Rect $g 6 11 14 1 (Blend $bg $fg 0.3)
            Fill-Rect $g 6 14 22 2 (Blend $bg $fg 0.55)
            Fill-Rect $g 18 20 12 2 $ac
            $p = Pen $ac 2
            $g.DrawLine($p, 24, 21, 20, 26)
            $g.DrawLine($p, 24, 21, 28, 26)
            $p.Dispose()
            Fill-Rect $g 4 30 40 9 (Blend $ac $bg 0.55)
            Fill-Rect $g 6 32 14 1 (Blend $bg $fg 0.35)
            Fill-Rect $g 6 35 22 2 (Blend $bg $fg 0.7)
            Draw-Text $g 'NEW' $script:F5 $bg 33 32
            Fill-Rect $g 4 43 40 3 (Blend $fg $bg 0.2)
            Fill-Rect $g 4 43 30 3 (C 96 232 156)
            Fill-Rect $g 4 49 40 10 (C 244 241 232)
            for ($i = 0; $i -lt 5; $i++) { Fill-Rect $g 6 (51 + $i * 2) (30 - $i * 3) 1 (C 120 124 130) }
            Sc-Barcode $g 4 61 40 2 $pale 5
        }
        'print' {
            Sc-Header $g $w $d 'PRINT PREVIEW'
            Fill-Rect $g 3 8 29 22 (C 246 244 236)
            Fill-Rect $g 3 8 29 4 $ac
            Fill-Rect $g 5 10 8 1 (Blend $bg $fg 0.3)
            Sc-Photo $g 5 14 9 10 (C 90 96 102) (C 246 244 236)
            for ($i = 0; $i -lt 4; $i++) { Fill-Rect $g 16 (14 + $i * 3) (13 - $i) 1 (C 110 114 120) }
            Sc-Mrz $g 5 26 25 1 (C 50 54 58) (C 246 244 236) 55
            for ($i = 0; $i -lt 3; $i++) {
                Fill-Rect $g 36 (9 + $i * 5) 2 2 (Blend $ac $bg 0.3)
                Fill-Rect $g 40 (10 + $i * 5) 20 1 $dim
            }
            Fill-Rect $g 36 26 23 4 $ac
            Draw-Text $g 'PRINT' $script:F5 $bg 38 27
            Fill-Rect $g 36 32 23 2 (Blend $bg (C 255 255 255) 0.12)
        }
        'blacklist' {
            Sc-Header $g $w $d 'WATCHLIST'
            for ($i = 0; $i -lt 4; $i++) {
                $yy = 9 + $i * 5
                Fill-Rect $g 4 $yy 2 2 $ac
                Fill-Rect $g 8 ($yy + 1) 14 1 (Blend $fg $bg 0.5)
                Fill-Rect $g 24 ($yy + 1) 18 1 (Blend $fg $bg 0.3)
                Fill-Rect $g 44 ($yy + 1) 10 1 (Blend $ac $bg 0.45)
            }
            Fill-Rect $g 3 30 38 8 $ac
            Draw-Text $g 'DENIED' $script:F6 $bg 8 31
            Sc-Cross $g 46 28 10 $ac
            Fill-Rect $g 46 34 12 2 (Blend $ac $bg 0.4)
        }
        'selfEndorse' {
            Sc-Header $g $w $d 'SELF SERVICE'
            # permit preview card
            Fill-Rect $g 3 9 21 27 (Blend $fg $bg 0.82)
            Fill-Rect $g 3 9 21 1 (Blend $ac $bg 0.25)
            for ($i = 0; $i -lt 4; $i++) { Fill-Rect $g 5 (12 + $i * 3) (15 - $i) 1 (Blend $bg $fg 0.3) }
            Sc-Photo $g 5 24 8 9 $fg $bg
            Fill-Rect $g 15 24 7 2 $ac
            Fill-Rect $g 15 28 6 1 (Blend $bg $fg 0.35)
            Sc-Seal $g 18 32 5 $ac $bg 'OK' 4
            # endorsement type chips
            for ($i = 0; $i -lt 3; $i++) {
                $yy = 10 + $i * 6
                Fill-Rect $g 27 $yy 17 4 (Blend $ac $bg (0.55 - $i * 0.12))
                Fill-Rect $g 28 ($yy + 1) 2 2 $ac
                Fill-Rect $g 31 ($yy + 1) 11 1 (Blend $ac $bg 0.15)
            }
            # touch keypad
            for ($r = 0; $r -lt 2; $r++) {
                for ($c = 0; $c -lt 3; $c++) {
                    Fill-Rect $g (27 + $c * 6) (32 + $r * 5) 4 4 (Blend $bg (C 255 255 255) 0.12)
                    Fill-Rect $g (27 + $c * 6) (32 + $r * 5) 4 1 (Blend $ac $bg 0.35)
                }
            }
            # process bar
            Fill-Rect $g 3 40 41 3 (Blend $bg (C 255 255 255) 0.08)
            Fill-Rect $g 3 40 26 3 $ac
            Fill-Rect $g 3 40 14 3 (Blend $ac (C 255 255 255) 0.45)
            # ticket printer slot
            Fill-Rect $g 3 46 ($w - 6) 5 (Blend $ac $bg 0.5)
            Draw-Text $g 'TAKE TICKET' $script:F5 $bg 5 47
            Fill-Rect $g 3 53 30 2 (Blend $fg $bg 0.25)
            Fill-Rect $g 3 57 20 2 (Blend $bg $fg 0.18)
            Fill-Rect $g 40 3 5 3 $ac
        }
    }
    Save-Bitmap $bmp $g $path
}

function New-Screen2Texture([string]$path, [int]$w, [int]$h, $d, [string]$style) {
    $bg = Col $d.bg
    $fg = Col $d.fg
    $ac = Col $d.accent
    $tr = Col $d.trim
    $dim = Blend $fg $bg 0.5
    $pale = Blend $fg $bg 0.2
    $bmp, $g = New-Bitmap 64 64
    Sc-Frame $g $w $h $d
    Fill-Rect $g 0 0 $w 5 $ac
    $label = switch ($style) {
        'idIssue' { 'KEYPAD' } 'passport' { 'SCAN' } 'permit' { 'SLOT' } 'visa' { 'VISA' }
        'endorse' { 'STAMP' } 'hotel' { 'ROOM' } 'scanner' { 'BED' } 'manual' { 'LOG' }
        'airport' { 'PASS' } 'station' { 'PASS' } 'egate' { 'TAP' } 'entry' { 'INK' }
        'exit' { 'INK' } 'history' { 'LOG' } 'loss' { 'VOID' } 'renewal' { 'DATE' }
        'print' { 'QUEUE' } 'blacklist' { 'ALERT' } 'egateExit' { 'EXIT' } 'selfEndorse' { 'KIOSK' } default { 'STATUS' }
    }
    Draw-Text $g $label $script:F5 $bg 2 0
    $iw = [int]($w * 0.34)
    $ih = [int]($h - 12)
    Fill-Rect $g 3 8 $iw $ih (Blend $bg (C 255 255 255) 0.1)
    switch ($style) {
        'egate' {
            $p = Pen $ac 1
            $g.DrawRectangle($p, 7, 11, ($iw - 8), ($ih - 6))
            $p.Dispose()
            for ($i = 0; $i -lt 3; $i++) {
                $p = Pen (Blend $ac $bg 0.3) 1
                $g.DrawArc($p, (9 + $i * 2), (13 + $i * 2), 8, 8, 300, 120)
                $p.Dispose()
            }
        }
        'loss' { Sc-Cross $g 7 12 12 $ac }
        'blacklist' { Sc-Cross $g 7 12 12 $ac }
        'entry' { Sc-Seal $g ([int]($iw / 2) + 3) ([int]($h / 2) + 1) 7 $ac $bg 'IN' 4 }
        'exit' { Sc-Seal $g ([int]($iw / 2) + 3) ([int]($h / 2) + 1) 7 $ac $bg 'OUT' 4 }
        'egateExit' { Sc-Seal $g ([int]($iw / 2) + 3) ([int]($h / 2) + 1) 7 $ac $bg 'OUT' 4 }
        'selfEndorse' { Sc-QR $g 6 11 10 $pale $bg 41 }
        'airport' { Sc-QR $g 6 11 10 $pale $bg 7 }
        'print' { Fill-Rect $g 6 11 ($iw - 6) 8 (C 246 244 236); Fill-Rect $g 7 12 ($iw - 8) 1 $ac }
        'scanner' { Fill-Rect $g 6 11 ($iw - 6) 8 (C 200 244 248); Fill-Rect $g 6 15 ($iw - 6) 1 (C 255 255 255) }
        default { Sc-Photo $g 6 11 ($iw - 6) ($ih - 5) $fg $bg }
    }
    for ($i = 0; $i -lt 4; $i++) {
        Fill-Rect $g ($iw + 7) (9 + $i * 4) 2 2 @((C 96 232 156), $ac, (C 240 190 80), $ac)[$i]
        Fill-Rect $g ($iw + 11) (10 + $i * 4) ($w - $iw - 16) 1 $dim
    }
    Fill-Rect $g 3 ($h - 4) ($w - 6) 2 (Blend $ac $bg 0.45)
    Save-Bitmap $bmp $g $path
}

# --------------------------------------------------------------- gate signage
# The sign board samples the whole 64 x 64 texture (square UV), so the board is
# drawn edge to edge: dark surround, cyan frame, large ENTRY / EXIT lettering.
function New-GateSignTexture([string]$path, $d) {
    $accent = Col $d.accent
    $bg = Col $d.bg
    $bmp, $g = New-Bitmap 64 64
    Fill-Rect $g 0 0 64 64 (C 8 11 14)
    Fill-Rect $g 2 2 60 60 (Blend $bg (C 255 255 255) 0.06)
    Fill-Rect $g 0 0 64 2 $accent
    Fill-Rect $g 0 62 64 2 (Blend $accent (C 0 0 0) 0.6)
    Fill-Rect $g 0 0 2 64 $accent
    Fill-Rect $g 62 0 2 64 $accent
    Fill-Rect $g 2 2 60 1 (Blend $accent (C 255 255 255) 0.45)
    Draw-TextCentered $g $d.sign (Font 20) (C 255 255 255) 2 13 60
    for ($i = 0; $i -lt 7; $i++) {
        Fill-Rect $g (17 + $i * 4) 45 3 2 (Blend $accent (C 255 255 255) 0.3)
    }
    if ($d.sign -eq 'EXIT') {
        Fill-Rect $g 6 8 3 3 (C 226 80 76)
        Fill-Rect $g 55 8 3 3 (C 226 80 76)
    } else {
        Fill-Rect $g 6 8 3 3 (C 96 232 156)
        Fill-Rect $g 55 8 3 3 (C 96 232 156)
    }
    Save-Bitmap $bmp $g $path
}

# ------------------------------------------------------ gate-only flat panels
# The gate pillars are 4 x 17 px faces, so the generic device surfaces (vents,
# accent bars, screws) would stretch into long stripes. Gate bodies/trim use
# near-uniform matte panels with only horizontal brush lines, which stay clean
# at any face size; all detail on the gates comes from geometry.
function New-GateBodyTexture([string]$path, $d) {
    $base = Col $d.base
    $base2 = Col $d.base2
    $bmp, $g = New-Bitmap 32 32
    Fill-Rect $g 0 0 32 32 $base
    for ($y = 0; $y -lt 32; $y++) {
        $t = $y / 31.0
        Fill-Rect $g 0 $y 32 1 (Blend $base (Blend $base $base2 0.5) ($t * 0.5))
    }
    Fill-Rect $g 0 0 32 1 (Blend $base (C 255 255 255) 0.55)
    Fill-Rect $g 0 31 32 1 (Blend $base2 (C 22 26 30) 0.2)
    Fill-Rect $g 0 0 1 32 (Blend $base2 (C 22 26 30) 0.09)
    Fill-Rect $g 31 0 1 32 (Blend $base2 (C 22 26 30) 0.09)
    Fill-Rect $g 5 9 1 1 (Blend $base2 (C 22 26 30) 0.05)
    Fill-Rect $g 23 21 1 1 (Blend $base (C 255 255 255) 0.3)
    Save-Bitmap $bmp $g $path
}

function New-GateBody2Texture([string]$path, $d) {
    $base2 = Col $d.base2
    $base = Col $d.base
    $bmp, $g = New-Bitmap 32 32
    Fill-Rect $g 0 0 32 32 $base2
    for ($y = 0; $y -lt 32; $y++) {
        $t = $y / 31.0
        Fill-Rect $g 0 $y 32 1 (Blend (Blend $base2 (C 255 255 255) 0.12) $base2 ($t * 0.9))
    }
    for ($y = 3; $y -lt 31; $y += 5) {
        Fill-Rect $g 1 $y 30 1 (Blend $base2 (C 28 32 36) 0.12)
        Fill-Rect $g 1 ($y + 1) 30 1 (Blend $base2 (C 255 255 255) 0.22)
    }
    Fill-Rect $g 0 0 32 1 (Blend $base2 (C 255 255 255) 0.55)
    Fill-Rect $g 0 31 32 1 (Blend $base2 (C 22 26 30) 0.25)
    Save-Bitmap $bmp $g $path
}

function New-GateTrimTexture([string]$path, $d) {
    $trim = Col $d.trim
    $bmp, $g = New-Bitmap 32 32
    Fill-Rect $g 0 0 32 32 $trim
    for ($y = 0; $y -lt 32; $y++) {
        $t = $y / 31.0
        Fill-Rect $g 0 $y 32 1 (Blend (Blend $trim (C 255 255 255) 0.18) (Blend $trim (C 0 0 0) 0.25) $t)
    }
    for ($y = 2; $y -lt 31; $y += 3) {
        Fill-Rect $g 0 $y 32 1 (Blend $trim (C 255 255 255) 0.07)
    }
    Fill-Rect $g 0 0 32 1 (Blend $trim (C 255 255 255) 0.45)
    Fill-Rect $g 0 31 32 1 (C 8 10 12)
    Fill-Rect $g 0 0 1 32 (C 8 10 12)
    Fill-Rect $g 31 0 1 32 (C 8 10 12)
    Save-Bitmap $bmp $g $path
}

function New-GateTopTexture([string]$path, $d) {
    $trim = Col $d.trim
    $bmp, $g = New-Bitmap 32 32
    Fill-Rect $g 0 0 32 32 (Blend $trim (C 0 0 0) 0.15)
    for ($y = 0; $y -lt 32; $y++) {
        $t = $y / 31.0
        Fill-Rect $g 0 $y 32 1 (Blend (Blend $trim (C 255 255 255) 0.1) (Blend $trim (C 0 0 0) 0.3) $t)
    }
    Fill-Rect $g 0 0 32 1 (Blend $trim (C 255 255 255) 0.38)
    for ($i = 0; $i -lt 4; $i++) {
        Fill-Rect $g 4 (8 + $i * 5) 24 1 (Blend $trim (C 255 255 255) 0.06)
    }
    Save-Bitmap $bmp $g $path
}

# Light frosted gate-door pane: pale cyan-white, very low contrast, a couple of
# soft diagonal reflections. Deliberately smooth so the closed door reads as
# glazing inside the dark metal frame instead of a noisy wall.
function New-GateLeafTexture([string]$path, $d) {
    $ac = Col $d.accent
    $bmp, $g = New-Bitmap 32 32
    for ($y = 0; $y -lt 32; $y++) {
        $t = $y / 31.0
        Fill-Rect $g 0 $y 32 1 (Blend (C 228 243 247) (C 198 224 232) ($t * 0.85))
    }
    Draw-Line $g 2 27 20 4 (C 248 254 255) 1
    Draw-Line $g 5 30 24 7 (C 244 252 255) 1
    Draw-Line $g 16 31 30 15 (C 240 250 252) 1
    Fill-Rect $g 0 0 32 1 (C 250 255 255)
    Fill-Rect $g 0 31 32 1 (C 172 200 210)
    Fill-Rect $g 0 0 1 32 (C 240 250 253)
    Fill-Rect $g 31 0 1 32 (C 206 228 235)
    Fill-Rect $g 2 2 7 2 (Blend $ac (C 255 255 255) 0.72)
    Save-Bitmap $bmp $g $path
}
# ------------------------------------------------------- gate-only LED / UI kit
# Cyan LED strip: bright emissive band. The gate maps this onto 0.36 px tall
# strips, so the bright rows 8..24 keep the line glowing after minification.
function New-CyanTexture([string]$path, $d) {
    $bmp, $g = New-Bitmap 32 32
    for ($i = 0; $i -lt 32; $i++) {
        $t = [Math]::Abs($i - 16) / 16.0
        Fill-Rect $g 0 $i 32 1 (Blend (C 58 224 248) (C 8 38 46) ($t * $t))
    }
    Fill-Rect $g 0 7 32 18 (C 86 235 252)
    Fill-Rect $g 0 11 32 10 (C 150 248 255)
    Fill-Rect $g 0 14 32 4 (C 226 255 255)
    Fill-Rect $g 0 15 32 2 (C 255 255 255)
    Save-Bitmap $bmp $g $path
}

# Green direction matrix: full 64 x 64 texture (square UV) with three chevrons
# pointing +u. The wide, short cap-face panel turns them into a shallow LED
# matrix, matching the reference's nose display.
function New-ArrowTexture([string]$path, $d) {
    $bmp, $g = New-Bitmap 64 64
    Fill-Rect $g 0 0 64 64 (C 4 16 11)
    Fill-Rect $g 1 1 62 62 (C 6 32 21)
    Fill-Rect $g 1 1 62 2 (C 30 120 84)
    Fill-Rect $g 1 61 62 2 (C 30 120 84)
    for ($k = 0; $k -lt 3; $k++) {
        $x = 6 + $k * 18
        $pts = [System.Drawing.Point[]]@(
            [System.Drawing.Point]::new(($x + 2), 7),
            [System.Drawing.Point]::new(($x + 17), 32),
            [System.Drawing.Point]::new(($x + 2), 57),
            [System.Drawing.Point]::new(($x + 2), 46),
            [System.Drawing.Point]::new(($x + 9), 32),
            [System.Drawing.Point]::new(($x + 2), 18)
        )
        $b = Brush (C 62 236 138); $g.FillPolygon($b, $pts); $b.Dispose()
        $pts2 = [System.Drawing.Point[]]@(
            [System.Drawing.Point]::new(($x + 2), 14),
            [System.Drawing.Point]::new(($x + 12), 32),
            [System.Drawing.Point]::new(($x + 2), 50),
            [System.Drawing.Point]::new(($x + 2), 43),
            [System.Drawing.Point]::new(($x + 6), 32),
            [System.Drawing.Point]::new(($x + 2), 23)
        )
        $b = Brush (C 214 255 226); $g.FillPolygon($b, $pts2); $b.Dispose()
    }
    Save-Bitmap $bmp $g $path
}

# Portrait tablet reader UI, full 64 x 64 (square UV). The model panel is a tall
# portrait rectangle, so wide, short shapes are used here: after mapping they
# read as a lit portrait display with a face-scan chip, data rows and a big
# green approval check.
function New-TabletTexture([string]$path, $d) {
    $ac = Col $d.accent
    $bg = Col $d.bg
    $fg = Col $d.fg
    $pale = Blend $fg $bg 0.28
    $dim = Blend $fg $bg 0.55
    $bmp, $g = New-Bitmap 64 64
    Fill-Rect $g 0 0 64 64 (Blend $bg (C 255 255 255) 0.05)
    Fill-Rect $g 0 0 64 64 (Blend $ac $bg 0.08)
    Fill-Rect $g 0 0 64 5 (Blend $ac (C 255 255 255) 0.1)
    Fill-Rect $g 0 5 64 1 (Blend $ac (C 255 255 255) 0.3)
    Fill-Rect $g 0 0 2 64 (Blend $ac (C 255 255 255) 0.35)
    Fill-Rect $g 62 0 2 64 (Blend $ac (C 255 255 255) 0.35)
    for ($i = 0; $i -lt 4; $i++) { Fill-Rect $g (3 + $i * 4) 1 2 2 $pale }
    Fill-Rect $g 44 2 16 1 $pale
    # wide face-scan chip (round after portrait compression)
    Fill-Rect $g 4 10 36 20 (Blend $fg $bg 0.85)
    Fill-Rect $g 4 10 36 1 $pale
    $face = Brush (Blend $bg $fg 0.55)
    $g.FillEllipse($face, 13, 13, 18, 12)
    $g.FillRectangle($face, 9, 24, 26, 4)
    $face.Dispose()
    # data rows to the right of the chip
    for ($i = 0; $i -lt 4; $i++) {
        Fill-Rect $g 42 (11 + $i * 5) 18 2 $dim
        Fill-Rect $g 42 (13 + $i * 5) 12 1 (Blend $fg $bg 0.4)
    }
    # approval check (wide ellipse + thick check)
    $p = Pen (C 74 234 150) 3
    $g.DrawEllipse($p, 6, 34, 28, 18)
    $g.DrawLine($p, 12, 43, 18, 48)
    $g.DrawLine($p, 18, 48, 30, 37)
    $p.Dispose()
    # status lines and barcode
    for ($i = 0; $i -lt 5; $i++) { Fill-Rect $g 39 (37 + $i * 3) 21 1 (Blend $fg $bg 0.45) }
    for ($i = 0; $i -lt 10; $i++) { Fill-Rect $g (4 + $i * 2) 56 1 5 (Blend $fg $bg 0.25) }
    Fill-Rect $g 39 56 21 2 (Blend $fg $bg 0.35)
    Fill-Rect $g 39 60 21 2 $ac
    Save-Bitmap $bmp $g $path
}

# ------------------------------------------------------------------- driver
function New-DeviceTextures($d) {
    $id = $d.id
    if ($d.gate) {
        New-GateBodyTexture (Join-Path $blockTex "${id}_body.png") $d
        New-GateBody2Texture (Join-Path $blockTex "${id}_body2.png") $d
        New-GateTrimTexture (Join-Path $blockTex "${id}_trim.png") $d
        New-GateTopTexture (Join-Path $blockTex "${id}_top.png") $d
    } else {
        New-BodyTexture (Join-Path $blockTex "${id}_body.png") $d
        New-Body2Texture (Join-Path $blockTex "${id}_body2.png") $d
        New-TrimTexture (Join-Path $blockTex "${id}_trim.png") $d
        New-TopTexture (Join-Path $blockTex "${id}_top.png") $d
    }
    New-AccentTexture (Join-Path $blockTex "${id}_accent.png") $d
    New-DetailTexture (Join-Path $blockTex "${id}_detail.png") $d
    New-LedTexture (Join-Path $blockTex "${id}_led.png") $d
    New-GlassTexture (Join-Path $blockTex "${id}_glass.png") $d
    New-PaperTexture (Join-Path $blockTex "${id}_paper.png") $d
    New-WarningTexture (Join-Path $blockTex "${id}_warning.png") $d
    New-ScreenTexture (Join-Path $blockTex "${id}_screen.png") ($d.px1)[0] ($d.px1)[1] $d $d.style
    New-Screen2Texture (Join-Path $blockTex "${id}_screen2.png") ($d.px2)[0] ($d.px2)[1] $d $d.style
    if ($d.gate) {
        New-GateSignTexture (Join-Path $blockTex "${id}_sign.png") $d
        New-CyanTexture (Join-Path $blockTex "${id}_cyan.png") $d
        New-ArrowTexture (Join-Path $blockTex "${id}_arrow.png") $d
        New-TabletTexture (Join-Path $blockTex "${id}_tablet.png") $d`r`n        New-GateLeafTexture (Join-Path $blockTex "${id}_leaf.png") $d
    }
}
