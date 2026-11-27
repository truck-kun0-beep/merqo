@REM ===========================================================================
@REM Merqo - run every test cleanly and repeatably.
@REM
@REM   * Drops and recreates "merqo_test" so repeated full-suite runs start clean
@REM   * Backend  : JUnit 5 unit tests and Spring Boot integration tests
@REM                 against the real PostgreSQL database "merqo_test"
@REM   * Frontend : Vitest and ESLint
@REM
@REM The backend tests need a running PostgreSQL server on localhost:5432.
@REM ===========================================================================
@ECHO OFF
SETLOCAL ENABLEDELAYEDEXPANSION
cd /d "%~dp0.."

REM The pinned JDK is used here for the same reason as in run-backend.bat: the
REM google-java-format that Spotless runs needs a JDK 21, and it fails on a
REM newer one with "JCCompilationUnit does not have member field endPositions".
IF EXIST "G:\tools\jdk21\jdk-21.0.13.1+2\bin\java.exe" (
  SET "JAVA_HOME=G:\tools\jdk21\jdk-21.0.13.1+2"
  SET "PATH=!JAVA_HOME!\bin;!PATH!"
)

SET "DB_USER=postgres"
SET "DB_PASSWORD=postgres"
IF EXIST ".env" (
  FOR /F "usebackq tokens=1,* delims==" %%A IN (".env") DO (
    IF "%%A"=="TEST_DB_USER" SET "DB_USER=%%B"
    IF "%%A"=="TEST_DB_PASSWORD" SET "DB_PASSWORD=%%B"
  )
)

ECHO ============================================================
ECHO  Reset merqo_test database
ECHO ============================================================
SET "PSQL="
IF DEFINED PGBIN (
  IF EXIST "%PGBIN%\psql.exe" SET "PSQL=%PGBIN%\psql.exe"
)
IF NOT DEFINED PSQL (
  WHERE psql >NUL 2>&1
  IF NOT ERRORLEVEL 1 SET "PSQL=psql"
)

IF DEFINED PSQL (
  SET "PGPASSWORD=!DB_PASSWORD!"
  "!PSQL!" -h localhost -p 5432 -U "!DB_USER!" -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS merqo_test WITH (FORCE);" -c "CREATE DATABASE merqo_test;"
  IF ERRORLEVEL 1 (
    ECHO [ERROR] Could not recreate merqo_test via psql.
    EXIT /B 1
  )
  SET "PGPASSWORD="
) ELSE (
  SET "PGJAR="
  FOR /R "%USERPROFILE%\.m2\repository\org\postgresql\postgresql" %%F IN (postgresql-*.jar) DO (
    ECHO %%~nxF | findstr /C:"sources" /C:"javadoc" >NUL
    IF ERRORLEVEL 1 SET "PGJAR=%%F"
  )
  IF NOT DEFINED PGJAR (
    ECHO [ERROR] Neither psql.exe nor PostgreSQL JDBC jar was found to reset merqo_test.
    EXIT /B 1
  )
  SET "RESET_JAVA=%TEMP%\MerqoResetTestDb.java"
  (
    ECHO import java.sql.Connection;
    ECHO import java.sql.DriverManager;
    ECHO import java.sql.Statement;
    ECHO public class MerqoResetTestDb {
    ECHO   public static void main^(String[] args^) throws Exception {
    ECHO     try ^(Connection c = DriverManager.getConnection^("jdbc:postgresql://localhost:5432/postgres", args[0], args[1]^);
    ECHO          Statement s = c.createStatement^(^)^) {
    ECHO       s.execute^("DROP DATABASE IF EXISTS merqo_test WITH (FORCE)"^);
    ECHO       s.execute^("CREATE DATABASE merqo_test"^);
    ECHO       System.out.println^("[OK]   Recreated merqo_test cleanly."^);
    ECHO     }
    ECHO   }
    ECHO }
  ) > "!RESET_JAVA!"
  java -cp "!PGJAR!" "!RESET_JAVA!" "!DB_USER!" "!DB_PASSWORD!"
  SET "RESET_ERR=!ERRORLEVEL!"
  DEL /Q "!RESET_JAVA!" >NUL 2>&1
  IF NOT "!RESET_ERR!"=="0" (
    ECHO [ERROR] Could not recreate merqo_test via JDBC.
    EXIT /B 1
  )
)

SET "RESULT=0"

ECHO.
ECHO ============================================================
ECHO  Backend tests
ECHO ============================================================
CALL backend\mvnw.cmd -f backend\pom.xml -B clean verify
IF ERRORLEVEL 1 (
  ECHO [ERROR] The backend tests failed.
  SET "RESULT=1"
)

ECHO.
ECHO ============================================================
ECHO  Frontend tests
ECHO ============================================================
PUSHD frontend
IF NOT EXIST "node_modules" CALL npm install
CALL npm run test
IF ERRORLEVEL 1 (
  ECHO [ERROR] The frontend tests failed.
  SET "RESULT=1"
)
CALL npm run lint
IF ERRORLEVEL 1 (
  ECHO [ERROR] The frontend lint failed.
  SET "RESULT=1"
)
POPD

ECHO.
IF "%RESULT%"=="0" (
  ECHO [DONE] All tests passed.
) ELSE (
  ECHO [FAIL] Some tests failed.
)
EXIT /B %RESULT%
