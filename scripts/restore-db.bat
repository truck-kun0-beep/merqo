@REM ===========================================================================
@REM Merqo - restore the database from a backup file.
@REM
@REM Usage:
@REM   scripts\restore-db.bat backups\merqo-20261120-1200.dump
@REM
@REM The restore replaces the contents of the database that is written in .env.
@REM WARNING: everything in that database is lost.
@REM ===========================================================================
@ECHO OFF
SETLOCAL ENABLEDELAYEDEXPANSION
cd /d "%~dp0.."

IF "%~1"=="" (
  ECHO [ERROR] Usage: scripts\restore-db.bat ^<backup-file^>
  EXIT /B 1
)

IF NOT EXIST "%~1" (
  ECHO [ERROR] The backup file "%~1" does not exist.
  EXIT /B 1
)

IF NOT EXIST ".env" (
  ECHO [ERROR] .env not found. Copy .env.example to .env first and fill in the values.
  EXIT /B 1
)

FOR /F "usebackq tokens=1,* delims==" %%A IN (".env") DO (
  IF "%%A"=="DATABASE_URL" SET "DB_URL=%%B"
  IF "%%A"=="DB_USER" SET "DB_USER=%%B"
  IF "%%A"=="DB_PASSWORD" SET "DB_PASSWORD=%%B"
)

IF NOT DEFINED DB_USER SET "DB_USER=postgres"
IF NOT DEFINED DB_PASSWORD SET "DB_PASSWORD=postgres"

IF DEFINED PGBIN (
  SET "PG_RESTORE=%PGBIN%\pg_restore.exe"
) ELSE (
  SET "PG_RESTORE=pg_restore"
)

WHERE %PG_RESTORE% >NUL 2>&1
IF ERRORLEVEL 1 (
  ECHO [ERROR] pg_restore.exe not found.
  EXIT /B 1
)

SET "HOST=localhost"
SET "PORT=5432"
SET "DBNAME=merqo"

FOR /F "tokens=1,2,* delims=//" %%A IN ("!DB_URL!") DO SET "URLPART=%%C"
FOR %%H IN ("!URLPART!") DO SET "HOST=%%~H"
FOR /F "tokens=1,2 delims=:" %%A IN ("!HOST!") DO (
  SET "HOSTONLY=%%A"
  SET "PORTPART=%%B"
)
SET "HOST=%HOSTONLY%"
IF NOT "!PORTPART!"=="" SET "PORT=!PORTPART!"
FOR /F "tokens=3 delims=/" %%D IN ("!DB_URL!") DO SET "DBNAME=%%D"

ECHO [WARN] This replaces the whole content of the database "!DBNAME!" on "!HOST!:!PORT!".
SET /P "ANSWER=Type YES to continue: "
IF /I NOT "%ANSWER%"=="YES" (
  ECHO [INFO] Cancelled.
  EXIT /B 0
)

SET "PGPASSWORD=!DB_PASSWORD!"
%PG_RESTORE% -h "!HOST!" -p "!PORT!" -U "!DB_USER!" -d "!DBNAME!" --clean --if-exists --no-owner "%~1"
IF ERRORLEVEL 1 (
  ECHO [ERROR] The restore failed.
  EXIT /B 1
)

ECHO.
ECHO [DONE] The database was restored from %~1
EXIT /B 0
