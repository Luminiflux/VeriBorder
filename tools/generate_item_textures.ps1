# ---------------------------------------------------------------------------
# VeriBorder item-texture regeneration + audit  (26.4.1 item asset upgrade)
#
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_item_textures.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_item_textures.ps1 -AuditOnly
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\generate_item_textures.ps1 -Root D:\scratch
#
# Regenerates ONLY textures\item: the document cards (5 passports, the HK/Macau
# permit, 2 ID cards, 3 custom documents), the four brush icons, the registry /
# border-terminal / document-modifier icons, and the per-device item screen
# textures used by the dedicated 3D item models. Nothing here reads or writes
# textures\block, models\block, blockstates or any gate asset.
#
# The drawing lives in tools/lib_item_art.ps1 (one palette, one top-left key
# light, 1 px dark outline, binary alpha). This script is the manifest + gate.
#
# The audit is the regression gate for three classes of bug:
#   * text running off the card face   -> TEXT_MARGIN / NO_TEXT,
#   * icons bleeding off the canvas    -> RING / EMPTY,
#   * shader see-through               -> the legacy SEMI_ALPHA counter plus the
#     new ALPHA_BINARY gate: every texel must be alpha 0 or 255, and fully
#     transparent texels must have RGB 0 so mipmaps cannot bleed a dark halo.
#   * z-fighting / light leak on the dedicated 3D item models -> the new
#     ITEM_OVERLAP gate (same-facing coincident faces, elements fully enclosed
#     by another element, invalid JSON).
# Text boxes are measured by re-running the same painters with Draw-Text /
# Draw-TextCentered redirected to an empty probe bitmap, so the numbers come
# from the real layout code, not from re-typed coordinates.
# ---------------------------------------------------------------------------
[CmdletBinding()]
param(
    [switch]$AuditOnly,
    # Optional output root (a directory containing src\main\resources); see
    # tools/check_generators.ps1.
    [string]$Root
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = if ($Root) { (Resolve-Path -LiteralPath $Root).Path } else { Split-Path -Parent $PSScriptRoot }
$itemTex = Join-Path $root 'src\main\resources\assets\veriborder\textures\item'
$itemModels = Join-Path $root 'src\main\resources\assets\veriborder\models\item'
# The generator owns this directory: recreate it when it was removed (scratch
# runs, clean checkouts) instead of relying on generate_assets.ps1 having run.
New-Item -ItemType Directory -Force $itemTex | Out-Null

. (Join-Path $PSScriptRoot 'lib_item_art.ps1')
. (Join-Path $PSScriptRoot 'lib_item_font.ps1')

# Document cards (id_card*, passport*, hk_macao_permit*, custom_*) are owned by
# the document line (double-layer tintable + emblem + custom-art rework). This
# script no longer paints them, so it does not text-audit them either; they are
# still covered by the generic RING / EMPTY / ALPHA_BINARY / size gates below.
# Register a face rectangle for a texture name here if a card painter comes back.
$cardFaces = @{}
# Full-bleed icons historically reached the canvas edge by design. The upgraded
# brushes and device icons now keep a 1 px margin, so nothing is exempt in
# practice; the list stays as a safety valve for hand-drawn exceptions.
$fullBleed = @('device_brush', 'config_copy_brush', 'gate_config_brush', 'dual_gate_brush')

# ---- manifest --------------------------------------------------------------
# Brushes: one painter, per-brush accent band so the four read apart at 1x.
$brushSpecs = @(
    @{ name = 'device_brush';      accent = '#2FC6B7'; bristle = '#2FBAAA'; salt = 11 }
    @{ name = 'config_copy_brush'; accent = '#4EA1D3'; bristle = '#7FC0E4'; salt = 23 }
    @{ name = 'gate_config_brush'; accent = '#E8A93F'; bristle = '#F0C272'; salt = 37 }
    @{ name = 'dual_gate_brush';   accent = '#4ED88A'; bristle = '#8FE7B4'; salt = 53 }
)
# Item material tiles used by every dedicated 3D item model. These are ITEM-side
# surfaces on purpose: the per-device block chassis textures differ wildly in
# value (a navy issuer body, a white scanner body, an orange stamp body), so the
# item family gets its own coherent material set.
$materialSpecs = @(
    @{ name = 'item_shell';        kind = 'shell';  tint = '#EFEFF2' }
    @{ name = 'item_trim';         kind = 'trim';   tint = '#CBD8E1' }
    @{ name = 'item_glass';        kind = 'glass';  tint = '#123B5E' }
    @{ name = 'item_led';          kind = 'led';    tint = '#5FE0FF' }
    @{ name = 'item_paper';        kind = 'paper';  tint = '#F1EBDC' }
    @{ name = 'item_panel';        kind = 'panel';  tint = '#3B434B' }
    @{ name = 'item_accent_blue';  kind = 'accent'; tint = '#4EA1D3' }
    @{ name = 'item_accent_teal';  kind = 'accent'; tint = '#2FC6B7' }
    @{ name = 'item_accent_amber'; kind = 'accent'; tint = '#E0A84A' }
    @{ name = 'item_accent_violet'; kind = 'accent'; tint = '#7C6AF2' }
    @{ name = 'item_accent_red';   kind = 'accent'; tint = '#E5564F' }
)
# Item screen textures for the dedicated 3D item models. The category decides
# the on-screen UI language so no two device families share one blue bar.
$screenSpecs = @(
    # --- pilot batch (issuers / readers / stamp) ---
    @{ name = 'id_issuer_screen';        category = 'issuer';   accent = '#4EA1D3'; variant = 'card' }
    @{ name = 'passport_issuer_screen';  category = 'issuer';   accent = '#4EA1D3'; variant = 'booklet' }
    @{ name = 'permit_issuer_screen';    category = 'issuer';   accent = '#4EA1D3'; variant = 'permit' }
    @{ name = 'desk_scanner_screen';     category = 'reader';   accent = '#2FC6B7'; variant = 'card' }
    @{ name = 'station_verifier_screen'; category = 'reader';   accent = '#2FC6B7'; variant = 'queue' }
    @{ name = 'entry_stamp_screen';      category = 'stamp';    accent = '#E0A84A'; variant = 'card' }
    # --- batch: remaining issuers (visa / endorsement, violet family) ---
    @{ name = 'visa_issuer_screen';         category = 'issuer'; accent = '#7C6AF2'; variant = 'visa' }
    @{ name = 'self_visa_screen';           category = 'issuer'; accent = '#3E8FC4'; variant = 'visa' }
    @{ name = 'endorsement_machine_screen'; category = 'issuer'; accent = '#7C6AF2'; variant = 'endorse' }
    @{ name = 'self_endorsement_screen';    category = 'issuer'; accent = '#9B6AF2'; variant = 'endorse' }
    # --- batch: remaining readers ---
    @{ name = 'hotel_reader_screen';     category = 'reader';   accent = '#2FC6B7'; variant = 'mrz' }
    @{ name = 'manual_terminal_screen';  category = 'reader';   accent = '#2FC6B7'; variant = 'keypad' }
    @{ name = 'airport_checkin_screen';  category = 'reader';   accent = '#2FC6B7'; variant = 'boarding' }
    # --- batch: remaining stamps ---
    @{ name = 'exit_stamp_screen';       category = 'stamp';    accent = '#E0A84A'; variant = 'card' }
    @{ name = 'stamp_history_screen';    category = 'stamp';    accent = '#E0A84A'; variant = 'history' }
    # --- batch: service terminals (roster + IN/OUT, print preview) ---
    @{ name = 'loss_terminal_screen';      category = 'terminal'; accent = '#F05A5A'; variant = 'lost' }
    @{ name = 'renewal_machine_screen';    category = 'terminal'; accent = '#F05A5A'; variant = 'renew' }
    @{ name = 'blacklist_terminal_screen'; category = 'terminal'; accent = '#C9393F'; variant = 'black' }
    @{ name = 'print_preview_screen';      category = 'print';    accent = '#F05A5A'; variant = 'card' }
)

# ---- painter driver --------------------------------------------------------
# Every texture this script owns is produced here, so the regeneration pass and
# the text-probe pass run exactly the same code.
function Invoke-ItemTextures {
    $fixes = [ordered]@{}
    foreach ($b in $brushSpecs) {
        $fixes[$b.name] = New-IaBrush $b.name $b.accent $b.bristle $b.salt
    }
    $fixes['credential_registry'] = New-IaRegistryIcon
    $fixes['border_terminal'] = New-IaBorderTerminalIcon
    $fixes['document_modifier'] = New-IaModifierIcon
    foreach ($s in $screenSpecs) {
        $fixes[$s.name] = New-IaItemScreen $s.name $s.category $s.accent $s.variant
    }
    foreach ($m in $materialSpecs) {
        $fixes[$m.name] = New-IaMaterialTile $m.name $m.kind $m.tint
    }
    return $fixes
}

$Script:IA_OutDir = $itemTex
$alphaFixes = [ordered]@{}
if (-not $AuditOnly) {
    $alphaFixes = Invoke-ItemTextures
    Write-Host ''
    Write-Host "== regenerated $($alphaFixes.Count) item texture(s) into $itemTex =="
    $totalFixed = 0
    foreach ($k in $alphaFixes.Keys) {
        $n = [int]$alphaFixes[$k]
        $totalFixed += $n
        if ($n -gt 0) { Write-Host ("   alpha hardened: {0,-22} {1,4} px" -f $k, $n) }
    }
    Write-Host ("   alpha pixels hardened on save: {0}" -f $totalFixed)
}

# ---- text-layer probe ------------------------------------------------------
function Get-InkBbox([System.Drawing.Bitmap]$bmp) {
    $rect = [System.Drawing.Rectangle]::new(0, 0, $bmp.Width, $bmp.Height)
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $stride = $data.Stride
        $bytes = [byte[]]::new([Math]::Abs($stride) * $bmp.Height)
        [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    } finally { $bmp.UnlockBits($data) }
    $x0 = $bmp.Width; $y0 = $bmp.Height; $x1 = -1; $y1 = -1
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        $row = $y * [Math]::Abs($stride)
        for ($x = 0; $x -lt $bmp.Width; $x++) {
            if ($bytes[$row + $x * 4 + 3] -gt 0) {
                if ($x -lt $x0) { $x0 = $x }
                if ($x -gt $x1) { $x1 = $x }
                if ($y -lt $y0) { $y0 = $y }
                if ($y -gt $y1) { $y1 = $y }
            }
        }
    }
    if ($x1 -lt 0) { return $null }
    return @($x0, $y0, $x1, $y1)
}

