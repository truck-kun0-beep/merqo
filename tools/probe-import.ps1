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

# A csv with one bad row, so the validation answer shows its shape.
$csv = "product_code,product_name,category,brand,color,size,other,sku,barcode,selling_price,cost,opening_qty`n"
$csv += "TT-001,Probe Tee,T-Shirts,Northline,Black,M,,TT-001-BLK-M,2000000009001,750,400,10`n"
$csv += "TT-002,Bad Row,T-Shirts,Northline,Blue,L,,TT-002-BLU-L,2000000009002,notanumber,400,5`n"
$bytes = [System.Text.Encoding]::UTF8.GetBytes($csv)
$boundary = '----merqoprobe'
$body = "--$boundary`r`n"
$body += "Content-Disposition: form-data; name=`"file`"; filename=`"probe.csv`"`r`n"
$body += "Content-Type: text/csv`r`n`r`n"
$body += $csv + "`r`n"
$body += "--$boundary--`r`n"
$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)

try {
    $r = Invoke-WebRequest -Uri "$base/catalog/import/validate" -Method Post -WebSession $session -Headers $headers `
        -UseBasicParsing -ContentType "multipart/form-data; boundary=$boundary" -Body $bodyBytes
    Write-Output ("=== import-validate (" + $r.StatusCode + ")")
    Write-Output $r.Content
} catch {
    Write-Output "=== import-validate ERROR"
    Write-Output $_.Exception.Message
}

Show 'adjustments' "$base/inventory/adjustments"
Show 'policies-empty' "$base/inventory/policies?locationId=00000000-0000-0000-0000-000000000000&variantId=00000000-0000-0000-0000-000000000000"
