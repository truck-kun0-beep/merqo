$ErrorActionPreference = 'Continue'
$base = 'http://localhost:18080/api/v1'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# Sign in as the demo owner.
$login = Invoke-WebRequest -Uri "$base/demo/login" -Method Post -WebSession $session `
    -ContentType 'application/json' -Body '{"accountKey":"owner"}'
Write-Output ('login status: ' + $login.StatusCode)

function Show($label, $uri) {
    try {
        $r = Invoke-WebRequest -Uri $uri -WebSession $session -UseBasicParsing
        Write-Output ("=== " + $label + " (" + $r.StatusCode + ")")
        Write-Output $r.Content
    } catch {
        Write-Output ("=== " + $label + " ERROR")
        Write-Output ($_.Exception.Message)
    }
}

Show 'categories' "$base/catalog/categories"
Show 'brands' "$base/catalog/brands"
Show 'products' "$base/catalog/products?size=2"
Show 'variants' "$base/catalog/variants?size=2"
Show 'promotions' "$base/promotions"
Show 'inventory-stock' "$base/inventory/stock?size=2"
Show 'inventory-totals' "$base/inventory/stock/totals"
Show 'inventory-movements' "$base/inventory/movements?size=2"
Show 'counts' "$base/inventory/counts"
Show 'transfers' "$base/transfers"
