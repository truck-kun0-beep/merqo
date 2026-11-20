@REM ===========================================================================
@REM Merqo - load the demo data.
@REM
@REM The demo seeder is part of Prompt 10 (the demo mode and seed data prompt).
@REM In this bootstrap there is no demo data yet, so this script checks the
@REM configuration and says plainly that there is nothing to seed.
@REM
@REM Later the seeder is added to the backend as a Spring Boot ApplicationRunner
@REM and this script calls it with the "seed" profile and then exits.
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

ECHO [INFO] DEMO_MODE is true, so the demo deployment would show seeded data.
ECHO [INFO] The demo seeder has not been written yet: it belongs to the prompt
ECHO        that builds the demo mode. Nothing was changed in the database.
EXIT /B 0
