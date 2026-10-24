[CmdletBinding()]
param(
    # Optional output root: a directory containing src\main\resources. The
    # default is the project this script lives in. tools/check_generators.ps1
    # regenerates into a scratch tree through this parameter, so the
    # "rerun produces no diff" gate never touches the working copy.
    [string]$Root,
    # Skip the document card textures; they belong to generate_item_textures.ps1
    # (which also runs the ink audit). tools/generate.ps1 uses this to keep the
    # "devices" and "items" generators independent.
    [switch]$NoDocuments
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

# ---------------------------------------------------------------------------
# VeriBorder asset generator (idempotent: same input -> same bytes)
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_assets.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_assets.ps1 -Root D:\scratch
# Regenerates: 20 device texture sets (12 PNGs each; the two gates add sign,
# cyan, arrow and tablet -> 16), 20 bespoke block models, 20 blockstates, 20
# item models, 20 loot tables, entry + exit E-Gate entity textures, the device
# brush icon, document card textures, modifier icon and the mod logo.
# Recipes are separate: tools/generate_recipes.ps1
# Everything at once:  tools/generate.ps1  (dispatcher; -Only devices|items|recipes)
# Drift gate:          powershell -File .\tools\check_generators.ps1
# Preview/validation:  powershell -File .\tools\preview_models.ps1
# ---------------------------------------------------------------------------

$root = if ($Root) { (Resolve-Path -LiteralPath $Root).Path } else { Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path) }
$assets = Join-Path $root 'src\main\resources\assets\veriborder'
$data = Join-Path $root 'src\main\resources\data\veriborder'
$blockTex = Join-Path $assets 'textures\block'
$itemTex = Join-Path $assets 'textures\item'
$entityTex = Join-Path $assets 'textures\entity'
$blockModels = Join-Path $assets 'models\block'
$itemModels = Join-Path $assets 'models\item'
$blockstates = Join-Path $assets 'blockstates'
$loot = Join-Path $data 'loot_tables\blocks'
$tags = Join-Path $root 'src\main\resources\data\minecraft\tags\blocks\mineable'
$needs = Join-Path $root 'src\main\resources\data\minecraft\tags\blocks'
foreach ($dir in @($assets, $data, $blockTex, $itemTex, $entityTex, $blockModels, $itemModels, $blockstates, $loot, $tags, $needs)) {
    New-Item -ItemType Directory -Force $dir | Out-Null
}

$utf8 = [System.Text.UTF8Encoding]::new($false)
$inv = [System.Globalization.CultureInfo]::InvariantCulture

