# ---------------------------------------------------------------------------
# Hand-plotted 3x5 pixel font for the item SCREEN textures.
#
# GDI+ text below ~7 px turns to mush and its hinting is not stable across
# machines, which is fatal for a 32x32 screen that is sampled across a ~9 px
# model face. The screen headings are therefore plotted from this table: crisp,
# deterministic, and identical on every render.
#
# Each glyph is 5 rows of 3 bits (MSB = left column).
# ---------------------------------------------------------------------------
function IA-Font3x5 {
    if ($null -ne $Script:IA_F35) { return $Script:IA_F35 }
    $f = @{}
    $f[' '] = @(0, 0, 0, 0, 0)
    $f['A'] = @(2, 5, 7, 5, 5)
    $f['B'] = @(6, 5, 6, 5, 6)
    $f['C'] = @(3, 4, 4, 4, 3)
    $f['D'] = @(6, 5, 5, 5, 6)
    $f['E'] = @(7, 4, 6, 4, 7)
    $f['F'] = @(7, 4, 6, 4, 4)
    $f['G'] = @(3, 4, 5, 5, 3)
    $f['H'] = @(5, 5, 7, 5, 5)
    $f['I'] = @(7, 2, 2, 2, 7)
    $f['J'] = @(1, 1, 1, 5, 2)
    $f['K'] = @(5, 5, 6, 5, 5)
    $f['L'] = @(4, 4, 4, 4, 7)
    $f['M'] = @(5, 7, 5, 5, 5)
    $f['N'] = @(5, 7, 7, 7, 5)
    $f['O'] = @(7, 5, 5, 5, 7)
    $f['P'] = @(7, 5, 7, 4, 4)
    $f['Q'] = @(7, 5, 5, 7, 1)
    $f['R'] = @(7, 5, 7, 6, 5)
    $f['S'] = @(7, 4, 7, 1, 7)
    $f['T'] = @(7, 2, 2, 2, 2)
    $f['U'] = @(5, 5, 5, 5, 7)
    $f['V'] = @(5, 5, 5, 5, 2)
    $f['W'] = @(5, 5, 7, 7, 5)
    $f['X'] = @(5, 5, 2, 5, 5)
    $f['Y'] = @(5, 5, 2, 2, 2)
    $f['Z'] = @(7, 1, 2, 4, 7)
    $f['0'] = @(7, 5, 5, 5, 7)
    $f['1'] = @(2, 6, 2, 2, 7)
    $f['2'] = @(7, 1, 7, 4, 7)
    $f['3'] = @(7, 1, 7, 1, 7)
    $f['4'] = @(5, 5, 7, 1, 1)
    $f['5'] = @(7, 4, 7, 1, 7)
    $f['6'] = @(7, 4, 7, 5, 7)
    $f['7'] = @(7, 1, 1, 1, 1)
    $f['8'] = @(7, 5, 7, 5, 7)
    $f['9'] = @(7, 5, 7, 1, 7)
    $f['/'] = @(1, 1, 2, 4, 4)
    $f['-'] = @(0, 0, 7, 0, 0)
    $f['.'] = @(0, 0, 0, 0, 2)
    $f[':'] = @(0, 2, 0, 2, 0)
    $Script:IA_F35 = $f
    return $f
}

# Plot $text at ($x,$y) top-left with an integer $scale (1 = 3x5, 2 = 6x10).
# Emits NOTHING: painters call this as a bare statement, and a returned value
# would leak into the painter's own return value (PowerShell streams every
# expression result out of a function, so returning the width here turned each
# texture's "alpha fixed" count into an array).
function IA-PlotText($g, [string]$text, [int]$x, [int]$y, [int]$scale, $col) {
    $f = IA-Font3x5
    $cx = $x
    foreach ($ch in $text.ToUpperInvariant().ToCharArray()) {
        $key = [string]$ch
        if (-not $f.ContainsKey($key)) { $cx += (4 * $scale); continue }
        $rows = $f[$key]
        for ($r = 0; $r -lt 5; $r++) {
            $bits = $rows[$r]
            for ($c = 0; $c -lt 3; $c++) {
                if (($bits -band (4 -shr $c)) -ne 0) {
                    IA-Fill $g ($cx + $c * $scale) ($y + $r * $scale) $scale $scale $col
                }
            }
        }
        $cx += (4 * $scale)
    }
}

# Width of $text in the 3x5 font at $scale (no trailing gap)
function IA-TextWidth([string]$text, [int]$scale) {
    return ([Math]::Max(0, $text.Length * 4 - 1) * $scale)
}

# Centred plot helper used by the screen painters.
function IA-PlotTextCentered($g, [string]$text, [int]$cx, [int]$y, [int]$scale, $col) {
    $w = IA-TextWidth $text $scale
    $x = $cx - [int][Math]::Floor($w / 2)
    IA-PlotText $g $text $x $y $scale $col
}