# Alpha statistics in one pass over the raw bits:
#   semi  = 0 < alpha < 255          (the shader-breaking ones)
#   dirty = alpha == 0 but RGB != 0  (mipmap halo source)
function Get-AlphaStats([System.Drawing.Bitmap]$bmp) {
    $rect = [System.Drawing.Rectangle]::new(0, 0, $bmp.Width, $bmp.Height)
    $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    try {
        $stride = [Math]::Abs($data.Stride)
        $bytes = [byte[]]::new($stride * $bmp.Height)
        [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
    } finally { $bmp.UnlockBits($data) }
    $semi = 0
    $dirty = 0
    for ($y = 0; $y -lt $bmp.Height; $y++) {
        $row = $y * $stride
        for ($x = 0; $x -lt $bmp.Width; $x++) {
            $i = $row + $x * 4
            $a = $bytes[$i + 3]
            if ($a -eq 0) {
                if ($bytes[$i] -ne 0 -or $bytes[$i + 1] -ne 0 -or $bytes[$i + 2] -ne 0) { $dirty++ }
            } elseif ($a -ne 255) {
                $semi++
            }
        }
    }
    return @($semi, $dirty)
}

# probe canvas is wider/taller than the texture so a regression that draws past
# the canvas is measured instead of silently clipped
$script:probeBmp = [System.Drawing.Bitmap]::new(192, 128, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$script:probeG = [System.Drawing.Graphics]::FromImage($script:probeBmp)
$script:probeG.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$script:probeG.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$script:probeG.Clear($Script:IA_Clear)
$script:textBoxes = [System.Collections.Generic.List[object]]::new()
$script:textRuns = [ordered]@{}

function Draw-Text($g, [string]$text, [System.Drawing.Font]$font, [System.Drawing.Color]$color, [int]$x, [int]$y) {
    if ($null -eq $script:probeG) { return }
    $b = [System.Drawing.SolidBrush]::new($Script:IA_White)
    $script:probeG.DrawString($text, $font, $b, [single]$x, [single]$y)
    $b.Dispose()
    $bb = Get-InkBbox $script:probeBmp
    if ($bb) { $script:textBoxes.Add([pscustomobject]@{ Text = $text; Size = $font.Size; X0 = $bb[0]; Y0 = $bb[1]; X1 = $bb[2]; Y1 = $bb[3] }) }
    $script:probeG.Clear($Script:IA_Clear)
}
function Draw-TextCentered($g, [string]$text, [System.Drawing.Font]$font, [System.Drawing.Color]$color, [int]$x, [int]$y, [int]$w) {
    if ($null -eq $script:probeG) { return }
    $b = [System.Drawing.SolidBrush]::new($Script:IA_White)
    $sf = [System.Drawing.StringFormat]::new()
    $sf.Alignment = [System.Drawing.StringAlignment]::Center
    $script:probeG.DrawString($text, $font, $b, [System.Drawing.RectangleF]::new($x, $y, $w, 24), $sf)
    $sf.Dispose(); $b.Dispose()
    $bb = Get-InkBbox $script:probeBmp
    if ($bb) { $script:textBoxes.Add([pscustomobject]@{ Text = $text; Size = $font.Size; X0 = $bb[0]; Y0 = $bb[1]; X1 = $bb[2]; Y1 = $bb[3] }) }
    $script:probeG.Clear($Script:IA_Clear)
}

# Re-run the painters with the redirected text functions into a scratch folder,
# capturing the text boxes of each texture separately.
$probeDir = Join-Path ([System.IO.Path]::GetTempPath()) "vb_item_probe_$PID"
New-Item -ItemType Directory -Force $probeDir | Out-Null
$realOut = $Script:IA_OutDir
try {
    $Script:IA_OutDir = $probeDir
    foreach ($s in $screenSpecs) {
        $script:textBoxes.Clear()
        $null = New-IaItemScreen $s.name $s.category $s.accent $s.variant
        $script:textRuns[$s.name] = @($script:textBoxes)
    }
} finally {
    $script:probeG.Dispose()
    $script:probeBmp.Dispose()
    $script:probeG = $null
    $Script:IA_OutDir = $realOut
    Remove-Item -Recurse -Force $probeDir -ErrorAction SilentlyContinue
}

# ---- item model geometry gate (ITEM_OVERLAP) -------------------------------
function Get-ItemModelOverlaps([string]$path) {
    $model = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    $els = @($model.elements)
    if ($els.Count -eq 0 -or $null -eq $els[0]) { return @() }
    $hits = [System.Collections.Generic.List[string]]::new()
    for ($i = 0; $i -lt $els.Count; $i++) {
        $af = @([double]$els[$i].from[0], [double]$els[$i].from[1], [double]$els[$i].from[2])
        $at = @([double]$els[$i].to[0], [double]$els[$i].to[1], [double]$els[$i].to[2])
        $amin = @(); $amax = @()
        for ($k = 0; $k -lt 3; $k++) {
            $amin += [Math]::Min($af[$k], $at[$k]); $amax += [Math]::Max($af[$k], $at[$k])
        }
        for ($j = $i + 1; $j -lt $els.Count; $j++) {
            $bf = @([double]$els[$j].from[0], [double]$els[$j].from[1], [double]$els[$j].from[2])
            $bt = @([double]$els[$j].to[0], [double]$els[$j].to[1], [double]$els[$j].to[2])
            $bmin = @(); $bmax = @()
            for ($k = 0; $k -lt 3; $k++) {
                $bmin += [Math]::Min($bf[$k], $bt[$k]); $bmax += [Math]::Max($bf[$k], $bt[$k])
            }
            # 1) same-facing coincident faces (z-fighting / light leak)
            for ($ax = 0; $ax -lt 3; $ax++) {
                $o1 = ($ax + 1) % 3; $o2 = ($ax + 2) % 3
                $ov1 = [Math]::Min($amax[$o1], $bmax[$o1]) - [Math]::Max($amin[$o1], $bmin[$o1])
                $ov2 = [Math]::Min($amax[$o2], $bmax[$o2]) - [Math]::Max($amin[$o2], $bmin[$o2])
                if ($ov1 -le 0.02 -or $ov2 -le 0.02) { continue }
                $sameMax = [Math]::Abs($amax[$ax] - $bmax[$ax]) -lt 0.005
                $sameMin = [Math]::Abs($amin[$ax] - $bmin[$ax]) -lt 0.005
                if ($sameMax -or $sameMin) {
                    $side = if ($sameMax) { 'max' } else { 'min' }
                    $hits.Add("coincident:$i/$j/$('xyz'[$ax])/$side")
                }
            }
            # 2) one element fully enclosed by the other (all six faces invisible)
            $insideA = $true; $insideB = $true
            for ($k = 0; $k -lt 3; $k++) {
                if (-not ($bmin[$k] -ge ($amin[$k] + 0.01) -and $bmax[$k] -le ($amax[$k] - 0.01))) { $insideA = $false }
                if (-not ($amin[$k] -ge ($bmin[$k] + 0.01) -and $amax[$k] -le ($bmax[$k] - 0.01))) { $insideB = $false }
            }
            if ($insideA) { $hits.Add("enclosed:$j in $i") }
            if ($insideB) { $hits.Add("enclosed:$i in $j") }
        }
    }
    return $hits
}

Write-Host ''
Write-Host '== item model geometry (ITEM_OVERLAP) =='
$overlapFail = 0
$overlapRows = @()
if (Test-Path -LiteralPath $itemModels) {
    foreach ($file in (Get-ChildItem $itemModels -Filter *.json | Sort-Object Name)) {
        $id = $file.BaseName
        $model = $null
        try {
            $model = Get-Content -LiteralPath $file.FullName -Raw | ConvertFrom-Json
        } catch {
            $overlapRows += [pscustomobject]@{ model = $id; elements = '-'; verdict = 'FAIL'; detail = 'invalid JSON' }
            $overlapFail++
            continue
        }
        $els = @($model.elements)
        if ($els.Count -eq 0 -or $null -eq $els[0]) { continue }   # flat item: nothing to test
        $hits = @(Get-ItemModelOverlaps $file.FullName)
        $verdict = if ($hits.Count -eq 0) { 'PASS' } else { 'FAIL' }
        if ($hits.Count -gt 0) { $overlapFail++ }
        $detail = '-'
        if ($hits.Count -gt 0) {
            $detail = ($hits | Select-Object -First 3) -join ' '
            if ($hits.Count -gt 3) { $detail += " (+$($hits.Count - 3))" }
        }
        $overlapRows += [pscustomobject]@{
            model = $id; elements = $els.Count; verdict = $verdict; detail = $detail
        }
    }
}
if ($overlapRows.Count -gt 0) {
    $overlapRows | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
} else {
    Write-Host '  (no 3D item models)'
}

# ---- texture audit ---------------------------------------------------------
Write-Host ''
Write-Host '== item texture ink audit =='
$fail = 0
$rows = @()
foreach ($file in (Get-ChildItem $itemTex -Filter *.png | Sort-Object Name)) {
    $name = $file.BaseName
    $bmp = [System.Drawing.Bitmap]::new($file.FullName)
    $W = $bmp.Width; $H = $bmp.Height
    $bb = Get-InkBbox $bmp
    $stats = Get-AlphaStats $bmp
    $semi = [int]$stats[0]
    $dirty = [int]$stats[1]
    $ring = 0
    if ($bb) {
        for ($x = 0; $x -lt $W; $x++) {
            if ($bmp.GetPixel($x, 0).A -gt 0) { $ring++ }
            if ($bmp.GetPixel($x, $H - 1).A -gt 0) { $ring++ }
        }
        for ($y = 0; $y -lt $H; $y++) {
            if ($bmp.GetPixel(0, $y).A -gt 0) { $ring++ }
            if ($bmp.GetPixel($W - 1, $y).A -gt 0) { $ring++ }
        }
    }
    $bmp.Dispose()

    $status = @()
    # Full-bleed by design (three kinds, all deliberate):
    #   * the brush icons (an item/handheld tool silhouette is diagonal by nature),
    #   * every *_screen panel - a screen texture is sampled 1:1 across the
    #     model's screen face, so it must fill its canvas edge to edge,
    #   * every item_* material tile - these are surface materials mapped onto
    #     model faces, not icons, so they are edge-to-edge by definition.
    $ringExempt = ($fullBleed -contains $name) -or ($name -like '*_screen') -or ($name -like 'item_*')
    if ($ring -gt 0 -and -not $ringExempt) { $status += 'RING' }
    if (-not $bb) { $status += 'EMPTY' }
    if ($semi -gt 0) { $status += "SEMI_ALPHA:$semi" }
    # ALPHA_BINARY: strict binary alpha AND clean transparent texels
    $alphaBad = $semi + $dirty
    if ($alphaBad -gt 0) { $status += "ALPHA_BINARY:$alphaBad" }

    $canvasMargins = if ($bb) { '{0}/{1}/{2}/{3}' -f $bb[0], ($W - 1 - $bb[2]), $bb[1], ($H - 1 - $bb[3]) } else { '-' }
    $face = $cardFaces[$name]
    $textBbox = '-'
    $textMargins = '-'
    $faceText = '-'
    if ($face) {
        $fx, $fy, $fw, $fh = $face
        $faceText = '{0},{1} {2}x{3}' -f $fx, $fy, $fw, $fh
        $runs = $script:textRuns[$name]
        if ($runs -and $runs.Count -gt 0) {
            $tx0 = ($runs | Measure-Object -Property X0 -Minimum).Minimum
            $ty0 = ($runs | Measure-Object -Property Y0 -Minimum).Minimum
            $tx1 = ($runs | Measure-Object -Property X1 -Maximum).Maximum
            $ty1 = ($runs | Measure-Object -Property Y1 -Maximum).Maximum
            $textBbox = '{0},{1}-{2},{3}' -f $tx0, $ty0, $tx1, $ty1
            $ml = $tx0 - $fx
            $mr = ($fx + $fw - 1) - $tx1
            $mt = $ty0 - $fy
            $mb = ($fy + $fh - 1) - $ty1
            $textMargins = '{0}/{1}/{2}/{3}' -f $ml, $mr, $mt, $mb
            if ($ml -lt 2 -or $mr -lt 2 -or $mt -lt 2 -or $mb -lt 2) { $status += 'TEXT_MARGIN' }
            foreach ($run in $runs) {
                $rl = $run.X0 - $fx
                $rr = ($fx + $fw - 1) - $run.X1
                $rt = $run.Y0 - $fy
                $rb = ($fy + $fh - 1) - $run.Y1
                if ($rl -lt 2 -or $rr -lt 2 -or $rt -lt 2 -or $rb -lt 2) { $status += "TEXT_MARGIN:$($run.Text)" }
            }
        } else {
            $status += 'NO_TEXT'
        }
    }

    $verdict = if ($status.Count -eq 0) { 'PASS' } else { 'FAIL' }
    if ($verdict -eq 'FAIL') { $fail++ }
    if ($ring -gt 0 -and $ringExempt -and $verdict -eq 'PASS') { $verdict = 'PASS*' }

    $inkText = if ($bb) { '{0},{1}-{2},{3}' -f $bb[0], $bb[1], $bb[2], $bb[3] } else { '-' }
    $rows += [pscustomobject]@{
        name = $name; canvas = "$W x $H"; ink = $inkText
        inkMargins = $canvasMargins; ring = $ring; semi = $semi; face = $faceText; text = $textBbox; textMargins = $textMargins
        status = ($status -join ','); verdict = $verdict
    }
}
$rows | Format-Table -AutoSize | Out-String -Width 220 | Write-Host

# per-string detail for the document cards
Write-Host '== drawn strings (card face coordinates) =='
foreach ($name in $script:textRuns.Keys) {
    $face = $cardFaces[$name]
    if (-not $face) { continue }
    $fx, $fy, $fw, $fh = $face
    foreach ($run in $script:textRuns[$name]) {
        $ml = $run.X0 - $fx; $mr = ($fx + $fw - 1) - $run.X1
        $mt = $run.Y0 - $fy; $mb = ($fy + $fh - 1) - $run.Y1
        $ok = if ($ml -ge 2 -and $mr -ge 2 -and $mt -ge 2 -and $mb -ge 2) { 'ok' } else { 'OUT' }
        Write-Host ("  {0,-16} {1,-18} px {2,4:0.#}  ink {3,3},{4,3} - {5,3},{6,3}  margins L{7} R{8} T{9} B{10}  {11}" -f `
                $name, ("'" + $run.Text + "'"), $run.Size, $run.X0, $run.Y0, $run.X1, $run.Y1, $ml, $mr, $mt, $mb, $ok)
    }
}

Write-Host ''
if ($alphaFixes.Count -gt 0) {
    Write-Host '== alpha hardening (pixels corrected while saving) =='
    foreach ($k in $alphaFixes.Keys) {
        Write-Host ("  {0,-24} {1,4} px" -f $k, [int]$alphaFixes[$k])
    }
    Write-Host ''
}

if ($fail -gt 0 -or $overlapFail -gt 0) {
    if ($fail -gt 0) {
        Write-Host "FAIL: $fail texture(s) outside their card face / canvas border, non-binary alpha, or with semi-transparent pixels" -ForegroundColor Red
    }
    if ($overlapFail -gt 0) {
        Write-Host "FAIL: $overlapFail item model(s) with coincident faces, enclosed elements or invalid JSON (ITEM_OVERLAP)" -ForegroundColor Red
    }
    exit 1
}
Write-Host 'PASS: every item texture is inside its card face, off the canvas border, and fully opaque or fully transparent'
Write-Host ("PASS: ALPHA_BINARY ({0} textures) + ITEM_OVERLAP ({1} item model(s), 0 conflicts)" -f @($rows).Count, @($overlapRows).Count)
