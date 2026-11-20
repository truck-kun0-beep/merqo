$ErrorActionPreference = 'Continue'
Set-Location 'G:\Merqo'

# Drop and recreate merqo_test, then run the backend tests against the empty
# database. This is the "run-tests.bat passes from a clean database" check.
$java = 'G:\tools\jdk21\jdk-21.0.13.1+2\bin\java.exe'
$driver = 'G:\tools\postgresql-42.7.4.jar'
$create = 'G:\Merqo\tools\RecreateDb.java'

$source = @"
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class RecreateDb {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:5432/postgres";
        try (Connection conn = DriverManager.getConnection(url, "postgres", "");
             Statement st = conn.createStatement()) {
            st.executeUpdate("DROP DATABASE IF EXISTS " + args[0] + " WITH (FORCE)");
            st.executeUpdate("CREATE DATABASE " + args[0]);
            System.out.println("RECREATED " + args[0]);
        }
    }
}
"@
Set-Content -Path $create -Value $source -Encoding utf8

& $java -cp $driver $create merqo_test
if ($LASTEXITCODE -ne 0) { Write-Output 'FAILED to recreate'; exit 1 }

Set-Location 'G:\Merqo\backend'
$output = & cmd /c 'mvnw.cmd -B clean verify 2>&1'
$exit = $LASTEXITCODE

$output | Select-String -Pattern 'Tests run:|BUILD|Spotless.Java is keeping' | ForEach-Object { $_.Line.Trim() }
Write-Output "MAVEN_EXIT=$exit"
exit $exit
