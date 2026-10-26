# ---------------------------------------------------------------------------
# VeriBorder 26.3.x document-card generator  (flat single-sheet cards)
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_document_cards.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_document_cards.ps1 -ModelsOnly
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_document_cards.ps1 -Root .\scratch
#
# What this owns (textures\item + models\item, nothing else):
#
#   <art>_flat.png    64x64  the TINTABLE card: one flat sheet, neutral grey ramp,
#                            tintIndex 0 -> ItemColors dyes the whole card
#   emblem_<id>.png   64x64  the 15 preset crests, drawn INSIDE the card face so
#                            they can be layered on top of any flat card
#   <art>_frame.png   64x64  the same card with the base-map window cut out, used
#                            by the custom-cover renderer (picture behind, frame
#                            in front) - see client/render/DocumentItemRenderer
#   doc_layers_<art>.json     layer0 = <art>_flat, layer1 = emblem_<default>
#   <item>_emblem_<id>.json   the same model with layer1 swapped for another crest
#   <item>.json               the credential model: crest + slot + custom overrides
#   <item>_slot_<n>.json      builtin/entity variants for the uploaded base map
#   custom_<art>.json         the custom-cover model (same flat card, tinted by the
#                            document's own accent)
#   <legacy style>.png/.json  the ten pre-26.4.1 per-layout cards, painted as a
#                            flattened (pre-tinted) flat card so old models, the
#                            creative tab and resource packs keep working
#
# art  = id_card | passport | permit   (the third item id is hk_macao_permit)
# item = id_card | passport | hk_macao_permit
#
# Design rules (the "26.2 flat card" look, at double the detail):
#   * ONE sheet per card: body, print, window and seal all live in the same
#     texture. Nothing is stacked into three semi-transparent layers any more, so
#     the icon reads as a card at 16 px instead of a grey pile of rectangles.
#   * NEUTRAL GREY ONLY. ItemColors multiplies layer0 by the document colour, so
#     every texel is a grey value: the tint then produces the template colour
#     without keeping a coloured copy of the art per layout.
#   * ONE key light from the TOP-LEFT: bright top/left foil edge, dark bottom/right.
#   * dark card CORNERS (the 26.2 signature) plus paper grain and a fine security
#     screen, which is where the extra detail of the 64x64 canvas goes.
#   * BINARY alpha: every texel is either fully opaque or fully transparent, and
#     transparent texels keep RGB 0 so no mipmap can bleed a dark halo.
#   * NO anti-aliased primitives (no DrawEllipse / DrawString): circles, stars and
#     letters are rasterised by integer maths - shader safe by construction.
#   * The crests are drawn in a shared 16x16 seal box (32,17)..(47,32) that lies
#     inside all three card faces and clear of header, photo and MRZ, so ONE crest
#     texture can be layered onto any of them and the override set stays 15 crests
#     instead of 45.
#
# Rerunning is idempotent: no timestamps and no RNG (all noise is a pure function
# of the pixel coordinate), and every file is rewritten from scratch.
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [switch]$ModelsOnly,
    [string]$Root
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = if ($Root) { (Resolve-Path -LiteralPath $Root).Path } else { Split-Path -Parent $PSScriptRoot }
$itemTex = Join-Path $root 'src\main\resources\assets\veriborder\textures\item'
$itemModels = Join-Path $root 'src\main\resources\assets\veriborder\models\item'
New-Item -ItemType Directory -Force $itemTex | Out-Null
New-Item -ItemType Directory -Force $itemModels | Out-Null

# ---- manifest --------------------------------------------------------------
# art id, credential item id, card face rect (x, y, w, h), booklet?
$kinds = @(
    @{ art = 'id_card';  doc = 'id_card';         x = 4;  y = 15; w = 56; h = 36; booklet = $false }
    @{ art = 'passport'; doc = 'passport';        x = 13; y = 6;  w = 38; h = 52; booklet = $true }
    @{ art = 'permit';   doc = 'hk_macao_permit'; x = 7;  y = 13; w = 50; h = 38; booklet = $false }
)

# The base-map window of the custom-cover renderer. The uploaded picture is
# stretched over the whole item face and this frame is drawn on top, so the
# window has to sit inside all three card faces (id card 4..59/15..50,
# permit 7..56/13..50, booklet 13..50/6..57): 16..47 / 20..43 does.
$winX = 16; $winY = 20; $winW = 32; $winH = 24

# Shared crest seal box: inside every card face, clear of the header plate, of the
# photo window, of the data rules and of the MRZ band of all three layouts.
$sealX = 32; $sealY = 17; $sealSize = 16

# Preset crests. Order == IconPicker.ICONS order == the item-model predicate
# index, so the registry editor, the viewer and the item overlay cannot disagree.
$emblems = @(
    'emblem', 'star', 'crown', 'shield', 'globe', 'leaf', 'anchor', 'key',
    'stamp', 'book', 'diamond', 'flame', 'gear', 'eagle', 'mrz'
)
$defaultEmblem = 'emblem'

