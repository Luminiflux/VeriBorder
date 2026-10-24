# Builds all 4 stamp/print devices: .blend + .obj + front preview PNG.
$ErrorActionPreference = 'Stop'
$root = "C:\Users\GhostBubbles\Documents\deepseek-harness\default-workspace\VeriBorder"
$blender = "D:\Steam\steamapps\common\Blender\blender.exe"
$buildDir = Join-Path $root 'assets\blender\_build'
$script = Join-Path $buildDir 'build_device.py'

$devices = @(
    @{ id='entry_stamp';    base='#F3EBDD'; base2='#E7D6BC'; trim='#5B4C3A'; accent='#D98A2B'; fg='#FFD9A0'; bg='#1C1206' },
    @{ id='exit_stamp';     base='#E7ECF2'; base2='#CDD8E4'; trim='#3E4A57'; accent='#3D6FA8'; fg='#A9D4FF'; bg='#08131F' },
    @{ id='stamp_history';  base='#EFE8DA'; base2='#DFD2BA'; trim='#574C3C'; accent='#C08A36'; fg='#FFE3A8'; bg='#1A1408' },
    @{ id='print_preview';  base='#EDEDEA'; base2='#DADAD4'; trim='#4A4A45'; accent='#D9534F'; fg='#FFB4AE'; bg='#141414' }
)

foreach ($d in $devices) {
    $id = $d.id
    $modelJson = Join-Path $root "src\main\resources\assets\veriborder\models\block\$id.json"
    $outDir = Join-Path $root "assets\blender\$id"
    $cfg = [ordered]@{
        id = $id
        model_json = $modelJson
        out_dir = $outDir
        palette = [ordered]@{
            base = $d.base; base2 = $d.base2; trim = $d.trim
            accent = $d.accent; fg = $d.fg; bg = $d.bg
        }
    }
    $cfgPath = Join-Path $buildDir "$id.json"
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $cfg | ConvertTo-Json -Depth 5 | Set-Content -Path $cfgPath -Encoding UTF8

    $env:VB_CONFIG = $cfgPath
    Write-Output "=== building $id ==="
    & $blender --background --python $script 2>&1 | Select-String -Pattern "BUILT|Saved:|Traceback|Error:|OBJ export" | ForEach-Object { $_.ToString() }
}

Write-Output "=== ALL DONE ==="
foreach ($d in $devices) {
    $outDir = Join-Path $root "assets\blender\$($d.id)"
    $png = Join-Path $outDir "$($d.id)_front.png"
    $obj = Join-Path $outDir "$($d.id).obj"
    $blend = Join-Path $outDir "$($d.id).blend"
    Write-Output ("{0,-14} png={1} obj={2} blend={3}" -f $d.id, (Test-Path $png), (Test-Path $obj), (Test-Path $blend))
}
