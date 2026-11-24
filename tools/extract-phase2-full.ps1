$source = 'G:\Merqo\logs\notebook-archives.txt'
$target = 'G:\Merqo\logs\phase2-brief-full.txt'
$lines = Get-Content -LiteralPath $source
$line = $lines[333]
$out = New-Object System.Collections.Generic.List[string]
$out.Add($line)
$out | Set-Content -LiteralPath $target -Encoding utf8
Write-Output ('written ' + $target + ' length ' + $line.Length)
