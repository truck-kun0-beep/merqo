# Runs one SQL statement against the test database and prints the rows.
#
# It uses the PostgreSQL driver of the Maven repository, so no psql client is
# needed.
#
# Usage: scripts\sql.ps1 "select count(*) from app_users"
param(
    [Parameter(Mandatory = $true)]
    [string] $Sql,

    [string] $Database = 'merqo_test'
)

$ErrorActionPreference = 'Stop'

$driver = Get-ChildItem "$env:USERPROFILE\.m2\repository\org\postgresql\postgresql" -Recurse `
    -Filter "postgresql-*.jar" | Where-Object { $_.Name -notlike '*sources*' } |
    Select-Object -First 1 -ExpandProperty FullName

if (-not $driver) {
    Write-Output 'The PostgreSQL driver was not found in the Maven repository.'
    exit 1
}

$java = 'G:\tools\jdk21\jdk-21.0.13.1+2\bin\java.exe'
$source = Join-Path $env:TEMP 'MerqoSql.java'

$program = @"
import java.sql.*;

public class MerqoSql {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://localhost:5432/" + args[1];
        try (Connection connection = DriverManager.getConnection(url, "postgres", "postgres");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(args[0])) {
            ResultSetMetaData meta = rows.getMetaData();
            int columns = meta.getColumnCount();
            StringBuilder header = new StringBuilder();
            for (int index = 1; index <= columns; index++) {
                if (index > 1) { header.append(" | "); }
                header.append(meta.getColumnLabel(index));
            }
            System.out.println(header);
            System.out.println("-".repeat(header.length()));
            while (rows.next()) {
                StringBuilder line = new StringBuilder();
                for (int index = 1; index <= columns; index++) {
                    if (index > 1) { line.append(" | "); }
                    Object value = rows.getObject(index);
                    line.append(value == null ? "" : value.toString());
                }
                System.out.println(line);
            }
        }
    }
}
"@

Set-Content -Path $source -Value $program -Encoding UTF8 -NoNewline

$withoutBom = Get-Content $source -Raw
[System.IO.File]::WriteAllText($source, $withoutBom.TrimStart([char] 0xFEFF))

& 'G:\tools\jdk21\jdk-21.0.13.1+2\bin\javac.exe' -cp $driver -d $env:TEMP $source 2>&1 | Out-Null

& $java -cp "$driver;$env:TEMP" MerqoSql $Sql $Database
