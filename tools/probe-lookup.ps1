$ErrorActionPreference = 'Continue'
$base = 'http://localhost:18080/api/v1'
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession

Invoke-WebRequest -Uri "$base/health" -WebSession $session -UseBasicParsing | Out-Null
$token = $session.Cookies.GetCookies('http://localhost:18080')['XSRF-TOKEN']
$headers = @{ 'X-XSRF-TOKEN' = $token.Value }
Invoke-WebRequest -Uri "$base/demo/login" -Method Post -WebSession $session -Headers $headers `
    -UseBasicParsing -ContentType 'application/json' -Body '{"accountKey":"owner"}' | Out-Null

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

Show 'lookup' "$base/catalog/lookup?barcode=890TE100BLK01&scope=POS"
Show 'price-preview' "$base/catalog/variants/e06241dd-2728-4a25-b908-3945552e6f37/price-preview?scope=POS&quantity=2"
Show 'variant-detail' "$base/catalog/variants/e06241dd-2728-4a25-b908-3945552e6f37"
Show 'import-template' "$base/catalog/import/template"
Show 'policy' "$base/inventory/policies?locationId=x&variantId=y"
