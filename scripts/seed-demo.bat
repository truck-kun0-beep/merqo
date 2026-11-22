@REM ===========================================================================
@REM Merqo - load the demo data.
@REM
@REM The demo data is written by the backend itself, not by this script. The
@REM DemoSeeder runs at every start when DEMO_MODE is true, and every
@REM contributor is idempotent: running it twice gives the same rows as running
@REM it once. Nothing else has to be done.
@REM
@REM This script starts the backend once with the dev profile (where demo mode
@REM is on). Wait for the line "The demo data is ready." in the console, then
@REM press Ctrl+C to stop the backend again.
@REM ===========================================================================
@ECHO OFF
SETLOCAL
cd /d "%~dp0.."

IF NOT EXIST ".env" (
  ECHO [ERROR] .env not found. Copy .env.example to .env first and fill in the values.
  EXIT /B 1
)

FOR /F "usebackq tokens=1,* delims==" %%A IN (".env") DO (
  IF "%%A"=="DEMO_MODE" SET "DEMO_MODE=%%B"
)

IF NOT DEFINED DEMO_MODE SET "DEMO_MODE=true"

IF /I NOT "%DEMO_MODE%"=="true" (
  ECHO [INFO] DEMO_MODE is "%DEMO_MODE%" in .env.
  ECHO        Demo data is only loaded when DEMO_MODE=true.
  EXIT /B 0
)

IF NOT EXIST "backend\target\merqo-backend-0.0.1-SNAPSHOT.jar" (
  ECHO [INFO] Building the backend jar ...
  CALL backend\mvnw.cmd -f backend\pom.xml -B -DskipTests package
  IF ERRORLEVEL 1 (
    ECHO [ERROR] The backend build failed.
    EXIT /B 1
  )
)

ECHO [INFO] Starting the backend once so the demo data is written ...
ECHO [INFO] Wait for "The demo data is ready." and then press Ctrl+C.
PUSHD backend
CALL mvnw.cmd -B spring-boot:run -Dspring-boot.run.profiles=dev
POPD

ECHO [INFO] The demo data is in the database now.
EXIT /B 0
