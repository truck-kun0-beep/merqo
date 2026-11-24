$files = @(
  'G:\Merqo\backend\src\main\java\com\merqo\catalog\CatalogController.java',
  'G:\Merqo\backend\src\main\java\com\merqo\inventory\InventoryController.java',
  'G:\Merqo\backend\src\main\java\com\merqo\transfer\TransferController.java',
  'G:\Merqo\backend\src\main\java\com\merqo\promotion\PromotionController.java'
)
foreach ($f in $files) {
  $name = Split-Path $f -Leaf
  Write-Output "=== $name ==="
  Select-String -Path $f -Pattern '@(Get|Post|Put|Delete)Mapping' | ForEach-Object {
    Write-Output ("  " + $_.Line.Trim())
  }
}
