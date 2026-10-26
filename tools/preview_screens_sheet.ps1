# VeriBorder 26.4.1 — device screen before/after sheet
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\preview_screens_sheet.ps1
#
# Composes tools/preview/screens_before_after.png:
#   one row per non-gate device, showing
#     (1) the OLD shared-panel screen as it actually appeared in world space,
#     (2) the NEW per-device screen as it appears in world space,
#     (3) the NEW screen as the raw texture file (what is committed to disk).
#
# "In world space" = the texture mapped onto the device's real screen quad, so
# the horizontal stretch is exactly what a player sees. The quad aspect is read
# from the model JSON, which keeps this sheet honest if geometry ever changes.
#
# The device table (name / accent) comes from tools/preview/_screens_manifest.json,
# which tools/gen_device_screens.js writes, so there is exactly one source of truth.
# The old textures are preserved in tools/preview/_screens_before/.
#
# NOTE: this file must stay UTF-8 **with BOM** — Windows PowerShell 5.1 reads
# BOM-less scripts as ANSI and mangles the Chinese literals.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root     = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$texDir   = Join-Path $root 'src\main\resources\assets\veriborder\textures\block'
$modelDir = Join-Path $root 'src\main\resources\assets\veriborder\models\block'
$prev     = Join-Path $root 'tools\preview'
$before   = Join-Path $prev '_screens_before'
$out      = Join-Path $prev 'screens_before_after.png'
$manifest = Join-Path $prev '_screens_manifest.json'

if (-not (Test-Path $manifest)) { throw "missing $manifest - run: node tools/gen_device_screens.js" }
$devices = Get-Content $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ($devices.Count -ne 19) { throw "expected 19 devices in manifest, found $($devices.Count)" }

function Get-ScreenAspect([string]$id) {
    $j = Get-Content (Join-Path $modelDir "$id.json") -Raw | ConvertFrom-Json
    foreach ($e in $j.elements) {
        if ($e.faces -and $e.faces.north -and $e.faces.north.texture -eq '#screen') {
            $dx = [double]$e.to[0] - [double]$e.from[0]
            $dy = [double]$e.to[1] - [double]$e.from[1]
            if ($dy -gt 0) { return $dx / $dy }
        }
    }
    return 1.0
}

# ---------------------------------------------------------------- geometry
$rowH  = 134
$headH = 104
$footH = 60
$W     = 1250
$H     = $headH + $devices.Count * $rowH + $footH
$nameX = 26;  $nameW = 288
$bX    = 330; $bW    = 296
$aX    = 646; $aW    = 296
$nX    = 962; $nW    = 262
$imgH  = 92          # in-world column height, px

