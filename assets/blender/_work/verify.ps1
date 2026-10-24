$ErrorActionPreference = 'Stop'
$base = "C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder"
$modelDir = "$base\src\main\resources\assets\veriborder\models\block"
$texDir = "$base\src\main\resources\assets\veriborder\textures\block"
$blenderDir = "$base\assets\blender"
$ids = @("id_issuer","passport_issuer","permit_issuer","visa_issuer","endorsement_machine","self_visa","self_endorsement")

$allOk = $true
$previews = @()
foreach ($id in $ids) {
  $modelPath = Join-Path $modelDir "$id.json"
  $m = Get-Content $modelPath -Raw | ConvertFrom-Json
  $n = $m.elements.Count
  $problems = @()

  if ($n -lt 10) { $problems += "elements=$n (<10)" }

  # texture map keys -> file resolution
  $missingTex = @()
  foreach ($slot in $m.textures.PSObject.Properties.Name) {
    $ref = $m.textures.$slot
    if ($ref -like "veriborder:block/*") {
      $texName = ($ref -split "/")[-1]
      $png = Join-Path $texDir "$texName.png"
      if (-not (Test-Path $png)) { $missingTex += "$ref" }
    }
  }
  if ($missingTex.Count -gt 0) { $problems += ("missing-textures: " + ($missingTex -join ", ")) }

  # face references resolve to a texture-map key
  $keys = @($m.textures.PSObject.Properties.Name)
  $badFace = @()
  foreach ($el in $m.elements) {
    if ($el.from.Count -ne 3 -or $el.to.Count -ne 3) { $badFace += "bad-from/to" }
    foreach ($axis in 0..2) {
      if ([double]$el.from[$axis] -gt [double]$el.to[$axis]) { $badFace += "inverted-axis" }
      if ([double]$el.to[$axis] -gt 16.001) { $badFace += "to>16" }
    }
    foreach ($face in @("up","down","north","south","east","west")) {
      if (-not ($el.faces.PSObject.Properties.Name -contains $face)) { $badFace += "missing-face:$face"; continue }
      $texRef = $el.faces.$face.texture
      if ($texRef -notlike "#*") { $badFace += "bad-face-ref:$texRef"; continue }
      $slotName = $texRef.Substring(1)
      if ($keys -notcontains $slotName) { $badFace += "unresolved-slot:$slotName" }
    }
  }
  if ($badFace.Count -gt 0) { $problems += ("bad-faces: " + (($badFace | Select-Object -Unique) -join ", ")) }

  # LED color texture presence (>=4 light colors requirement)
  $ledColors = 0
  foreach ($slot in @("led_green","led_blue","led_red","led_amber","screen","screen2","accent")) {
    $png = Join-Path $texDir "$id`_$slot.png"
    if (Test-Path $png) { $ledColors++ }
  }

  # artifacts present
  $blend = Join-Path $blenderDir "$id\$id.blend"
  $obj = Join-Path $blenderDir "$id\$id.obj"
  $preview = Join-Path $blenderDir "$id\preview.png"
  $previews += $preview
  if (-not (Test-Path $blend)) { $problems += "missing-blend" }
  if (-not (Test-Path $obj)) { $problems += "missing-obj" }
  if (-not (Test-Path $preview)) { $problems += "missing-preview" }

  $status = if ($problems.Count -eq 0) { "PASS" } else { "FAIL" }
  if ($problems.Count -gt 0) { $allOk = $false }
  Write-Output ("{0,-22} {1}  elements={2,-3} ledColors={3}  {4}" -f $id, $status, $n, $ledColors, ($problems -join " | "))
}

Write-Output ""
Write-Output ("ALL: " + $(if ($allOk) { "PASS" } else { "FAIL" }))
Write-Output ""
Write-Output "PREVIEW PATHS:"
foreach ($p in $previews) { Write-Output ("  " + $p) }
