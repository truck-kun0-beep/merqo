$source = 'G:\Merqo\logs\phase2-brief-full.txt'
$target = 'G:\Merqo\logs\phase2-brief-readable.txt'
$text = Get-Content -LiteralPath $source -Raw
$decoded = $text -replace '\\n', "`n" -replace '\\"', '"' -replace '\\\\', '\'
$decoded | Set-Content -LiteralPath $target -Encoding utf8
Write-Output ('written ' + $target + ' length ' + $decoded.Length)
