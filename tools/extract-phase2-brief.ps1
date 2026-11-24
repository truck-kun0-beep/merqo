$source = 'G:\Merqo\logs\notebook-full.txt'
$target = 'G:\Merqo\logs\phase2-brief-extract.txt'
$text = Get-Content -LiteralPath $source -Raw
$needle = 'Merqo, Phase 2: Catalog'
$index = $text.IndexOf($needle)
if ($index -lt 0) {
    Write-Output 'needle not found'
    exit 1
}
$start = [Math]::Max(0, $index - 200)
$length = [Math]::Min(20000, $text.Length - $start)
$text.Substring($start, $length) | Set-Content -LiteralPath $target -Encoding utf8
Write-Output ('written ' + $target + ' from index ' + $index)
