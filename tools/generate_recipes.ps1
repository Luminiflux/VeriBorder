$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$recipes = Join-Path $root 'src\main\resources\data\veriborder\recipes'
New-Item -ItemType Directory -Force $recipes | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)

function Item([string]$id) { return [ordered]@{ item = $id } }
function Shaped([string[]]$pattern, $key, [string]$result, [int]$count = 1) {
    $used = ($pattern -join '').ToCharArray()
    $filtered = [ordered]@{}
    foreach ($entry in $key.GetEnumerator()) {
        if ($used -contains $entry.Key) {
            $filtered[$entry.Key] = $entry.Value
        }
    }
    return [ordered]@{
        type = 'minecraft:crafting_shaped'
        pattern = $pattern
        key = $filtered
        result = [ordered]@{ item = $result; count = $count }
    }
}
function Shapeless($ingredients, [string]$result, [int]$count = 1) {
    return [ordered]@{
        type = 'minecraft:crafting_shapeless'
        ingredients = $ingredients
        result = [ordered]@{ item = $result; count = $count }
    }
}
function WriteRecipe([string]$name, $recipe) {
    [System.IO.File]::WriteAllText((Join-Path $recipes "$name.json"),
        ($recipe | ConvertTo-Json -Depth 12), $utf8)
}

WriteRecipe 'id_card' (Shapeless @(
    (Item 'minecraft:paper'), (Item 'minecraft:paper'), (Item 'minecraft:gold_nugget'),
    (Item 'minecraft:iron_ingot'), (Item 'minecraft:red_dye')
) 'veriborder:id_card')
WriteRecipe 'passport' (Shapeless @(
    (Item 'minecraft:book'), (Item 'minecraft:paper'), (Item 'minecraft:paper'),
    (Item 'minecraft:gold_ingot'), (Item 'minecraft:blue_dye')
) 'veriborder:passport')
WriteRecipe 'hk_macao_permit' (Shapeless @(
    (Item 'minecraft:book'), (Item 'minecraft:paper'), (Item 'minecraft:paper'),
    (Item 'minecraft:blue_dye'), (Item 'minecraft:gold_nugget')
) 'veriborder:hk_macao_permit')

# Admin tools are creative-only: document_modifier and device_brush intentionally
# have no survival recipe. They remain available in the creative tab.

$iron = [ordered]@{
    I = Item 'minecraft:iron_ingot'
    G = Item 'minecraft:glass'
    P = Item 'minecraft:paper'
    B = Item 'minecraft:iron_block'
    R = Item 'minecraft:redstone'
    A = Item 'minecraft:amethyst_shard'
    K = Item 'minecraft:book'
    C = Item 'minecraft:clock'
}
WriteRecipe 'id_issuer' (Shaped @('IGI', 'PBP', 'IRI') $iron 'veriborder:id_issuer')
WriteRecipe 'passport_issuer' (Shaped @('IGI', 'KBK', 'IRI') $iron 'veriborder:passport_issuer')
WriteRecipe 'permit_issuer' (Shaped @('IGI', 'PBP', 'IKI') $iron 'veriborder:permit_issuer')
WriteRecipe 'visa_issuer' (Shaped @('IGI', 'ABA', 'IRI') $iron 'veriborder:visa_issuer')
WriteRecipe 'endorsement_machine' (Shaped @('IGI', 'KBK', 'IAI') $iron 'veriborder:endorsement_machine')
WriteRecipe 'hotel_reader' (Shaped @('IGI', 'IRI', 'PPP') $iron 'veriborder:hotel_reader')
WriteRecipe 'desk_scanner' (Shaped @('GGG', 'RAR', 'IBI') $iron 'veriborder:desk_scanner')
WriteRecipe 'manual_terminal' (Shaped @('IGI', 'RKR', 'IBI') $iron 'veriborder:manual_terminal')
WriteRecipe 'airport_checkin' (Shaped @('GGG', 'IRI', 'PBP') $iron 'veriborder:airport_checkin')
WriteRecipe 'station_verifier' (Shaped @('IGI', 'IRI', 'IBI') $iron 'veriborder:station_verifier')
WriteRecipe 'egate' (Shaped @('IBI', 'GRG', 'IPI') $iron 'veriborder:egate')
WriteRecipe 'exit_gate' (Shaped @('IBI', 'GRG', 'IPR') ([ordered]@{
    I = Item 'minecraft:iron_ingot'
    B = Item 'minecraft:iron_block'
    G = Item 'minecraft:glass'
    R = Item 'minecraft:redstone'
    P = Item 'minecraft:red_dye'
}) 'veriborder:exit_gate')
WriteRecipe 'self_endorsement' (Shaped @('IGI', 'ABA', 'IRI') ([ordered]@{
    I = Item 'minecraft:iron_ingot'
    G = Item 'minecraft:glass'
    A = Item 'minecraft:amethyst_shard'
    B = Item 'minecraft:iron_block'
    R = Item 'minecraft:redstone'
}) 'veriborder:self_endorsement')
WriteRecipe 'self_visa' (Shaped @('IGI', 'PBP', 'IRI') ([ordered]@{
    I = Item 'minecraft:iron_ingot'
    G = Item 'minecraft:glass'
    P = Item 'minecraft:paper'
    B = Item 'minecraft:iron_block'
    R = Item 'minecraft:redstone'
}) 'veriborder:self_visa')
WriteRecipe 'entry_stamp' (Shaped @('IGI', 'RPR', 'I I') $iron 'veriborder:entry_stamp')
WriteRecipe 'exit_stamp' (Shaped @('IGI', 'RPR', 'I I') ([ordered]@{
    I = Item 'minecraft:iron_ingot'
    G = Item 'minecraft:glass'
    R = Item 'minecraft:redstone'
    P = Item 'minecraft:blue_dye'
}) 'veriborder:exit_stamp')
WriteRecipe 'stamp_history' (Shaped @('IGI', 'RKR', 'PPP') $iron 'veriborder:stamp_history')
WriteRecipe 'loss_terminal' (Shaped @('IGI', 'RKR', 'PBP') ([ordered]@{
    I = Item 'minecraft:iron_ingot'
    G = Item 'minecraft:glass'
    R = Item 'minecraft:redstone'
    K = Item 'minecraft:book'
    P = Item 'minecraft:black_dye'
    B = Item 'minecraft:iron_block'
}) 'veriborder:loss_terminal')
WriteRecipe 'renewal_machine' (Shaped @('IGI', 'RCR', 'PBP') $iron 'veriborder:renewal_machine')
WriteRecipe 'print_preview' (Shaped @('IGI', 'RPR', 'IBI') ([ordered]@{
    I = Item 'minecraft:iron_ingot'
    G = Item 'minecraft:glass'
    R = Item 'minecraft:redstone'
    P = Item 'minecraft:paper'
    B = Item 'minecraft:iron_block'
}) 'veriborder:print_preview')
WriteRecipe 'blacklist_terminal' (Shaped @('IGI', 'RKR', 'IBP') ([ordered]@{
    I = Item 'minecraft:iron_ingot'
    G = Item 'minecraft:glass'
    R = Item 'minecraft:redstone'
    K = Item 'minecraft:book'
    P = Item 'minecraft:black_dye'
    B = Item 'minecraft:iron_block'
}) 'veriborder:blacklist_terminal')

Get-ChildItem $recipes -Filter *.json | Measure-Object | ForEach-Object { Write-Host "Generated recipes: $($_.Count)" }