# The pre-26.4.1 per-layout cards. They are flattened (already coloured) copies of
# the flat card, kept because older resource packs and the creative tab sample
# these names; hk_macao_permit.png is the permit's own legacy card.
$legacyCards = @(
    @{ name = 'id_card_cn';        art = 'id_card';  accent = 0xC43B2D }
    @{ name = 'id_card_hk';        art = 'id_card';  accent = 0x8A2330 }
    @{ name = 'passport_cn';       art = 'passport'; accent = 0x9E2B25 }
    @{ name = 'passport_hk';       art = 'passport'; accent = 0x203A6B }
    @{ name = 'passport_mo';       art = 'passport'; accent = 0x1F5B45 }
    @{ name = 'passport_us';       art = 'passport'; accent = 0x1B356B }
    @{ name = 'passport_jp';       art = 'passport'; accent = 0xA3212B }
    @{ name = 'hk_macao_permit';   art = 'permit';   accent = 0x1D4F91 }
    @{ name = 'custom_id_card';    art = 'id_card';  accent = 0xFFFFFF }
    @{ name = 'custom_passport';   art = 'passport'; accent = 0xFFFFFF }
    @{ name = 'custom_permit';     art = 'permit';   accent = 0xFFFFFF }
)

# ---- pixel helpers ---------------------------------------------------------
function Grey([int]$v) {
    if ($v -lt 0) { $v = 0 }
    if ($v -gt 255) { $v = 255 }
    return [System.Drawing.Color]::FromArgb(255, $v, $v, $v)
}
$Clear = [System.Drawing.Color]::FromArgb(0, 0, 0, 0)

function New-Canvas {
    $bmp = [System.Drawing.Bitmap]::new(64, 64, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) { $bmp.SetPixel($x, $y, $Clear) }
    }
    return $bmp
}

function Px($bmp, [int]$x, [int]$y, $col) {
    if ($x -lt 0 -or $y -lt 0 -or $x -ge $bmp.Width -or $y -ge $bmp.Height) { return }
    $bmp.SetPixel($x, $y, $col)
}

function Fill($bmp, [int]$x, [int]$y, [int]$w, [int]$h, $col) {
    for ($iy = $y; $iy -lt ($y + $h); $iy++) {
        for ($ix = $x; $ix -lt ($x + $w); $ix++) { Px $bmp $ix $iy $col }
    }
}

function HLine($bmp, [int]$x1, [int]$x2, [int]$y, $col) { Fill $bmp $x1 $y ([Math]::Max(0, $x2 - $x1 + 1)) 1 $col }
function VLine($bmp, [int]$x, [int]$y1, [int]$y2, $col) { Fill $bmp $x $y1 1 ([Math]::Max(0, $y2 - $y1 + 1)) $col }

function Ring($bmp, [int]$x, [int]$y, [int]$w, [int]$h, $col) {
    HLine $bmp $x ($x + $w - 1) $y $col
    HLine $bmp $x ($x + $w - 1) ($y + $h - 1) $col
    VLine $bmp $x $y ($y + $h - 1) $col
    VLine $bmp ($x + $w - 1) $y ($y + $h - 1) $col
}

# Deterministic value noise: the same coordinate always yields the same value, so
# a rerun is byte identical (there is no RNG anywhere in this generator).
function Noise([int]$x, [int]$y, [int]$salt) {
    $h = ([long]$x * 374761393) + ([long]$y * 668265263) + ([long]$salt * 1442695041)
    $h = $h -band 0x7FFFFFFF
    return [int]($h % 997)
}

function Shift($col, [int]$delta) {
    $v = $col.R + $delta
    if ($v -lt 0) { $v = 0 }
    if ($v -gt 255) { $v = 255 }
    return (Grey $v)
}