function Write-Text([string]$path, [string]$text) {
    [System.IO.File]::WriteAllText($path, $text, $utf8)
}
function Write-Json([string]$path, $object) {
    Write-Text $path ($object | ConvertTo-Json -Depth 30)
}
# invariant number formatting: models are written with a hand-rolled serializer
function N($value) {
    $d = [double]$value
    if ([Math]::Abs($d - [Math]::Round($d)) -lt 0.0001) {
        return ([long][Math]::Round($d)).ToString($inv)
    }
    return ([Math]::Round($d, 3)).ToString('0.###', $inv)
}
function C([int]$r, [int]$g, [int]$b, [int]$a = 255) {
    return [System.Drawing.Color]::FromArgb($a, $r, $g, $b)
}
function Blend([System.Drawing.Color]$a, [System.Drawing.Color]$b, [double]$t) {
    if ($t -lt 0) { $t = 0 }
    if ($t -gt 1) { $t = 1 }
    return C ([int]($a.R * (1 - $t) + $b.R * $t)) ([int]($a.G * (1 - $t) + $b.G * $t)) `
        ([int]($a.B * (1 - $t) + $b.B * $t)) ([int]($a.A * (1 - $t) + $b.A * $t))
}
function New-Bitmap([int]$w, [int]$h) {
    $bmp = [System.Drawing.Bitmap]::new($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::SingleBitPerPixelGridFit
    $g.Clear([System.Drawing.Color]::Transparent)
    return @($bmp, $g)
}
# document cards / logo keep the original GDI+ text rendering (AntiAliasGridFit)
function New-BitmapSmooth([int]$w, [int]$h) {
    $bmp = [System.Drawing.Bitmap]::new($w, $h, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::None
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.Color]::Transparent)
    return @($bmp, $g)
}
# Item icons must be BINARY in alpha. Iris/OptiFine treat any 0 < alpha < 255 as
# a translucent draw: an anti-aliased icon edge then blends with whatever sits
# behind it (light bleed / halos at the silhouette) and drops the whole icon into
# the sorted translucent pass, which is what makes items look see-through under
# shaders. GDI+ anti-aliasing leaves exactly those feathered edges, so every
# texture written below textures\item is flattened on save:
#   alpha <  128 -> fully transparent (0)
#   alpha >= 128 -> fully opaque     (255)
# RGB is kept as drawn (the card/icon art uses straight alpha, so no dark halo).
# Block textures are deliberately untouched: glass/leaf/tablet are designed
# translucent and their render layer is chosen by the block, not by the texture.
function Set-HardAlpha([System.Drawing.Bitmap]$bmp) {
    $flat = [System.Drawing.Bitmap]::new($bmp.Width, $bmp.Height, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        for ($x = 0; $x -lt $bmp.Width; $x++) {
            $p = $bmp.GetPixel($x, $y)
            if ($p.A -eq 0 -or $p.A -eq 255) { $flat.SetPixel($x, $y, $p); continue }
            $a = if ($p.A -ge 128) { 255 } else { 0 }
            $flat.SetPixel($x, $y, [System.Drawing.Color]::FromArgb($a, $p.R, $p.G, $p.B))
        }
    }
    $bmp.Dispose()
    return $flat
}
function Save-Bitmap($bmp, $g, [string]$path) {
    $g.Dispose()
    if ($path -like '*\textures\item\*') { $bmp = Set-HardAlpha $bmp }
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}
function Font([double]$size, [bool]$bold = $true) {
    $style = if ($bold) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
    return [System.Drawing.Font]::new('Arial', $size, $style, [System.Drawing.GraphicsUnit]::Pixel)
}
function Brush([System.Drawing.Color]$color) {
    return [System.Drawing.SolidBrush]::new($color)
}
function Pen([System.Drawing.Color]$color, [double]$width = 1) {
    return [System.Drawing.Pen]::new($color, $width)
}
function Fill-Rect($g, [int]$x, [int]$y, [int]$w, [int]$h, [System.Drawing.Color]$color) {
    $b = Brush $color
    $g.FillRectangle($b, $x, $y, $w, $h)
    $b.Dispose()
}
function Draw-Line($g, [int]$x1, [int]$y1, [int]$x2, [int]$y2, [System.Drawing.Color]$color, [double]$width = 1) {
    $p = Pen $color $width
    $g.DrawLine($p, $x1, $y1, $x2, $y2)
    $p.Dispose()
}
function Draw-TextCentered($g, [string]$text, [System.Drawing.Font]$font, [System.Drawing.Color]$color, [int]$x, [int]$y, [int]$w) {
    $b = Brush $color
    $sf = [System.Drawing.StringFormat]::new()
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $g.DrawString($text, $font, $b, [System.Drawing.RectangleF]::new($x, $y, $w, 20), $sf)
    $sf.Dispose(); $b.Dispose()
}
function Draw-Text($g, [string]$text, [System.Drawing.Font]$font, [System.Drawing.Color]$color, [int]$x, [int]$y) {
    $b = Brush $color
    $g.DrawString($text, $font, $b, $x, $y)
    $b.Dispose()
}
# Largest whole-pixel Arial bold size in [$minSize, $maxSize] whose advance width
# still fits $maxWidth. Card headings use it so they can never outgrow the card
# face: the old fixed Font 7 headings measured 52 px ("IDENTITY CARD") and 69 px
# ("EXIT-ENTRY PERMIT") in a 56 px card, i.e. 12-29 px past the 64 px canvas.
function Get-FitFontSize($g, [string]$text, [double]$maxSize, [double]$minSize, [int]$maxWidth) {
    for ($size = [int]$maxSize; $size -gt [int]$minSize; $size--) {
        $font = Font $size
        $measure = $g.MeasureString($text, $font, [System.Drawing.PointF]::new(0, 0), [System.Drawing.StringFormat]::GenericTypographic)
        $font.Dispose()
        if ($measure.Width -le $maxWidth) { return $size }
    }
    return [int]$minSize
}
function UVFit([double]$w, [double]$h) {
    $m = [Math]::Max($w, $h)
    $u = [Math]::Round($w * 16.0 / $m, 1)
    $v = [Math]::Round($h * 16.0 / $m, 1)
    return @($u, $v)
}

$script:DISPLAY = '"display":{"thirdperson_righthand":{"rotation":[75,45,0],"translation":[0,2.5,0],"scale":[0.375,0.375,0.375]},"thirdperson_lefthand":{"rotation":[75,45,0],"translation":[0,2.5,0],"scale":[0.375,0.375,0.375]},"firstperson_righthand":{"rotation":[0,45,0],"scale":[0.4,0.4,0.4]},"firstperson_lefthand":{"rotation":[0,45,0],"scale":[0.4,0.4,0.4]},"gui":{"rotation":[30,225,0],"scale":[0.625,0.625,0.625]},"ground":{"translation":[0,3,0],"scale":[0.25,0.25,0.25]},"fixed":{"rotation":[0,180,0],"scale":[0.5,0.5,0.5]}}'

# ---- device definitions ---------------------------------------------------
# base/base2/trim/accent/fg/bg form the per-device palette (all light bodies).
# s1/s2 are the primary/secondary screen face sizes in model units; the screen
# textures are generated at exactly 4 px per UV unit of that aspect.
$devices = @(
    @{ id = 'id_issuer'; base = '#EAF1F6'; base2 = '#D3E3EE'; trim = '#465A67'; accent = '#2F7FA8'; fg = '#8FE3FF'; bg = '#071B29'; variant = 0; style = 'idIssue'; s1 = @(8, 5); s2 = @(1.8, 1.125) },
    @{ id = 'passport_issuer'; base = '#F4EFE2'; base2 = '#E5D8BD'; trim = '#5A5142'; accent = '#B8892F'; fg = '#F7D98A'; bg = '#191306'; variant = 1; style = 'passport'; s1 = @(6, 3.75); s2 = @(2, 1.25) },
    @{ id = 'permit_issuer'; base = '#E3EEF6'; base2 = '#C9DEEA'; trim = '#3E5563'; accent = '#2C8FB5'; fg = '#9BE7FF'; bg = '#061B26'; variant = 2; style = 'permit'; s1 = @(8, 5); s2 = @(2, 1.25) },
    @{ id = 'visa_issuer'; base = '#EEEAF8'; base2 = '#DCD3F0'; trim = '#4C4560'; accent = '#7C6AF2'; fg = '#CFC2FF'; bg = '#120C29'; variant = 3; style = 'visa'; s1 = @(4.4, 5); s2 = @(2.2, 1.375) },
    @{ id = 'endorsement_machine'; base = '#E6EEF3'; base2 = '#CFDDE8'; trim = '#46545E'; accent = '#5F7FE0'; fg = '#B9D0FF'; bg = '#0A1428'; variant = 0; style = 'endorse'; s1 = @(7, 4.5); s2 = @(3, 1.875) },
    @{ id = 'hotel_reader'; base = '#F0F5F4'; base2 = '#D8E9E6'; trim = '#3F5A57'; accent = '#2FC6B7'; fg = '#9BFFF2'; bg = '#062421'; variant = 1; style = 'hotel'; s1 = @(7.6, 3.2); s2 = @(2.4, 1.5) },
    @{ id = 'desk_scanner'; base = '#E8EDF0'; base2 = '#D2DADF'; trim = '#3E4C55'; accent = '#1FA89C'; fg = '#8FF0E4'; bg = '#061F22'; variant = 2; style = 'scanner'; s1 = @(4, 2.5); s2 = @(2.4, 1.5) },
    @{ id = 'manual_terminal'; base = '#E9EDF0'; base2 = '#D3DBE1'; trim = '#414D57'; accent = '#2FC6B7'; fg = '#9BE9FF'; bg = '#071C22'; variant = 3; style = 'manual'; s1 = @(9.8, 6.4); s2 = @(3, 1.875) },
    @{ id = 'airport_checkin'; base = '#E6EFF8'; base2 = '#CFE1F2'; trim = '#3B5366'; accent = '#3E8FC4'; fg = '#9FD4FF'; bg = '#081A2A'; variant = 0; style = 'airport'; s1 = @(8.2, 4.4); s2 = @(3, 1.875) },
    @{ id = 'station_verifier'; base = '#EAF0F1'; base2 = '#D4E2E4'; trim = '#3F5255'; accent = '#1FA89C'; fg = '#9BFFF2'; bg = '#061E20'; variant = 1; style = 'station'; s1 = @(6.8, 2.4); s2 = @(2.4, 1.5) },
    @{ id = 'egate'; base = '#F2F4F3'; base2 = '#E2E6E4'; trim = '#1F242B'; accent = '#22B8D6'; fg = '#A9F6FF'; bg = '#041A20'; variant = 2; style = 'egate'; s1 = @(3.6, 5.2); s2 = @(2.4, 3.2); gate = $true; sign = 'ENTRY' },
    @{ id = 'entry_stamp'; base = '#F3EBDD'; base2 = '#E7D6BC'; trim = '#5B4C3A'; accent = '#D98A2B'; fg = '#FFD9A0'; bg = '#1C1206'; variant = 3; style = 'entry'; s1 = @(4, 2.5); s2 = @(2.6, 1.625) },
    @{ id = 'exit_stamp'; base = '#E7ECF2'; base2 = '#CDD8E4'; trim = '#3E4A57'; accent = '#3D6FA8'; fg = '#A9D4FF'; bg = '#08131F'; variant = 0; style = 'exit'; s1 = @(3.6, 2.4); s2 = @(2.6, 1.625) },
    @{ id = 'stamp_history'; base = '#EFE8DA'; base2 = '#DFD2BA'; trim = '#574C3C'; accent = '#C08A36'; fg = '#FFE3A8'; bg = '#1A1408'; variant = 1; style = 'history'; s1 = @(9.2, 4.6); s2 = @(3, 1.875) },
    @{ id = 'loss_terminal'; base = '#F5E9E9'; base2 = '#EAD2D2'; trim = '#5C3F3F'; accent = '#D9534F'; fg = '#FFB4AE'; bg = '#200808'; variant = 2; style = 'loss'; s1 = @(8, 5); s2 = @(3, 1.875) },
    @{ id = 'renewal_machine'; base = '#F6EFE3'; base2 = '#E8DAC4'; trim = '#5A4E3E'; accent = '#C98A3E'; fg = '#FFD9A0'; bg = '#1B1408'; variant = 3; style = 'renewal'; s1 = @(3.9, 5.2); s2 = @(3, 1.875) },
    @{ id = 'print_preview'; base = '#EDEDEA'; base2 = '#DADAD4'; trim = '#4A4A45'; accent = '#D9534F'; fg = '#FFB4AE'; bg = '#141414'; variant = 0; style = 'print'; s1 = @(7, 4); s2 = @(3, 1.875) },
    @{ id = 'blacklist_terminal'; base = '#E4E7EA'; base2 = '#CCD2D8'; trim = '#3A4149'; accent = '#C9393F'; fg = '#FF9E9E'; bg = '#1A0608'; variant = 1; style = 'blacklist'; s1 = @(8.8, 5.6); s2 = @(3, 1.875) },
    # --- round 2: exit-side gate (same split-pillar envelope, EXIT theme) ---
    @{ id = 'exit_gate'; base = '#F4F6F5'; base2 = '#E4E8E6'; trim = '#191D23'; accent = '#22C3D8'; fg = '#A9F6FF'; bg = '#03181E'; variant = 1; style = 'egateExit'; s1 = @(3.6, 5.2); s2 = @(2.4, 3.2); gate = $true; sign = 'EXIT' },
    # --- round 2: self-service endorsement kiosk (violet, tall) ---
    @{ id = 'self_endorsement'; base = '#F0EAF8'; base2 = '#DCD0F0'; trim = '#4B3F63'; accent = '#8A5CF0'; fg = '#D8C6FF'; bg = '#120A26'; variant = 3; style = 'selfEndorse'; s1 = @(5, 6.6); s2 = @(3, 1.875) },
    # --- 1.2.0: self-service visa kiosk (blue, low desk) ---
    @{ id = 'self_visa'; base = '#E9F3FA'; base2 = '#D2E4F0'; trim = '#3C5263'; accent = '#3E8FC4'; fg = '#A9D8FF'; bg = '#071A2A'; variant = 2; style = 'selfVisa'; s1 = @(5, 4.2); s2 = @(2.6, 1.6) }
)

foreach ($d in $devices) {
    $d.uv1 = UVFit $d.s1[0] $d.s1[1]
    $d.uv2 = UVFit $d.s2[0] $d.s2[1]
    $d.px1 = @([int][Math]::Round($d.uv1[0] * 4), [int][Math]::Round($d.uv1[1] * 4))
    $d.px2 = @([int][Math]::Round($d.uv2[0] * 4), [int][Math]::Round($d.uv2[1] * 4))
}

# The libraries live next to this script, not under the output root: -Root only
# redirects generated artefacts.
. (Join-Path $PSScriptRoot 'lib_device_textures.ps1')
. (Join-Path $PSScriptRoot 'lib_device_models.ps1')

$tagValues = @()
foreach ($d in $devices) {
    $tagValues += "veriborder:$($d.id)"
    New-DeviceTextures $d
    $script:DEV = $d
    New-BlockModel $d
    # blockstate: every device is horizontal-facing; the E-Gate also has OPEN
    $variants = [ordered]@{}
    $map = [ordered]@{ north = 0; east = 90; south = 180; west = 270 }
    foreach ($entry in $map.GetEnumerator()) {
        if ($d.gate) {
            foreach ($open in @('false', 'true')) {
                # closed gates use the model WITH static door leaves; open=true
                # uses the matching _open shell without leaves
                $modelId = if ($open -eq 'true') { "$($d.id)_open" } else { $d.id }
                $variants["facing=$($entry.Key),open=$open"] = [ordered]@{
                    model = "veriborder:block/$modelId"; y = $entry.Value
                }
            }
        } else {
            $variants["facing=$($entry.Key)"] = [ordered]@{ model = "veriborder:block/$($d.id)"; y = $entry.Value }
        }
    }
    Write-Json (Join-Path $blockstates "$($d.id).json") ([ordered]@{ variants = $variants })
    if ($d.gate) {
        # the gate model is 2 blocks wide, so its item form needs smaller display
        # transforms than the 16 px machines or it clips the inventory slot
        Write-Json (Join-Path $itemModels "$($d.id).json") ([ordered]@{
            parent = "veriborder:block/$($d.id)"
            # a block model defaults to gui_light "side"; the item form wants the
            # head-on GUI lighting vanilla item models use, otherwise the front
            # faces go dark and the shading steps read as internal faces
            gui_light = 'front'
            display = [ordered]@{
                thirdperson_righthand = [ordered]@{ rotation = @(75, 45, 0); translation = @(0, 2.5, 0); scale = @(0.2, 0.2, 0.2) }
                thirdperson_lefthand = [ordered]@{ rotation = @(75, 45, 0); translation = @(0, 2.5, 0); scale = @(0.2, 0.2, 0.2) }
                firstperson_righthand = [ordered]@{ rotation = @(0, 45, 0); scale = @(0.22, 0.22, 0.22) }
                firstperson_lefthand = [ordered]@{ rotation = @(0, 45, 0); scale = @(0.22, 0.22, 0.22) }
                gui = [ordered]@{ rotation = @(30, 225, 0); scale = @(0.32, 0.32, 0.32) }
                ground = [ordered]@{ translation = @(0, 3, 0); scale = @(0.14, 0.14, 0.14) }
                fixed = [ordered]@{ rotation = @(0, 180, 0); scale = @(0.26, 0.26, 0.26) }
            }
        })
    } else {
        Write-Json (Join-Path $itemModels "$($d.id).json") ([ordered]@{ parent = "veriborder:block/$($d.id)"; gui_light = 'front' })
    }
    Write-Json (Join-Path $loot "$($d.id).json") ([ordered]@{
        type = 'minecraft:block'
        pools = @([ordered]@{
            rolls = 1
            bonus_rolls = 0
            entries = @([ordered]@{ type = 'minecraft:item'; name = "veriborder:$($d.id)" })
            conditions = @([ordered]@{ condition = 'minecraft:survives_explosion' })
        })
    })
}

Write-Json (Join-Path $tags 'pickaxe.json') ([ordered]@{ replace = $false; values = $tagValues })
Write-Json (Join-Path $needs 'needs_stone_tool.json') ([ordered]@{ replace = $false; values = $tagValues })

# ---- E-Gate entity textures (entry gate + exit gate) ----------------------
# EGateRenderer box unwraps are fixed; only these regions are sampled:
#   door  (texOffs 0,0 / 16,0, box 6x10x1) -> north (u+1,v+1,6x10),
#                                             south (u+7,v+1,6x10)
#   light (texOffs 0,32, box 2.8x0.46x0.1) -> north (u+1,v+1,2.8x0.46)
#   scan  (texOffs 0,38, box 12x1x1.2)     -> north (u+1,v+1,12x1)
# GDI+ Graphics.Clear ignores the alpha channel, so these are composed in a raw
# BGRA buffer and written with LockBits to keep real PNG transparency.
function New-Rgba([int]$w, [int]$h) {
    $arr = [byte[]]::new($w * $h * 4)
    return ,$arr
}
function Rgba-Fill([byte[]]$buf, [int]$BufW, [int]$BufH, [int]$x0, [int]$y0, [int]$rw, [int]$rh, [int]$cr, [int]$cg, [int]$cb, [int]$ca) {
    if ($ca -le 0) { return }
    $xa = [Math]::Max(0, $x0); $ya = [Math]::Max(0, $y0)
    $xb = [Math]::Min($BufW, $x0 + $rw); $yb = [Math]::Min($BufH, $y0 + $rh)
    $sa = $ca / 255.0
    for ($y = $ya; $y -lt $yb; $y++) {
        for ($x = $xa; $x -lt $xb; $x++) {
            $i = ($y * $BufW + $x) * 4
            if ($ca -ge 255) {
                $buf[$i] = [byte]$cb; $buf[$i + 1] = [byte]$cg; $buf[$i + 2] = [byte]$cr; $buf[$i + 3] = 255
            } else {
                $da = $buf[$i + 3] / 255.0
                $oa = $sa + $da * (1 - $sa)
                if ($oa -le 0.0001) { continue }
                $buf[$i] = [byte][Math]::Round(($cb * $sa + $buf[$i] * $da * (1 - $sa)) / $oa)
                $buf[$i + 1] = [byte][Math]::Round(($cg * $sa + $buf[$i + 1] * $da * (1 - $sa)) / $oa)
                $buf[$i + 2] = [byte][Math]::Round(($cr * $sa + $buf[$i + 2] * $da * (1 - $sa)) / $oa)
                $buf[$i + 3] = [byte][Math]::Round($oa * 255)
            }
        }
    }
}
function Save-Rgba([byte[]]$buf, [int]$W, [int]$H, [string]$path) {
    $bmp = [System.Drawing.Bitmap]::new($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $rect = [System.Drawing.Rectangle]::new(0, 0, $W, $H)
    $bd = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::WriteOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    [System.Runtime.InteropServices.Marshal]::Copy($buf, 0, $bd.Scan0, $buf.Length)
    $bmp.UnlockBits($bd)
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

# one 4x18 framed-glass leaf: opaque metal frame + four semi-transparent panes
function Draw-DoorLeaf([byte[]]$buf, [int]$x0, [int]$fr, [int]$fg, [int]$fb, [int]$gr, [int]$gg, [int]$gb) {
    Rgba-Fill $buf 64 64 $x0 1 4 2 $fr $fg $fb 255          # top rail
    Rgba-Fill $buf 64 64 $x0 18 4 1 $fr $fg $fb 255         # bottom rail
    Rgba-Fill $buf 64 64 $x0 3 1 16 $fr $fg $fb 255         # left stile
    Rgba-Fill $buf 64 64 ($x0 + 3) 3 1 16 $fr $fg $fb 255   # right stile
    Rgba-Fill $buf 64 64 $x0 10 4 1 $fr $fg $fb 255         # mullion
    for ($i = 0; $i -lt 7; $i++) {
        Rgba-Fill $buf 64 64 ($x0 + 1) (3 + $i) 2 1 $gr $gg $gb (54 + $i * 5)
        Rgba-Fill $buf 64 64 ($x0 + 1) (11 + $i) 2 1 $gr $gg $gb (62 + $i * 5)
    }
    Rgba-Fill $buf 64 64 ($x0 + 1) 3 1 6 255 255 255 44
    Rgba-Fill $buf 64 64 ($x0 + 2) 4 1 5 255 255 255 30
    Rgba-Fill $buf 64 64 ($x0 + 1) 11 1 6 255 255 255 40
    Rgba-Fill $buf 64 64 $x0 11 1 2 120 132 140 255         # lock block
}
function New-GateEntityTextures([string]$prefix, [int]$fr, [int]$fg, [int]$fb, [int]$gr, [int]$gg, [int]$gb, [int]$sr, [int]$sg, [int]$sb) {
    [byte[]]$door = New-Rgba 64 64
    # four leaf faces: front/back of the two 4x18x1 wings
    foreach ($x0 in @(1, 6, 13, 18)) { Draw-DoorLeaf $door $x0 $fr $fg $fb $gr $gg $gb }
    # edge strips of both boxes (west / east / cap / bottom faces)
    Rgba-Fill $door 64 64 0 1 1 18 $fr $fg $fb 255
    Rgba-Fill $door 64 64 5 1 1 18 $fr $fg $fb 255
    Rgba-Fill $door 64 64 12 1 1 18 $fr $fg $fb 255
    Rgba-Fill $door 64 64 17 1 1 18 $fr $fg $fb 255
    Rgba-Fill $door 64 64 1 0 4 1 $fr $fg $fb 255
    Rgba-Fill $door 64 64 5 0 4 1 $fr $fg $fb 255
    Rgba-Fill $door 64 64 13 0 4 1 $fr $fg $fb 255
    Rgba-Fill $door 64 64 17 0 4 1 $fr $fg $fb 255
    # faint glass wash so mipmaps stay glassy instead of pulling transparent black
    Rgba-Fill $door 64 64 0 20 64 16 $gr $gg $gb 12
    Save-Rgba $door 64 64 (Join-Path $entityTex "${prefix}_door.png")

    foreach ($light in @(@('green', 55, 230, 145), @('amber', 245, 190, 65), @('red', 245, 75, 75))) {
        [byte[]]$buf = New-Rgba 64 64
        Rgba-Fill $buf 64 64 1 33 8 1 $light[1] $light[2] $light[3] 255
        Rgba-Fill $buf 64 64 1 32 8 1 $light[1] $light[2] $light[3] 90
        Rgba-Fill $buf 64 64 1 34 8 1 $light[1] $light[2] $light[3] 90
        Rgba-Fill $buf 64 64 1 35 8 1 $light[1] $light[2] $light[3] 40
        Rgba-Fill $buf 64 64 2 33 2 1 255 255 255 235
        Rgba-Fill $buf 64 64 5 33 2 1 255 255 255 150
        Save-Rgba $buf 64 64 (Join-Path $entityTex "${prefix}_light_$($light[0]).png")
    }

    [byte[]]$scan = New-Rgba 64 64
    Rgba-Fill $scan 64 64 1 39 8 1 $sr $sg $sb 240
    Rgba-Fill $scan 64 64 1 38 8 1 $sr $sg $sb 120
    Rgba-Fill $scan 64 64 1 40 8 1 $sr $sg $sb 80
    Rgba-Fill $scan 64 64 2 39 2 1 255 255 255 240
    Save-Rgba $scan 64 64 (Join-Path $entityTex "${prefix}_scan.png")
}

# entry gate: cool silver frame + cyan glass
New-GateEntityTextures 'egate' 198 212 219 140 204 224 90 230 236
# exit gate: dark bronze frame + warm amber glass and scan
New-GateEntityTextures 'exit_gate' 120 86 70 255 196 130 255 190 90

# The BER leaves are 6 x 10 x 1 px boxes: each unwrap covers north (u+1,v+1,
# 6x10), south (u+7,v+1,6x10), 1 px east/west edge strips at u+0, and 6 x 1
# up/down caps at v+0. Left leaf uses texOffs(0,0), right leaf texOffs(16,0),
# so the four framed-glass panels are drawn at x = 1, 7, 17, 23 with the metal
# edge islands at x = 0 and 16. The leaves keep real alpha in the panes so the
# passage stays visible through the closed door.
# The BER leaves are 6 x 10 x 1 px boxes: each unwrap covers north (u+1,v+1,
# 6x10), south (u+7,v+1,6x10), 1 px east/west edge strips at u+0, and 6 x 1
# up/down caps at v+0. Left leaf uses texOffs(0,0), right leaf texOffs(16,0),
# so the four leaf faces are drawn at x = 1, 7, 17, 23 with metal edge islands
# at x = 0 and 16. The panes stay translucent (a real frosted cyan tint) while
# the baked 1 px frame + cyan edge line read as a framed door even at distance;
# EGateRenderer additionally draws opaque frame bars as solid geometry.
function New-GateBladeTexture([string]$path, [int]$fr, [int]$fg, [int]$fb, [int]$gr, [int]$gg, [int]$gb) {
    [byte[]]$buf = New-Rgba 64 64
    foreach ($x0 in @(1, 7, 17, 23)) {
        # glass body (frosted cyan tint, clearly translucent)
        Rgba-Fill $buf 64 64 $x0 1 6 10 $gr $gg $gb 172
        # diagonal frost band across the pane
        for ($i = 0; $i -lt 4; $i++) {
            Rgba-Fill $buf 64 64 ($x0 + 1 + $i) (2 + $i) 2 6 $gr $gg $gb 218
            Rgba-Fill $buf 64 64 ($x0 + 1 + $i) (2 + $i) 1 6 235 252 255 165
        }
        # dark metal frame: 1 px all round, plus mid rail and lock plate
        Rgba-Fill $buf 64 64 $x0 1 6 1 $fr $fg $fb 255
        Rgba-Fill $buf 64 64 $x0 10 6 1 $fr $fg $fb 255
        Rgba-Fill $buf 64 64 $x0 1 1 10 $fr $fg $fb 255
        Rgba-Fill $buf 64 64 ($x0 + 5) 1 1 10 $fr $fg $fb 255
        Rgba-Fill $buf 64 64 ($x0 + 1) 5 4 1 $fr $fg $fb 255
        Rgba-Fill $buf 64 64 ($x0 + 4) 3 2 2 $fr $fg $fb 255
        # cyan edge light under the top rail and a lock indicator
        Rgba-Fill $buf 64 64 ($x0 + 1) 2 4 1 96 232 250 255
        Rgba-Fill $buf 64 64 ($x0 + 4) 4 1 1 180 255 255 255
        # metal highlight on the stiles
        Rgba-Fill $buf 64 64 ($x0 + 1) 3 1 6 255 255 255 45
        # cap islands (up / down faces) at v+0
        Rgba-Fill $buf 64 64 $x0 0 6 1 $fr $fg $fb 255
    }
    # east / west edge strips of both boxes
    Rgba-Fill $buf 64 64 0 1 1 16 $fr $fg $fb 255
    Rgba-Fill $buf 64 64 16 1 1 16 $fr $fg $fb 255
    # faint glass wash so mipmaps stay glassy instead of pulling transparent black
    Rgba-Fill $buf 64 64 0 20 64 16 $gr $gg $gb 12
    Save-Rgba $buf 64 64 $path
}

# Opaque brushed-metal sheet for the geometry frame bars (entitySolid).
function New-GateDoorFrameTexture([string]$path, [int]$r, [int]$g, [int]$b) {
    [byte[]]$buf = New-Rgba 32 32
    Rgba-Fill $buf 32 32 0 0 32 32 $r $g $b 255
    for ($y = 0; $y -lt 32; $y += 2) {
        Rgba-Fill $buf 32 32 0 $y 32 1 ($r + 16) ($g + 16) ($b + 18) 255
    }
    for ($y = 1; $y -lt 32; $y += 4) {
        Rgba-Fill $buf 32 32 0 $y 32 1 ([Math]::Max(0, $r - 8)) ([Math]::Max(0, $g - 8)) ([Math]::Max(0, $b - 6)) 255
    }
    Rgba-Fill $buf 32 32 0 0 32 1 ($r + 40) ($g + 42) ($b + 46) 255
    Rgba-Fill $buf 32 32 0 31 32 1 ([Math]::Max(0, $r - 10)) ([Math]::Max(0, $g - 10)) ([Math]::Max(0, $b - 8)) 255
    Rgba-Fill $buf 32 32 2 3 2 2 90 210 240 255
    Rgba-Fill $buf 32 32 27 26 2 2 90 210 240 255
    Save-Rgba $buf 32 32 $path
}

# Emissive cyan inlay for the leading-edge lock indicator.
function New-GateDoorGlowTexture([string]$path) {
    [byte[]]$buf = New-Rgba 16 16
    for ($y = 0; $y -lt 16; $y++) {
        $t = [Math]::Abs($y - 8) / 8.0
        $r = [int](100 * (1 - $t) + 8 * $t)
        $g = [int](240 * (1 - $t) + 60 * $t)
        $b = [int](255 * (1 - $t) + 80 * $t)
        Rgba-Fill $buf 16 16 0 $y 16 1 $r $g $b 255
    }
    Rgba-Fill $buf 16 16 0 7 16 2 235 255 255 255
    Save-Rgba $buf 16 16 $path
}

New-GateBladeTexture (Join-Path $entityTex 'egate_door.png') 28 34 42 120 214 235
New-GateDoorFrameTexture (Join-Path $entityTex 'egate_door_frame.png') 28 34 42
New-GateDoorGlowTexture (Join-Path $entityTex 'egate_door_glow.png')
New-GateBladeTexture (Join-Path $entityTex 'exit_gate_door.png') 34 30 26 120 214 235
New-GateDoorFrameTexture (Join-Path $entityTex 'exit_gate_door_frame.png') 34 30 26
New-GateDoorGlowTexture (Join-Path $entityTex 'exit_gate_door_glow.png')
# ---- documents ------------------------------------------------------------
function New-CardTexture([string]$name, [string]$title, [string]$subtitle, [string]$bg1, [string]$bg2, [string]$emblem, [bool]$booklet, [string]$code) {
    $bmp, $g = New-BitmapSmooth 64 64
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $c1 = [System.Drawing.ColorTranslator]::FromHtml($bg1)
    $c2 = [System.Drawing.ColorTranslator]::FromHtml($bg2)
    $gold = [System.Drawing.ColorTranslator]::FromHtml($emblem)
    if ($booklet) {
        $x = 13; $y = 6; $w = 38; $h = 52
    } else {
        $x = 4; $y = 15; $w = 56; $h = 36
    }
    $shadow = Brush (C 0 0 0 90); $g.FillRectangle($shadow, $x + 2, $y + 2, $w, $h); $shadow.Dispose()
    $grad = [System.Drawing.Drawing2D.LinearGradientBrush]::new([System.Drawing.Rectangle]::new($x, $y, $w, $h), $c1, $c2, 35)
    $g.FillRectangle($grad, $x, $y, $w, $h); $grad.Dispose()
    $border = Pen $gold 1.5; $g.DrawRectangle($border, $x, $y, $w, $h); $border.Dispose()
    Fill-Rect $g ($x + 2) ($y + 2) ($w - 4) 1 (C 255 255 255 70)
    # Booklet covers (passport) keep the gold emblem above the centred title.
    if ($booklet) {
        $ep = Pen $gold 1.2
        $g.DrawEllipse($ep, ($x + 8), ($y + 9), 11, 11)
        $g.DrawLine($ep, ($x + 13), ($y + 10), ($x + 13), ($y + 19))
        $g.DrawLine($ep, ($x + 9), ($y + 14), ($x + 18), ($y + 14))
        $ep.Dispose()
    }
    # Flat cards (ID card / permit) have a 56 px face with a full photo + MRZ block,
    # so the heading owns the top band: centred on the card and auto-fitted to the
    # 2 px inner margin. The old x+21 / Font 7 title measured 52-69 px wide and ran
    # 17-29 px past the face and off the canvas; the emblem that used to sit at
    # (x+7, y+7) is omitted here because it would collide with the heading band.
    if (-not $booklet) {
        $innerW = $w - 4
        Draw-TextCentered $g $title (Font (Get-FitFontSize $g $title 6 4 $innerW)) $gold $x ($y + 4) $w
        if ($subtitle -ne '') {
            Draw-TextCentered $g $subtitle (Font (Get-FitFontSize $g $subtitle 4 3 $innerW)) (C 240 245 250 220) $x ($y + 10) $w
        }
    }
    if ($booklet) {
        for ($i = 0; $i -lt 5; $i++) {
            Draw-Line $g ($x + 8) ($y + 25 + $i * 4) ($x + 30) ($y + 25 + $i * 4) (C 255 255 255 65) 1
        }
        # 8 glyphs of Arial bold 5 px measure 27 px wide: inside the w=38 cover that is
        # 5 px left + 6 px right padding, and 3 px above the bottom edge (ink y 51..54).
        # The old copy also drew the title at x+21 with Font 7 (40 px wide) -> clipped.
        Draw-TextCentered $g $title (Font 5) (C 255 240 210 190) $x ($y + 44) $w
    } else {
        Fill-Rect $g ($x + 8) ($y + 14) 13 19 (C 12 22 32 150)
        $face = Brush (C 145 170 190 180); $g.FillEllipse($face, $x + 12, $y + 17, 6, 6); $face.Dispose()
        $body = Brush (C 100 130 155 170); $g.FillRectangle($body, $x + 11, $y + 24, 8, 7); $body.Dispose()
        for ($i = 0; $i -lt 4; $i++) {
            Draw-Line $g ($x + 26) ($y + 16 + $i * 4) ($x + $w - 8) ($y + 16 + $i * 4) (C 235 245 255 100) 1
        }
        Fill-Rect $g ($x + 8) ($y + $h - 7) 31 3 (C 255 255 255 60)
        Draw-Text $g $code (Font 5) (C 255 255 255 130) ($x + $w - 17) ($y + $h - 9)
    }
    Save-Bitmap $bmp $g (Join-Path $itemTex "$name.png")
}

function New-CustomDocTexture([string]$name, [bool]$booklet, [bool]$permit) {
    $bmp, $g = New-BitmapSmooth 64 64
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    if ($booklet) {
        $x = 13; $y = 6; $w = 38; $h = 52
    } elseif ($permit) {
        $x = 7; $y = 13; $w = 50; $h = 38
    } else {
        $x = 4; $y = 15; $w = 56; $h = 36
    }
    $shadow = Brush (C 0 0 0 90); $g.FillRectangle($shadow, $x + 2, $y + 2, $w, $h); $shadow.Dispose()
    $grad = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
        [System.Drawing.Rectangle]::new($x, $y, $w, $h), (C 255 255 255), (C 196 202 208), 30)
    $g.FillRectangle($grad, $x, $y, $w, $h); $grad.Dispose()
    $border = Pen (C 245 248 250) 2; $g.DrawRectangle($border, $x, $y, $w, $h); $border.Dispose()
    Fill-Rect $g ($x + 3) ($y + 3) ($w - 6) 1 (C 255 255 255)
    # booklet: emblem sits centred under the title; the other card types keep the
    # original top-left emblem so their textures stay byte-identical.
    $emblemX = if ($booklet) { $x + 14 } else { $x + 7 }
    $emblemY = if ($booklet) { $y + 11 } else { $y + 7 }
    $ep = Pen (C 225 233 238) 1.2
    $g.DrawEllipse($ep, $emblemX, $emblemY, 10, 10)
    $g.DrawLine($ep, ($emblemX + 5), ($emblemY + 1), ($emblemX + 5), ($emblemY + 9))
    $g.DrawLine($ep, ($emblemX + 1), ($emblemY + 5), ($emblemX + 9), ($emblemY + 5))
    $ep.Dispose()
    if ($booklet) {
        # neutral cover: centred title (4 px side padding) instead of the old
        # x+21 / Font 6 placement, which ran 9 px past the cover edge.
        Draw-TextCentered $g 'CUSTOM' (Font 5) (C 82 92 99) $x ($y + 4) $w
    } else {
        Draw-Text $g 'CUSTOM' (Font 6) (C 82 92 99) ($x + 21) ($y + 6)
    }
    if ($booklet) {
        for ($i = 0; $i -lt 5; $i++) { Draw-Line $g ($x + 8) ($y + 26 + $i * 4) ($x + 30) ($y + 26 + $i * 4) (C 70 78 84 110) 1 }
        Draw-TextCentered $g 'DOCUMENT' (Font 5) (C 60 70 76) $x ($y + 44) $w
    } else {
        Fill-Rect $g ($x + 8) ($y + 14) 13 19 (C 74 82 88)
        $face = Brush (C 178 188 194); $g.FillEllipse($face, $x + 12, $y + 17, 6, 6); $face.Dispose()
        $body = Brush (C 135 146 153); $g.FillRectangle($body, $x + 11, $y + 24, 8, 7); $body.Dispose()
        for ($i = 0; $i -lt 4; $i++) { Draw-Line $g ($x + 26) ($y + 16 + $i * 4) ($x + $w - 8) ($y + 16 + $i * 4) (C 75 84 90 100) 1 }
        Fill-Rect $g ($x + 8) ($y + $h - 7) 31 3 (C 235 240 243)
        Draw-Text $g 'TYPE' (Font 5) (C 82 92 99) ($x + $w - 20) ($y + $h - 9)
    }
    Save-Bitmap $bmp $g (Join-Path $itemTex "$name.png")
}

if (-not $NoDocuments) {
    New-CardTexture 'id_card_cn' 'IDENTITY CARD' 'CN / RESIDENT' '#1A5E8C' '#3FA7C9' '#E5C76B' $false 'CN'
    New-CardTexture 'id_card_hk' 'IDENTITY CARD' 'HK / PERMANENT' '#732532' '#B64B5E' '#E7C87A' $false 'HK'
    New-CardTexture 'passport_cn' 'PASSPORT' '' '#7E1F24' '#B73A3D' '#E7C878' $true 'CN'
    New-CardTexture 'passport_hk' 'PASSPORT' '' '#1A2E57' '#33558E' '#D9C27A' $true 'HK'
    New-CardTexture 'passport_mo' 'PASSPORT' '' '#1D513D' '#2F7A5D' '#D7C47B' $true 'MO'
    New-CardTexture 'passport_us' 'PASSPORT' '' '#152C55' '#2C538F' '#DCC77C' $true 'US'
    New-CardTexture 'passport_jp' 'PASSPORT' '' '#7E2027' '#B3434B' '#E4C977' $true 'JP'
    New-CardTexture 'hk_macao_permit' 'EXIT-ENTRY PERMIT' 'HK / MACAU' '#173E73' '#2D67A8' '#E1C574' $false 'CN'
    New-CustomDocTexture 'custom_id_card' $false $false
    New-CustomDocTexture 'custom_passport' $true $false
    New-CustomDocTexture 'custom_permit' $false $true
}

# modifier icon
$bmp, $g = New-BitmapSmooth 32 32
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$shadow = Brush (C 0 0 0 100); $g.FillRectangle($shadow, 4, 6, 24, 20); $shadow.Dispose()
$grad = [System.Drawing.Drawing2D.LinearGradientBrush]::new([System.Drawing.Rectangle]::new(3, 5, 24, 20), (C 30 47 68), (C 18 27 40), 45)
$g.FillRectangle($grad, 3, 5, 24, 20); $grad.Dispose()
$p = Pen (C 220 185 100) 1.4; $g.DrawRectangle($p, 3, 5, 24, 20); $p.Dispose()
Fill-Rect $g 6 9 10 3 (C 120 200 220)
Fill-Rect $g 6 14 8 2 (C 100 160 190)
Fill-Rect $g 6 18 11 2 (C 100 160 190)
$p = Pen (C 220 185 100) 2.4
$g.DrawLine($p, 19, 9, 25, 17); $g.DrawLine($p, 25, 17, 22, 20); $g.DrawLine($p, 22, 20, 16, 14)
$p.Dispose()
$b = Brush (C 220 185 100); $g.FillEllipse($b, 18, 7, 4, 4); $g.FillEllipse($b, 22, 18, 4, 4); $b.Dispose()
Save-Bitmap $bmp $g (Join-Path $itemTex 'document_modifier.png')

# ---- device brush icon (config brush: handle + ferrule + bristles) --------
$bristleDark = C 23 116 110
$bristleMid = C 43 186 172
$bristleLight = C 143 250 238
$metalDark = C 74 88 98
$metal = C 201 214 222
$metalLight = C 241 248 252
$goldBrush = C 229 199 107
$woodDark = C 66 43 22
$wood = C 178 124 64
$woodLight = C 214 160 92
$woodDeep = C 132 86 40
$brBmp, $brG = New-Bitmap 32 32
# bristle fan: widening rows with a serrated top edge
for ($row = 0; $row -lt 8; $row++) {
    $half = 6 + [int][Math]::Floor($row * 0.5)
    $x0 = 16 - $half
    $w = $half * 2
    Fill-Rect $brG $x0 (3 + $row) $w 1 $bristleDark
    Fill-Rect $brG ($x0 + 1) (3 + $row) ($w - 2) 1 $bristleMid
    for ($i = 0; $i -lt ($w - 3); $i += 3) {
        Fill-Rect $brG ($x0 + 2 + $i) (3 + $row) 1 1 $bristleLight
    }
}
for ($i = 0; $i -lt 7; $i++) {
    $x = 8 + $i * 2
    Fill-Rect $brG $x 1 1 2 $bristleDark
    Fill-Rect $brG $x 1 1 1 $bristleLight
}
# metal ferrule with rivets
Fill-Rect $brG 8 10 16 4 $metalDark
Fill-Rect $brG 9 11 14 2 $metal
Fill-Rect $brG 9 11 14 1 $metalLight
Fill-Rect $brG 10 12 3 1 $metalLight
Fill-Rect $brG 19 12 3 1 $metalLight
Fill-Rect $brG 9 14 14 1 $goldBrush
# tapered wooden handle
for ($row = 0; $row -lt 15; $row++) {
    $y = 15 + $row
    $inset = if ($row -ge 11) { 1 } else { 0 }
    $x1 = 12 + $inset
    $x2 = 20 - $inset
    Fill-Rect $brG $x1 $y ($x2 - $x1) 1 $woodDark
    Fill-Rect $brG ($x1 + 1) $y ($x2 - $x1 - 2) 1 $wood
    Fill-Rect $brG ($x1 + 2) $y 2 1 $woodLight
    Fill-Rect $brG ($x2 - 2) $y 1 1 $woodDeep
    if (($row % 4) -eq 1) { Fill-Rect $brG ($x1 + 2) $y ($x2 - $x1 - 4) 1 $woodDeep }
}
Fill-Rect $brG 13 30 6 2 $woodDark
Fill-Rect $brG 14 30 4 1 $woodDeep
# small config label on the handle
Fill-Rect $brG 13 20 6 4 (C 244 239 226)
Fill-Rect $brG 14 21 4 1 (C 31 168 160)
Fill-Rect $brG 14 23 3 1 (C 31 168 160)
Save-Bitmap $brBmp $brG (Join-Path $itemTex 'device_brush.png')

# ---- document item models -------------------------------------------------
Write-Json (Join-Path $itemModels 'id_card_cn.json') ([ordered]@{ parent = 'minecraft:item/generated'; textures = [ordered]@{ layer0 = 'veriborder:item/id_card_cn' } })
Write-Json (Join-Path $itemModels 'id_card_hk.json') ([ordered]@{ parent = 'minecraft:item/generated'; textures = [ordered]@{ layer0 = 'veriborder:item/id_card_hk' } })
Write-Json (Join-Path $itemModels 'id_card.json') ([ordered]@{
    parent = 'minecraft:item/generated'
    textures = [ordered]@{ layer0 = 'veriborder:item/id_card_cn' }
    overrides = @(
        [ordered]@{ predicate = [ordered]@{ custom_model_data = 2 }; model = 'veriborder:item/id_card_hk' },
        [ordered]@{ predicate = [ordered]@{ custom_model_data = 100 }; model = 'veriborder:item/custom_id_card' }
    )
})
Write-Json (Join-Path $itemModels 'custom_id_card.json') ([ordered]@{ parent = 'minecraft:item/generated'; textures = [ordered]@{ layer0 = 'veriborder:item/custom_id_card' } })
foreach ($style in @('cn', 'hk', 'mo', 'us', 'jp')) {
    Write-Json (Join-Path $itemModels "passport_$style.json") ([ordered]@{ parent = 'minecraft:item/generated'; textures = [ordered]@{ layer0 = "veriborder:item/passport_$style" } })
}
$passportOverrides = @()
$idx = 2
foreach ($style in @('hk', 'mo', 'us', 'jp')) {
    $passportOverrides += [ordered]@{ predicate = [ordered]@{ custom_model_data = $idx }; model = "veriborder:item/passport_$style" }
    $idx++
}
$passportOverrides += [ordered]@{ predicate = [ordered]@{ custom_model_data = 100 }; model = 'veriborder:item/custom_passport' }
Write-Json (Join-Path $itemModels 'passport.json') ([ordered]@{
    parent = 'minecraft:item/generated'
    textures = [ordered]@{ layer0 = 'veriborder:item/passport_cn' }
    overrides = $passportOverrides
})
Write-Json (Join-Path $itemModels 'custom_passport.json') ([ordered]@{ parent = 'minecraft:item/generated'; textures = [ordered]@{ layer0 = 'veriborder:item/custom_passport' } })
Write-Json (Join-Path $itemModels 'hk_macao_permit.json') ([ordered]@{
    parent = 'minecraft:item/generated'
    textures = [ordered]@{ layer0 = 'veriborder:item/hk_macao_permit' }
    overrides = @([ordered]@{ predicate = [ordered]@{ custom_model_data = 100 }; model = 'veriborder:item/custom_permit' })
})
Write-Json (Join-Path $itemModels 'custom_permit.json') ([ordered]@{ parent = 'minecraft:item/generated'; textures = [ordered]@{ layer0 = 'veriborder:item/custom_permit' } })
Write-Json (Join-Path $itemModels 'document_modifier.json') ([ordered]@{ parent = 'minecraft:item/generated'; textures = [ordered]@{ layer0 = 'veriborder:item/document_modifier' } })
# config brush (right-click a device to open DeviceConfigScreen)
Write-Json (Join-Path $itemModels 'device_brush.json') ([ordered]@{
    parent = 'minecraft:item/handheld'
    textures = [ordered]@{ layer0 = 'veriborder:item/device_brush' }
})

# ---- logo -----------------------------------------------------------------
$logoDir = Join-Path $root 'src\main\resources'
$bmp, $g = New-BitmapSmooth 128 128
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear((C 7 13 21))
$grad = [System.Drawing.Drawing2D.LinearGradientBrush]::new([System.Drawing.Rectangle]::new(0, 0, 128, 128), (C 12 28 44), (C 5 10 16), 45)
$g.FillRectangle($grad, 0, 0, 128, 128); $grad.Dispose()
$p = Pen (C 220 185 100) 2; $g.DrawRectangle($p, 8, 8, 112, 112); $p.Dispose()
Draw-TextCentered $g 'VERIBORDER' (Font 16) (C 232 241 255) 0 30 128
Draw-TextCentered $g 'IMMIGRATION / DOCUMENT SYSTEM' (Font 7) (C 120 200 220) 0 54 128
$p = Pen (C 220 185 100) 1.2
$g.DrawEllipse($p, 48, 70, 32, 32); $g.DrawLine($p, 64, 72, 64, 100); $g.DrawLine($p, 50, 86, 78, 86)
$p.Dispose()
Draw-TextCentered $g 'VB / 1.0' (Font 8) (C 220 185 100) 0 108 128
Save-Bitmap $bmp $g (Join-Path $logoDir 'veriborder.png')

Get-ChildItem $blockTex, $itemTex, $entityTex -Filter *.png | Measure-Object | ForEach-Object {
    Write-Host "Generated textures: $($_.Count)"
}
Get-ChildItem $blockModels -Filter *.json | Measure-Object | ForEach-Object {
    Write-Host "Block models: $($_.Count)"
}
Write-Host "Assets generated under $assets"
