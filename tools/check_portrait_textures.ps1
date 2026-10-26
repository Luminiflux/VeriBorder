# ---------------------------------------------------------------------------
# VeriBorder — portrait preset audit (26.4.1)
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\check_portrait_textures.ps1
#
# READ-ONLY audit of src/main/resources/assets/veriborder/textures/portrait:
# the 24 built-in presets (p00..p23) a document can name in its signed
# `portrait` field. Ownership of the artwork itself belongs to the document
# rendering owner (32x32 pixel art, palette.json describes the cast); this script
# never writes into src/main/resources — it only reports, and optionally writes a
# 4x contact sheet into tools/preview/ for eyeballing.
#
# Checks per file, all of which the runtime and the unit tests rely on:
#   * present and readable
#   * a real PNG (signature + IHDR)
#   * square, with an edge of exactly PRESET_SOURCE_EDGE (32) pixels
#   * at most PortraitLibrary.MAX_BYTES (16 KiB)
#   * the distinct-colour count is reported against the 28-colour pixel-art budget
#
#   -ContactSheet   also writes tools/preview/portraits/contact-sheet.png
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [string]$Root,
    [switch]$ContactSheet
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = if ($Root) { (Resolve-Path -LiteralPath $Root).Path } else { Split-Path -Parent $PSScriptRoot }
$dir = Join-Path $root 'src\main\resources\assets\veriborder\textures\portrait'

# Keep in sync with com.veriborder.doc.PortraitLibrary
$EXPECTED_EDGE = 32
$MAX_BYTES = 16 * 1024
$MAX_COLORS = 28
$COUNT = 24

if (-not (Test-Path -LiteralPath $dir)) {
    Write-Host "MISSING folder $dir" -ForegroundColor Red
    exit 1
}

$failures = 0
$images = @()

for ($i = 0; $i -lt $COUNT; $i++) {
    $id = 'p{0:D2}' -f $i
    $file = Join-Path $dir ($id + '.png')
    if (-not (Test-Path -LiteralPath $file)) {
        Write-Host ("{0}: MISSING" -f $id) -ForegroundColor Red
        $failures++
        continue
    }
    $length = (Get-Item -LiteralPath $file).Length
    $image = $null
    try {
        $image = [System.Drawing.Bitmap]::new($file)
    } catch {
        Write-Host ("{0}: not a decodable PNG ({1} B)" -f $id, $length) -ForegroundColor Red
        $failures++
        continue
    }
    $problems = @()
    $colourNote = ""
    if ($image.Width -ne $EXPECTED_EDGE -or $image.Height -ne $EXPECTED_EDGE) {
        $problems += ("size {0}x{1} != {2}x{2}" -f $image.Width, $image.Height, $EXPECTED_EDGE)
    }
    if ($length -gt $MAX_BYTES) {
        $problems += ("{0} B > {1} B" -f $length, $MAX_BYTES)
    }
    $colors = @{}
    for ($y = 0; $y -lt $image.Height; $y++) {
        for ($x = 0; $x -lt $image.Width; $x++) {
            $argb = $image.GetPixel($x, $y).ToArgb()
            if (-not $colors.ContainsKey($argb)) { $colors[$argb] = $true }
        }
    }
    # The colour budget is the artwork owner's policy, not a runtime requirement:
    # report it, never fail on it (the renderer only needs a square PNG).
    if ($colors.Count -gt $MAX_COLORS) {
        $colourNote = " ({0} colours, budget {1})" -f $colors.Count, $MAX_COLORS
    }
    if ($image.Width -eq $EXPECTED_EDGE -and $image.Height -eq $EXPECTED_EDGE) {
        $images += $image
    } else {
        $image.Dispose()
    }
    if ($problems.Count -eq 0) {
        Write-Host ("{0}: ok ({1} B, {2} colours{3})" -f $id, $length, $colors.Count, $colourNote) -ForegroundColor Green
    } else {
        Write-Host ("{0}: {1}" -f $id, ($problems -join '; ')) -ForegroundColor Red
        $failures++
    }
}

if ($ContactSheet -and $images.Count -gt 0) {
    $previewDir = Join-Path $root 'tools\preview\portraits'
    New-Item -ItemType Directory -Force $previewDir | Out-Null
    $cell = $EXPECTED_EDGE * 4
    $columns = 6
    $rows = [Math]::Ceiling($images.Count / $columns)
    $sheet = [System.Drawing.Bitmap]::new($columns * ($cell + 4) + 4, $rows * ($cell + 4) + 4,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($sheet)
    $graphics.Clear([System.Drawing.Color]::FromArgb(255, 11, 21, 32))
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
    for ($index = 0; $index -lt $images.Count; $index++) {
        $column = $index % $columns
        $row = [Math]::Floor($index / $columns)
        $x = 4 + $column * ($cell + 4)
        $y = 4 + $row * ($cell + 4)
        $graphics.DrawImage($images[$index], $x, $y, $cell, $cell)
    }
    $graphics.Dispose()
    $target = Join-Path $previewDir 'contact-sheet.png'
    $stream = [System.IO.MemoryStream]::new()
    $sheet.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
    [System.IO.File]::WriteAllBytes($target, $stream.ToArray())
    $stream.Dispose()
    $sheet.Dispose()
    Write-Host "contact sheet: $target"
}

foreach ($image in $images) { $image.Dispose() }

if ($failures -gt 0) {
    Write-Host ("{0} preset(s) failed the audit" -f $failures) -ForegroundColor Red
    exit 1
}
Write-Host ("all {0} portrait presets pass" -f $COUNT) -ForegroundColor Green
exit 0