# Save with hard alpha (0 or 255, transparent pixels zeroed) and report fixes.
function Save-Png($bmp, [string]$name) {
    $fixes = 0
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        for ($x = 0; $x -lt $bmp.Width; $x++) {
            $p = $bmp.GetPixel($x, $y)
            if ($p.A -eq 255) { continue }
            if ($p.A -eq 0) {
                if ($p.R -ne 0 -or $p.G -ne 0 -or $p.B -ne 0) { $bmp.SetPixel($x, $y, $Clear) }
                continue
            }
            $fixes++
            if ($p.A -ge 128) {
                $bmp.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $p.R, $p.G, $p.B))
            } else {
                $bmp.SetPixel($x, $y, $Clear)
            }
        }
    }
    $bmp.Save((Join-Path $itemTex "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    return $fixes
}

# ---- card painter ----------------------------------------------------------
# The whole card is one neutral grey sheet. Value ramp of the body:
#   outline 12 -> foil edge 255/246 -> body 236..252 -> bottom shade 192
# so a tint of 0x9E2B25 turns it into a red card and 0x1D4F91 into a navy one.
function New-CardBitmap($kind, [switch]$Frame) {
    $bmp = New-Canvas
    $x = $kind.x; $y = $kind.y; $w = $kind.w; $h = $kind.h

    # drop shadow: two offset copies OUTSIDE the face, kept off the canvas border
    Fill $bmp ($x + $w) ($y + 2) 2 $h (Grey 26)
    Fill $bmp ($x + 2) ($y + $h) $w 2 (Grey 30)
    Fill $bmp ($x + $w) ($y + $h) 2 2 (Grey 18)

    # 1 px silhouette
    Ring $bmp ($x - 1) ($y - 1) ($w + 2) ($h + 2) (Grey 12)

    # body: vertical ramp ...
    for ($iy = 0; $iy -lt $h; $iy++) {
        $t = $iy / [double]($h - 1)
        if ($t -lt 0.16) { $v = 236 + [int](16 * ($t / 0.16)) }
        elseif ($t -lt 0.58) { $v = 252 - [int](8 * (($t - 0.16) / 0.42)) }
        elseif ($t -lt 0.90) { $v = 244 - [int](22 * (($t - 0.58) / 0.32)) }
        else { $v = 222 - [int](30 * (($t - 0.90) / 0.10)) }
        HLine $bmp ($x + 1) ($x + $w - 2) ($y + $iy) (Grey $v)
    }
    # ... darkened corners: the 26.2 card curl, three texels of wedge per corner
    for ($i = 0; $i -lt 3; $i++) {
        $dark = 206 - $i * 12
        HLine $bmp ($x + 1) ($x + 2 + $i) ($y + 1 + $i) (Grey $dark)
        HLine $bmp ($x + $w - 3 - $i) ($x + $w - 2) ($y + 1 + $i) (Grey $dark)
        HLine $bmp ($x + 1) ($x + 2 + $i) ($y + $h - 2 - $i) (Grey ($dark - 18))
        HLine $bmp ($x + $w - 3 - $i) ($x + $w - 2) ($y + $h - 2 - $i) (Grey ($dark - 18))
    }

    # paper grain: +-5 on about one texel in seven
    for ($iy = $y + 1; $iy -lt ($y + $h - 1); $iy++) {
        for ($ix = $x + 1; $ix -lt ($x + $w - 1); $ix++) {
            $n = Noise $ix $iy 117
            if (($n % 7) -ne 0) { continue }
            $delta = if (($n % 2) -eq 0) { 5 } else { -6 }
            Px $bmp $ix $iy (Shift $bmp.GetPixel($ix, $iy) $delta)
        }
    }
    # fine security screen: one step darker where x+y is a multiple of four
    for ($iy = $y + 2; $iy -lt ($y + $h - 2); $iy++) {
        for ($ix = $x + 2; $ix -lt ($x + $w - 2); $ix++) {
            if ((($ix + $iy) % 4) -ne 0) { continue }
            if ((Noise $ix $iy 211) % 3 -eq 0) { continue }
            Px $bmp $ix $iy (Shift $bmp.GetPixel($ix, $iy) -3)
        }
    }

    # inner foil frame (1 px, 198) and the single top-left key light
    Ring $bmp ($x + 2) ($y + 2) ($w - 4) ($h - 4) (Grey 198)
    HLine $bmp ($x + 1) ($x + $w - 2) ($y + 1) (Grey 255)
    VLine $bmp ($x + 1) ($y + 1) ($y + $h - 2) (Grey 246)
    VLine $bmp ($x + 2) ($y + 1) ($y + $h - 2) (Grey 236)
    HLine $bmp ($x + 1) ($x + $w - 2) ($y + $h - 2) (Grey 150)
    VLine $bmp ($x + $w - 2) ($y + 1) ($y + $h - 2) (Grey 168)

    if ($kind.booklet) {
        # ---- booklet cover: spine on the LEFT, page block behind on the right
        Fill $bmp ($x + 1) ($y + 2) 3 ($h - 4) (Grey 204)
        VLine $bmp ($x + 2) ($y + 2) ($y + $h - 3) (Grey 184)
        VLine $bmp ($x + 4) ($y + 1) ($y + $h - 2) (Grey 176)

        # page block: the cut edges of the stitched pages, right of the cover
        Fill $bmp ($x + $w - 1) ($y + 4) 2 ($h - 9) (Grey 244)
        VLine $bmp ($x + $w) ($y + 4) ($y + $h - 6) (Grey 156)
        for ($i = 6; $i -lt ($h - 8); $i += 4) { Px $bmp ($x + $w - 1) ($y + $i) (Grey 255) }

        # title band across the top of the cover
        Fill $bmp ($x + 7) ($y + 3) ($w - 13) 8 (Grey 216)
        HLine $bmp ($x + 7) ($x + $w - 7) ($y + 3) (Grey 246)
        HLine $bmp ($x + 7) ($x + $w - 7) ($y + 10) (Grey 156)
        # "PASSPORT" as solid tiles (there is no anti-aliased text in this file)
        for ($i = 0; $i -lt ($w - 17); $i += 4) {
            if ((Noise $i 1 71) % 6 -eq 0) { continue }
            $tw = 1 + ((Noise $i 2 72) % 3)
            Fill $bmp ($x + 9 + $i) ($y + 5) $tw 3 (Grey 62)
        }

        # guilloche: three dashed rings around the middle of the cover
        for ($r = 6; $r -le (6 + $w / 2); $r += 4) {
            for ($a = 0; $a -lt 360; $a += 3) {
                if ((Noise $r $a 151) % 5 -eq 0) { continue }
                $px = [int][Math]::Round(($x + $w / 2) + $r * [Math]::Cos($a * [Math]::PI / 180))
                $py = [int][Math]::Round(($y + $h / 2) + $r * [Math]::Sin($a * [Math]::PI / 180))
                if ($px -lt ($x + 5) -or $px -gt ($x + $w - 5)) { continue }
                if ($py -lt ($y + 12) -or $py -gt ($y + $h - 13)) { continue }
                Px $bmp $px $py (Grey 212)
            }
        }

        # footer: a foil rule with the issuing-code tiles underneath
        Fill $bmp ($x + 7) ($y + $h - 13) ($w - 13) 1 (Grey 176)
        for ($i = 0; $i -lt ($w - 20); $i += 3) {
            if ((Noise $i 7 93) % 4 -eq 0) { continue }
            Fill $bmp ($x + 10 + $i) ($y + $h - 11) 2 2 (Grey 120)
        }
    } else {
        # ---- flat card: header plate, photo window, data rules, MRZ band
        # header plate, kept left of the seal box
        $headW = [Math]::Min([int]($w * 0.50), $sealX - ($x + 3) - 2)
        $hy = $y + 4
        Fill $bmp ($x + 3) $hy $headW 8 (Grey 206)
        HLine $bmp ($x + 3) ($x + 2 + $headW) $hy (Grey 240)
        HLine $bmp ($x + 3) ($x + 2 + $headW) ($hy + 7) (Grey 152)
        # a two-row word mark, dark enough to survive any tint
        for ($i = 0; $i -lt ($headW - 6); $i += 4) {
            if ((Noise $i 1 71) % 6 -eq 0) { continue }
            $tw = 1 + ((Noise $i 2 72) % 3)
            Fill $bmp ($x + 5 + $i) ($hy + 2) $tw 2 (Grey 58)
            if ((Noise $i 3 73) % 3 -ne 0) { Fill $bmp ($x + 5 + $i) ($hy + 5) ($tw + 1) 1 (Grey 96) }
        }

        # chip: contact plate in the top-right corner, right of the seal box
        $chipX = $x + $w - 9
        Fill $bmp $chipX ($y + 4) 5 5 (Grey 228)
        Ring $bmp $chipX ($y + 4) 5 5 (Grey 118)
        HLine $bmp ($chipX + 1) ($chipX + 3) ($y + 5) (Grey 150)
        HLine $bmp ($chipX + 1) ($chipX + 3) ($y + 7) (Grey 150)
        VLine $bmp ($chipX + 2) ($y + 5) ($y + 7) (Grey 150)

        # photo window: dark room with a head-and-shoulders silhouette
        $px0 = $x + 4
        $py0 = $y + 13
        $pw = [int]($w * 0.28)
        $ph = $h - 22
        Fill $bmp $px0 $py0 $pw $ph (Grey 66)
        Ring $bmp ($px0 - 1) ($py0 - 1) ($pw + 2) ($ph + 2) (Grey 44)
        HLine $bmp $px0 ($px0 + $pw - 1) $py0 (Grey 100)
        VLine $bmp $px0 $py0 ($py0 + $ph - 1) (Grey 88)
        $headR = [Math]::Max(2, [int]($pw * 0.24))
        $hcx = $px0 + [int]($pw / 2)
        $hcy = $py0 + [Math]::Max(3, [int]($ph * 0.30))
        for ($iy = -$headR; $iy -le $headR; $iy++) {
            for ($ix = -$headR; $ix -le $headR; $ix++) {
                if (($ix * $ix + $iy * $iy) -gt ($headR * $headR)) { continue }
                Px $bmp ($hcx + $ix) ($hcy + $iy) (Grey 188)
            }
        }
        $bodyTop = $hcy + $headR + 1
        for ($iy = $bodyTop; $iy -lt ($py0 + $ph - 1); $iy++) {
            $half = [int](($pw * 0.30) + ($iy - $bodyTop) * 0.55)
            for ($ix = ($hcx - $half); $ix -le ($hcx + $half); $ix++) {
                if ($ix -le $px0 -or $ix -ge ($px0 + $pw - 1)) { continue }
                Px $bmp $ix $iy (Grey 148)
            }
        }

        # data rules: below the seal box, above the MRZ band
        $rx = $px0 + $pw + 3
        $rw = $x + $w - 4 - $rx
        for ($i = 0; $i -lt 3; $i++) {
            $ry = $sealY + $sealSize + 1 + $i * 3
            if ($ry -ge ($y + $h - 9)) { break }
            Fill $bmp $rx $ry 3 1 (Grey 96)
            Fill $bmp ($rx + 4) $ry ([Math]::Max(2, $rw - 4)) 1 (Grey (166 + 10 * $i))
        }
    }

    # ---- MRZ band along the bottom (both layouts own this band) --------------
    $mzY = $y + $h - 8
    $mzX = $x + 3
    $mzW = $w - 6
    Fill $bmp $mzX $mzY $mzW 6 (Grey 214)
    Ring $bmp $mzX $mzY $mzW 6 (Grey 168)
    for ($i = 0; $i -lt ($mzW - 4); $i += 2) {
        if ((Noise $i 5 91) % 4 -eq 0) { continue }
        VLine $bmp ($mzX + 2 + $i) ($mzY + 1) ($mzY + 4) (Grey 74)
    }

    # corner registration ticks
    Fill $bmp ($x + 2) ($y + 2) 2 1 (Grey 252)
    Fill $bmp ($x + $w - 4) ($y + 2) 2 1 (Grey 252)
    Fill $bmp ($x + 2) ($y + $h - 3) 2 1 (Grey 214)
    Fill $bmp ($x + $w - 4) ($y + $h - 3) 2 1 (Grey 214)

    if ($Frame) {
        # ---- the same card with the base-map window cut out -----------------
        # The uploaded picture is drawn first and this frame on top, so only the
        # window shows the image. Cut AFTER the print, so nothing survives inside.
        for ($iy = $winY; $iy -lt ($winY + $winH); $iy++) {
            for ($ix = $winX; $ix -lt ($winX + $winW); $ix++) { Px $bmp $ix $iy $Clear }
        }
        # window frame: dark inner lip + light outer bezel, 2 px
        Ring $bmp ($winX - 1) ($winY - 1) ($winW + 2) ($winH + 2) (Grey 46)
        Ring $bmp ($winX - 2) ($winY - 2) ($winW + 4) ($winH + 4) (Grey 178)
        HLine $bmp ($winX - 2) ($winX + $winW + 1) ($winY - 2) (Grey 232)
        VLine $bmp ($winX - 2) ($winY - 2) ($winY + $winH + 1) (Grey 222)
    }

    return $bmp
}

# ---- crests (layer1) -------------------------------------------------------
# Every glyph is rasterised into the shared 16x16 seal box by integer maths (no
# anti-aliasing). Values: body 236, outline 40, detail 150.
$Body = Grey 236
$Edge = Grey 40
$Detail = Grey 150
$SealShadow = Grey 116

function GS($bmp, [int]$gx, [int]$gy, $col) {
    if ($gx -lt 0 -or $gy -lt 0 -or $gx -ge $sealSize -or $gy -ge $sealSize) { return }
    Px $bmp ($sealX + $gx) ($sealY + $gy) $col
}
function GF($bmp, [int]$x0, [int]$y0, [int]$x1, [int]$y1, $col) {
    for ($y = $y0; $y -le $y1; $y++) { for ($x = $x0; $x -le $x1; $x++) { GS $bmp $x $y $col } }
}
function GR($bmp, [int]$x0, [int]$y0, [int]$x1, [int]$y1, $col) {
    for ($x = $x0; $x -le $x1; $x++) { GS $bmp $x $y0 $col; GS $bmp $x $y1 $col }
    for ($y = $y0; $y -le $y1; $y++) { GS $bmp $x0 $y $col; GS $bmp $x1 $y $col }
}
function GDisc($bmp, [int]$cx, [int]$cy, [int]$r, $col, [bool]$filled = $true) {
    $rr = $r * $r
    $ri = ($r - 1) * ($r - 1)
    for ($y = ($cy - $r); $y -le ($cy + $r); $y++) {
        for ($x = ($cx - $r); $x -le ($cx + $r); $x++) {
            $d = ($x - $cx) * ($x - $cx) + ($y - $cy) * ($y - $cy)
            if ($d -gt $rr) { continue }
            if (-not $filled -and $d -le $ri) { continue }
            GS $bmp $x $y $col
        }
    }
}
function GDiag($bmp, [int]$x0, [int]$y0, [int]$x1, [int]$y1, $col) {
    $dx = [Math]::Abs($x1 - $x0); $dy = [Math]::Abs($y1 - $y0)
    $sx = if ($x0 -lt $x1) { 1 } else { -1 }
    $sy = if ($y0 -lt $y1) { 1 } else { -1 }
    $err = $dx - $dy
    while ($true) {
        GS $bmp $x0 $y0 $col
        if ($x0 -eq $x1 -and $y0 -eq $y1) { break }
        $e2 = 2 * $err
        if ($e2 -gt (-$dy)) { $err -= $dy; $x0 += $sx }
        if ($e2 -lt $dx) { $err += $dx; $y0 += $sy }
    }
}

function Draw-Glyph($bmp, [string]$id) {
    switch ($id) {
        'emblem' {
            GDisc $bmp 7 7 7 $Edge $false
            GDisc $bmp 7 7 6 $Body $false
            GDisc $bmp 7 7 2 $Edge
            GF $bmp 7 7 8 7 $Body
            GF $bmp 7 7 7 8 $Body
            GF $bmp 6 1 9 1 $Detail; GF $bmp 6 13 9 13 $Detail
            GF $bmp 1 6 1 9 $Detail; GF $bmp 13 6 13 9 $Detail
            GF $bmp 2 2 3 3 $Body; GF $bmp 12 2 13 3 $Body
            GF $bmp 2 12 3 13 $Body; GF $bmp 12 12 13 13 $Body
        }
        'star' {
            GDiag $bmp 7 0 1 15 $Edge; GDiag $bmp 8 0 14 15 $Edge
            GDiag $bmp 1 15 14 15 $Edge; GDiag $bmp 0 5 15 5 $Edge
            GDiag $bmp 7 1 2 14 $Body; GDiag $bmp 8 1 13 14 $Body
            GDiag $bmp 2 14 13 14 $Body; GDiag $bmp 1 6 14 6 $Body
            GF $bmp 7 4 8 10 $Body
        }
        'crown' {
            GF $bmp 1 12 14 14 $Edge
            GF $bmp 2 13 13 13 $Body
            GF $bmp 2 4 2 11 $Edge; GF $bmp 7 2 7 11 $Edge; GF $bmp 13 3 13 11 $Edge
            GF $bmp 3 7 3 11 $Body; GF $bmp 8 5 8 11 $Body; GF $bmp 14 6 14 11 $Body
            GF $bmp 4 10 6 11 $Detail; GF $bmp 10 10 12 11 $Detail
            GF $bmp 1 3 2 3 $Body; GF $bmp 6 1 8 1 $Body; GF $bmp 13 2 14 2 $Body
        }
        'shield' {
            GR $bmp 1 1 14 5 $Edge
            GDiag $bmp 1 5 7 15 $Edge; GDiag $bmp 14 5 8 15 $Edge
            GR $bmp 3 3 12 5 $Body
            GDiag $bmp 3 5 7 13 $Body; GDiag $bmp 12 5 8 13 $Body
            GF $bmp 7 3 8 9 $Detail
            GF $bmp 5 6 10 7 $Detail
        }
        'globe' {
            GDisc $bmp 7 7 7 $Edge $false
            GDisc $bmp 7 7 6 $Body $false
            GR $bmp 1 1 14 14 $Edge
            GDiag $bmp 2 2 13 13 $Detail; GDiag $bmp 13 2 2 13 $Detail
            GF $bmp 2 7 13 8 $Detail
            GF $bmp 7 1 8 14 $Detail
        }
        'leaf' {
            GDiag $bmp 1 14 13 1 $Edge; GDiag $bmp 2 15 14 2 $Edge
            GDiag $bmp 1 14 13 2 $Body
            GDiag $bmp 4 11 11 4 $Detail
            GF $bmp 7 9 9 12 $Body
            GF $bmp 4 5 4 12 $Edge; GF $bmp 3 12 8 12 $Edge
        }
        'anchor' {
            GDisc $bmp 7 2 2 $Edge $false
            GDisc $bmp 7 2 1 $Body $false
            GF $bmp 7 4 8 15 $Edge
            GF $bmp 8 4 8 14 $Body
            GF $bmp 3 8 11 8 $Edge
            GDiag $bmp 7 14 2 11 $Edge; GDiag $bmp 8 14 13 11 $Edge
            GF $bmp 1 9 3 10 $Body; GF $bmp 12 9 14 10 $Body
        }
        'key' {
            GDisc $bmp 4 4 3 $Edge $false
            GDisc $bmp 4 4 2 $Body $false
            GDisc $bmp 4 4 1 $Edge
            GDiag $bmp 7 7 14 14 $Edge
            GDiag $bmp 8 7 14 13 $Body
            GF $bmp 11 11 13 12 $Edge
            GF $bmp 13 13 15 14 $Edge
        }
        'stamp' {
            GR $bmp 0 1 14 9 $Edge
            GF $bmp 1 2 13 8 $Body
            GF $bmp 3 3 12 3 $Detail; GF $bmp 3 5 10 5 $Detail
            GDiag $bmp 2 8 3 7 $Detail
            GF $bmp 1 11 13 12 $Edge
            GF $bmp 2 13 12 14 $Detail
            GF $bmp 3 13 11 13 $Body
        }
        'book' {
            GF $bmp 1 2 7 14 $Edge; GF $bmp 9 2 15 14 $Edge
            GF $bmp 2 3 7 13 $Body; GF $bmp 9 3 14 13 $Body
            GF $bmp 8 2 8 14 $Detail
            GF $bmp 3 5 6 5 $Detail; GF $bmp 3 8 6 8 $Detail
            GF $bmp 10 5 13 5 $Detail; GF $bmp 10 8 13 8 $Detail
        }
        'diamond' {
            GDiag $bmp 2 2 13 2 $Edge; GDiag $bmp 7 15 2 2 $Edge; GDiag $bmp 8 15 13 2 $Edge
            GDiag $bmp 3 3 12 3 $Body; GDiag $bmp 7 14 3 3 $Body; GDiag $bmp 8 14 12 3 $Body
            GDiag $bmp 5 5 10 5 $Detail; GDiag $bmp 7 12 5 5 $Detail; GDiag $bmp 8 12 10 5 $Detail
        }
        'flame' {
            GDiag $bmp 7 0 2 8 $Edge; GDiag $bmp 8 0 13 8 $Edge
            GDiag $bmp 2 8 7 15 $Edge; GDiag $bmp 13 8 8 15 $Edge
            GDiag $bmp 7 2 3 9 $Body; GDiag $bmp 8 2 12 9 $Body
            GDiag $bmp 3 9 7 14 $Body; GDiag $bmp 12 9 8 14 $Body
            GF $bmp 7 8 8 12 $Detail
        }
        'gear' {
            GDisc $bmp 7 7 6 $Edge $false
            GDisc $bmp 7 7 5 $Body $false
            GDisc $bmp 7 7 2 $Edge
            GF $bmp 7 0 8 1 $Edge; GF $bmp 7 13 8 14 $Edge
            GF $bmp 0 7 1 8 $Edge; GF $bmp 13 7 14 8 $Edge
            GDiag $bmp 2 2 3 3 $Edge; GDiag $bmp 12 2 13 3 $Edge
            GDiag $bmp 2 12 3 13 $Edge; GDiag $bmp 12 12 13 13 $Edge
        }
        'eagle' {
            GDiag $bmp 0 5 7 9 $Edge; GDiag $bmp 15 5 8 9 $Edge
            GDiag $bmp 1 6 7 10 $Body; GDiag $bmp 14 6 8 10 $Body
            GF $bmp 7 4 8 13 $Edge
            GDisc $bmp 7 2 2 $Body
            GF $bmp 7 7 8 12 $Body
            GF $bmp 1 11 4 12 $Detail; GF $bmp 11 11 14 12 $Detail
            GF $bmp 6 14 9 14 $Edge; GF $bmp 6 15 9 15 $Body
        }
        'mrz' {
            GR $bmp 0 1 15 14 $Edge
            GF $bmp 1 2 14 13 $Body
            GF $bmp 3 4 12 4 $Edge; GF $bmp 3 7 12 7 $Edge; GF $bmp 3 10 12 10 $Edge
            GF $bmp 3 12 8 12 $Edge
            GDiag $bmp 10 11 12 13 $Edge; GDiag $bmp 12 11 10 13 $Edge
        }
        default {
            GDisc $bmp 7 7 7 $Edge $false
            GF $bmp 7 2 8 12 $Detail
            GF $bmp 2 7 12 8 $Detail
        }
    }
}

# One crest as its own 64x64 texture: the card is NOT painted, only the glyph, so
# `item/generated` can stack it on top of the flat card (layer1, never tinted).
function New-CrestBitmap($emblemId) {
    $bmp = New-Canvas
    Draw-Glyph $bmp $emblemId
    # drop shadow one texel to the bottom-right of every glyph texel
    $shadow = @()
    for ($gy = 0; $gy -lt $sealSize; $gy++) {
        for ($gx = 0; $gx -lt $sealSize; $gx++) {
            if ($bmp.GetPixel($sealX + $gx, $sealY + $gy).A -ne 0) { $shadow += ,@($gx, $gy) }
        }
    }
    foreach ($cell in $shadow) { GS $bmp ($cell[0] + 1) ($cell[1] + 1) $SealShadow }
    Draw-Glyph $bmp $emblemId
    return $bmp
}

# ---- flat card with a colour already baked in (the legacy names) ------------
function New-LegacyCard($art, [int]$accent) {
    $kind = $kinds | Where-Object { $_.art -eq $art } | Select-Object -First 1
    $card = New-CardBitmap $kind
    $out = New-Canvas
    $cr = ($accent -shr 16) -band 0xFF
    $cg = ($accent -shr 8) -band 0xFF
    $cb = $accent -band 0xFF
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            $p = $card.GetPixel($x, $y)
            if ($p.A -eq 0) { continue }
            $out.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255,
                    [int](($p.R * $cr) / 255), [int](($p.G * $cg) / 255), [int](($p.B * $cb) / 255)))
        }
    }
    $card.Dispose()
    # the seal of an official layout is the default crest
    $crest = New-CrestBitmap $defaultEmblem
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            $c = $crest.GetPixel($x, $y)
            if ($c.A -ne 0) { $out.SetPixel($x, $y, $c) }
        }
    }
    $crest.Dispose()
    return $out
}

