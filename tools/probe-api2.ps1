$ErrorActionPreference = 'Continue'
$base = 'http://localhost:18080/api/v1'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# A GET makes the server write the CSRF cookie.
Invoke-WebRequest -Uri "$base/health" -WebSession $session -UseBasicParsing | Out-Null

$token = $session.Cookies.GetCookies('http://localhost:18080')['XSRF-TOKEN']
if ($token -eq $null) {
    Write-Output 'no csrf cookie'
    exit 1
}
Write-Output ('csrf token: ' + $token.Value)

$headers = @{ 'X-XSRF-TOKEN' = $token.Value }

$login = Invoke-WebRequest -Uri "$base/demo/login" -Method Post -WebSession $session -Headers $headers `
    -UseBasicParsing -ContentType 'application/json' -Body '{"accountKey":"owner"}'
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
Show 'inventory-stock' "$base/inventory/stock"
Show 'inventory-totals' "$base/inventory/stock/totals"
Show 'inventory-movements' "$base/inventory/movements?size=2"
Show 'counts' "$base/inventory/counts"
Show 'transfers' "$base/transfers"
