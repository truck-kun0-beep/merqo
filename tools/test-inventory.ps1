$root = 'G:\Merqo\backend\src\test\java'
$out = 'G:\Merqo\logs\test-inventory-current.txt'
$lines = New-Object System.Collections.Generic.List[string]
Get-ChildItem -LiteralPath $root -Recurse -Filter *.java | Sort-Object FullName | ForEach-Object {
    $file = $_
    $rel = $file.FullName.Replace('G:\Merqo\backend\src\test\java\', '')
    $lines.Add('')
    $lines.Add('### ' + $rel)
    $content = Get-Content -LiteralPath $file.FullName
    $display = ''
    foreach ($line in $content) {
        if ($line -match '@DisplayName\("([^"]*)"\)') {
            $display = $matches[1]
        }
        if ($line -match '^\s+(?:public\s+|private\s+)?void\s+([A-Za-z0-9_]+)\s*\(\s*\)') {
            $name = $matches[1]
            if ($name -notmatch '^get$|^set$') {
                $lines.Add('  - ' + $name + '  :: ' + $display)
                $display = ''
            }
        }
    }
}
$lines | Set-Content -LiteralPath $out -Encoding utf8
Write-Output ('classes and methods written to ' + $out)
