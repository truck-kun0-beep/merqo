@REM ===========================================================================
@REM Merqo - run every test.
@REM
@REM   * Backend  : JUnit 5 unit tests and Spring Boot integration tests
@REM                 against the real PostgreSQL database "merqo_test"
@REM   * Frontend : Vitest
@REM
@REM The backend tests need a running PostgreSQL with the "merqo_test" database.
@REM Run scripts\setup-db.bat once before the first run.
@REM ===========================================================================
@ECHO OFF
SETLOCAL
cd /d "%~dp0.."

SET "RESULT=0"

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
