$ErrorActionPreference = 'Stop'
$root = 'G:\Merqo\backend\src\main\java\com\merqo'

Write-Output '=== FILE SIZES (lines) ==='
$rows = @()
Get-ChildItem -Path $root -Recurse -Filter *.java | ForEach-Object {
    $n = (Get-Content $_.FullName | Measure-Object -Line).Lines
    $rows += [pscustomobject]@{ Lines = $n; Path = $_.FullName.Substring($root.Length + 1) }
}
$rows | Sort-Object Path | ForEach-Object { '{0,6}  {1}' -f $_.Lines, $_.Path }

Write-Output ''
Write-Output '=== ENDPOINTS ==='
Get-ChildItem -Path $root -Recurse -Filter *Controller.java | ForEach-Object {
    $file = $_
    $base = Select-String -Path $file.FullName -Pattern '@RequestMapping\("([^"]+)"\)' | Select-Object -First 1
    $basePath = ''
    if ($base) { $basePath = $base.Matches[0].Groups[1].Value }
    Write-Output ("-- " + $file.Name + "  base=" + $basePath)
    Select-String -Path $file.FullName -Pattern '@(Get|Post|Put|Delete|Patch)Mapping(\("([^"]*)"\))?' | ForEach-Object {
        Write-Output ("     " + $_.Line.Trim())
    }
}

Write-Output ''
Write-Output '=== INVENTORY / TRANSFER / CATALOG SERVICE METHODS ==='
foreach ($f in @('catalog\CatalogService.java', 'inventory\StockService.java', 'inventory\InventoryService.java', 'inventory\InventoryLedger.java', 'transfer\TransferService.java', 'promotion\PromotionService.java')) {
    $p = Join-Path $root $f
    if (Test-Path $p) {
        Write-Output ("-- " + $f)
        Select-String -Path $p -Pattern '^\s+public\s+[\w<>,\[\]\. ?]+\s+\w+\(' | ForEach-Object { Write-Output ("     " + $_.Line.Trim()) }
    }
}