$bmp = [System.Drawing.Bitmap]::new($W, $H, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g   = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode     = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode   = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit
$g.Clear([System.Drawing.Color]::FromArgb(255, 12, 16, 21))

function Fill([int]$x, [int]$y, [int]$w, [int]$h, $c) {
    $b = [System.Drawing.SolidBrush]::new($c); $g.FillRectangle($b, $x, $y, $w, $h); $b.Dispose()
}
function Stroke([int]$x, [int]$y, [int]$w, [int]$h, $c) {
    $p = [System.Drawing.Pen]::new($c, 1); $g.DrawRectangle($p, $x, $y, $w, $h); $p.Dispose()
}
function Txt([string]$s, $f, $c, [float]$x, [float]$y) {
    $b = [System.Drawing.SolidBrush]::new($c); $g.DrawString($s, $f, $b, $x, $y); $b.Dispose()
}

$fTitle = [System.Drawing.Font]::new('Microsoft YaHei', 21, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fSub   = [System.Drawing.Font]::new('Microsoft YaHei', 12, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$fCol   = [System.Drawing.Font]::new('Microsoft YaHei', 14, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fName  = [System.Drawing.Font]::new('Microsoft YaHei', 16, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
$fMeta  = [System.Drawing.Font]::new('Consolas', 11, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
$fFoot  = [System.Drawing.Font]::new('Microsoft YaHei', 12, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)

$cText  = [System.Drawing.Color]::FromArgb(255, 232, 246, 255)
$cDim   = [System.Drawing.Color]::FromArgb(255, 128, 158, 180)
$cMute  = [System.Drawing.Color]::FromArgb(255, 100, 124, 142)
$cLine  = [System.Drawing.Color]::FromArgb(255, 42, 68, 90)
$cOld   = [System.Drawing.Color]::FromArgb(255, 255, 118, 128)
$cNew   = [System.Drawing.Color]::FromArgb(255, 108, 236, 170)

# ---------------------------------------------------------------- chrome
Fill 0 0 $W $H ([System.Drawing.Color]::FromArgb(255, 12, 16, 21))
Txt 'VeriBorder 26.4.1  —  设备屏幕 UI 重绘   before / after' $fTitle $cText 26 14
Txt '19 台非闸机设备（闸机由闸机代理负责）。"游戏内"两列按各自屏幕四边形的宽高比映射，横向拉伸与玩家看到的一致；最右一列为提交到仓库的纹理原图。' $fSub $cDim 26 48

$headRow = 76
Stroke 20 $headRow ($W - 40) 1 $cLine
Txt '设备' $fCol ([System.Drawing.Color]::FromArgb(255, 170, 200, 220)) $nameX ($headRow + 8)
Txt '改动前 · 游戏内' $fCol $cOld $bX ($headRow + 8)
Txt '改动后 · 游戏内' $fCol $cNew $aX ($headRow + 8)
Txt '改动后 · 纹理原图' $fCol ([System.Drawing.Color]::FromArgb(255, 150, 180, 210)) $nX ($headRow + 8)

$row = 0
foreach ($d in $devices) {
    $id     = $d.id
    $cn     = $d.cn
    $accHex = $d.accent
    $acc    = [System.Drawing.ColorTranslator]::FromHtml($accHex)
    $y      = $headH + $row * $rowH
    $row++

    if ($row % 2 -eq 0) { Fill 20 $y ($W - 40) $rowH ([System.Drawing.Color]::FromArgb(255, 17, 23, 30)) }
    Fill 20 $y 2 ($rowH - 8) $acc

    $aspect     = Get-ScreenAspect $id
    $beforePath = Join-Path $before "$id`_screen.png"
    $afterPath  = Join-Path $texDir "$id`_screen.png"

    # --- name block
    Txt $cn  $fName $acc ($nameX + 10) ($y + 12)
    Txt $id  $fMeta $cMute ($nameX + 10) ($y + 38)
    Fill ($nameX + 10) ($y + 60) 12 12 $acc
    Txt $accHex $fMeta $cDim ($nameX + 28) ($y + 58)
    Txt $d.size $fMeta $cMute ($nameX + 100) ($y + 58)

    # --- before (old shared panel, in world space)
    if (Test-Path $beforePath) {
        $bImg = [System.Drawing.Image]::FromFile($beforePath)
        $bW = [int][Math]::Round($imgH * $aspect)
        if ($bW -lt 8) { $bW = 8 }
        if ($bW -gt 270) { $bW = 270 }
        Stroke ($bX - 1) ($y + 26) ($bW + 2) ($imgH + 2) ([System.Drawing.Color]::FromArgb(255, 40, 52, 64))
        $g.DrawImage($bImg, [System.Drawing.Rectangle]::new($bX, $y + 27, $bW, $imgH))
        Txt "$($bImg.Width)x$($bImg.Height)" $fMeta $cMute $bX ($y + 31 + $imgH)
        $bImg.Dispose()
    }

    # --- after (in world space) + after (native)
    if (Test-Path $afterPath) {
        $aImg = [System.Drawing.Image]::FromFile($afterPath)
        $aWid = [int][Math]::Round($imgH * $aspect)
        if ($aWid -lt 8) { $aWid = 8 }
        if ($aWid -gt 270) { $aWid = 270 }
        Stroke ($aX - 1) ($y + 26) ($aWid + 2) ($imgH + 2) $acc
        $g.DrawImage($aImg, [System.Drawing.Rectangle]::new($aX, $y + 27, $aWid, $imgH))
        Txt "$($aImg.Width)x$($aImg.Height)   screen-ratio $([Math]::Round($aspect, 2))" $fMeta $cDim $aX ($y + 31 + $imgH)

        $z = [Math]::Max(1, [Math]::Floor([Math]::Min(($nW - 24) / $aImg.Width, $imgH / $aImg.Height)))
        $nw = [int]($aImg.Width * $z); $nh = [int]($aImg.Height * $z)
        Stroke ($nX - 1) ($y + 26) ($nw + 2) ($nh + 2) ([System.Drawing.Color]::FromArgb(255, 48, 62, 76))
        $g.DrawImage($aImg, [System.Drawing.Rectangle]::new($nX, $y + 27, $nw, $nh))
        Txt "$($z)x" $fMeta $cMute ($nX + $nw + 8) ($y + 31)
        $aImg.Dispose()
    }
}

$fy = $headH + $devices.Count * $rowH + 8
Stroke 20 $fy ($W - 40) 1 $cLine
Txt '统一设计系统：3x5 像素字模（同字格 / 1px 描边 / 4px 步进）、1px 圆角描边、同一套状态色（绿=通过 黄=待处理 红=拒绝），每台设备一个专属强调色。alpha 全不透明（二值），底色压在 0x14 亮度以下以便强调色发亮。' $fFoot $cDim 26 ($fy + 12)

$g.Dispose()
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Host "wrote $out ($W x $H)"