# ---- json helpers ----------------------------------------------------------
function Write-Json([string]$path, $value) {
    $json = $value | ConvertTo-Json -Depth 12
    # deterministic, readable: LF line endings, no BOM
    $text = ($json -replace "`r`n", "`n") + "`n"
    [System.IO.File]::WriteAllText($path, $text, [System.Text.UTF8Encoding]::new($false))
}

# ---- paint ----------------------------------------------------------------
$fixes = 0
if (-not $ModelsOnly) {
    foreach ($kind in $kinds) {
        $card = New-CardBitmap $kind
        $fixes += Save-Png $card ($kind.art + "_flat")
        $card.Dispose()

        $frame = New-CardBitmap $kind -Frame
        $fixes += Save-Png $frame ($kind.art + "_frame")
        $frame.Dispose()
    }
    foreach ($id in $emblems) {
        $crest = New-CrestBitmap $id
        $fixes += Save-Png $crest ("emblem_" + $id)
        $crest.Dispose()
    }
    foreach ($legacy in $legacyCards) {
        $bmp = New-LegacyCard $legacy.art $legacy.accent
        $fixes += Save-Png $bmp $legacy.name
        $bmp.Dispose()
    }
    Write-Host ''
    Write-Host ("== painted {0} flat card(s), {1} crest(s), {2} frame(s), {3} legacy card(s) ==" -f `
            $kinds.Count, $emblems.Count, $kinds.Count, $legacyCards.Count)
    Write-Host ("   alpha pixels hardened on save: {0}" -f $fixes)
}

# ---- models ---------------------------------------------------------------
# Layer map of one credential model:
#   layer0  <art>_flat        the tintable card  (tintIndex 0)
#   layer1  emblem_<id>       the crest          (tintIndex 1 -> ItemColors -1)
foreach ($kind in $kinds) {
    $art = $kind.art
    Write-Json (Join-Path $itemModels ("doc_layers_" + $art + ".json")) ([ordered]@{
            parent   = 'minecraft:item/generated'
            textures = [ordered]@{
                layer0 = "veriborder:item/$($art)_flat"
                layer1 = "veriborder:item/emblem_$defaultEmblem"
            }
        })
}

foreach ($kind in $kinds) {
    $art = $kind.art
    $item = $kind.doc
    foreach ($id in $emblems) {
        if ($id -eq $defaultEmblem) { continue }
        Write-Json (Join-Path $itemModels ("$($item)_emblem_" + $id + ".json")) ([ordered]@{
                parent   = "veriborder:item/doc_layers_$art"
                textures = [ordered]@{ layer1 = "veriborder:item/emblem_$id" }
            })
    }
}
Write-Json (Join-Path $itemModels 'custom_id_card.json') ([ordered]@{ parent = 'veriborder:item/doc_layers_id_card' })
Write-Json (Join-Path $itemModels 'custom_passport.json') ([ordered]@{ parent = 'veriborder:item/doc_layers_passport' })
Write-Json (Join-Path $itemModels 'custom_permit.json') ([ordered]@{ parent = 'veriborder:item/doc_layers_permit' })

# Item-model overrides. Order matters: the item model resolves the LAST matching
# entry, so the crest variants come first, the eight base-map slots next and the
# custom-cover model (`custom_model_data = 100`) last - a custom cover therefore
# keeps its own model no matter which crest or slot the document carries.
foreach ($kind in $kinds) {
    $art = $kind.art
    $item = $kind.doc
    $custom = if ($art -eq 'passport') { 'custom_passport' } elseif ($art -eq 'permit') { 'custom_permit' } else { 'custom_id_card' }
    $overrides = [System.Collections.Generic.List[object]]::new()
    $crestIndex = 0
    foreach ($id in $emblems) {
        if ($id -eq $defaultEmblem) { $crestIndex++; continue }
        $overrides.Add([ordered]@{
                predicate = [ordered]@{ 'veriborder:emblem' = [double]$crestIndex / 16.0 }
                model     = "veriborder:item/$($item)_emblem_$id"
            })
        $crestIndex++
    }
    for ($slot = 0; $slot -lt 8; $slot++) {
        $overrides.Add([ordered]@{
                predicate = [ordered]@{ 'veriborder:texslot' = $slot }
                model     = "veriborder:item/$($item)_slot_$slot"
            })
    }
    $overrides.Add([ordered]@{
            predicate = [ordered]@{ custom_model_data = 100 }
            model     = "veriborder:item/$custom"
        })
    Write-Json (Join-Path $itemModels ("$item.json")) ([ordered]@{
            parent    = "veriborder:item/doc_layers_$art"
            overrides = $overrides
        })
}

# The eight base-map slots are builtin/entity so the custom renderer can blit the
# uploaded picture (a baked model may only sample atlas textures). The display
# block is the standard flat-item transform set.
foreach ($kind in $kinds) {
    $item = $kind.doc
    $art = $kind.art
    for ($slot = 0; $slot -lt 8; $slot++) {
        Write-Json (Join-Path $itemModels ("$($item)_slot_$slot.json")) ([ordered]@{
                parent    = 'builtin/entity'
                gui_light = 'front'
                display   = [ordered]@{
                    ground                = [ordered]@{ rotation = @(0, 0, 0); translation = @(0, 2, 0); scale = @(0.5, 0.5, 0.5) }
                    head                  = [ordered]@{ rotation = @(0, 180, 0); translation = @(0, 13, 7); scale = @(1, 1, 1) }
                    thirdperson_righthand = [ordered]@{ rotation = @(0, 0, 0); translation = @(0, 3, 1); scale = @(0.55, 0.55, 0.55) }
                    firstperson_righthand = [ordered]@{ rotation = @(0, -90, 25); translation = @(1.13, 3.2, 1.13); scale = @(0.68, 0.68, 0.68) }
                    fixed                 = [ordered]@{ rotation = @(0, 180, 0); scale = @(1, 1, 1) }
                }
                textures  = [ordered]@{ particle = "veriborder:item/$($art)_flat" }
            })
    }
}

# The seven legacy style models still name their own flattened texture, so they
# keep working unchanged (older resource packs and the creative tab sample them).
# hk_macao_permit.json is NOT one of them: it is the credential model above.
$legacyModels = @('id_card_cn', 'id_card_hk', 'passport_cn', 'passport_hk', 'passport_mo',
    'passport_us', 'passport_jp')
foreach ($name in $legacyModels) {
    Write-Json (Join-Path $itemModels ("$name.json")) ([ordered]@{
            parent   = 'minecraft:item/generated'
            textures = [ordered]@{ layer0 = "veriborder:item/$name" }
        })
}

Write-Host ''
Write-Host ("== wrote {0} crest variant model(s), 3 base models, 3 custom models, 24 slot models ==" -f `
        (($emblems.Count - 1) * 3))
Write-Host 'OK: document cards regenerated (64x64 flat tintable cards + crest overlay)'
