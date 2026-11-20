@REM ===========================================================================
@REM Merqo - export the database to a backup file.
@REM
@REM Supabase free has no automatic backups, so this script is the safety net.
@REM It writes a .dump file into the "backups" folder in the repository.
@REM
@REM Restore it with scripts\restore-db.bat.
@REM ===========================================================================
@ECHO OFF
SETLOCAL ENABLEDELAYEDEXPANSION
cd /d "%~dp0.."

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
  SET "PGDUMP=%PGBIN%\pg_dump.exe"
) ELSE (
  SET "PGDUMP=pg_dump"
)

WHERE %PGDUMP% >NUL 2>&1
IF ERRORLEVEL 1 (
  ECHO [ERROR] pg_dump.exe not found.
  ECHO         Install PostgreSQL and add its bin folder to the PATH, or set PGBIN.
  EXIT /B 1
)

REM ---- Read the host, port and database name out of the JDBC url ------------
SET "HOST=localhost"
SET "PORT=5432"
SET "DBNAME=merqo"

FOR /F "tokens=1,2,* delims=//" %%A IN ("!DB_URL!") DO SET "URLPART=%%C"
FOR %%H IN ("!URLPART!") DO (
  SET "HOST=%%~H"
)
FOR /F "tokens=1,2 delims=:" %%A IN ("!HOST!") DO (
  SET "HOSTONLY=%%A"
  SET "PORTPART=%%B"
)
SET "HOST=%HOSTONLY%"
IF NOT "!PORTPART!"=="" SET "PORT=!PORTPART!"
FOR /F "tokens=3 delims=/" %%D IN ("!DB_URL!") DO SET "DBNAME=%%D"

IF NOT EXIST "backups" MKDIR "backups"

SET "STAMP=%DATE:~-4%%DATE:~3,2%%DATE:~0,2%-%TIME:~0,2%%TIME:~3,2%"
SET "STAMP=%STAMP: =0%"
SET "BACKUPFILE=backups\merqo-%STAMP%.dump"

ECHO [INFO] Exporting database "!DBNAME!" from "!HOST!:!PORT!" ...
ECHO [INFO] Target file: %BACKUPFILE%

SET "PGPASSWORD=!DB_PASSWORD!"
%PGDUMP% -h "!HOST!" -p "!PORT!" -U "!DB_USER!" -d "!DBNAME!" -F p --clean --if-exists -f "%BACKUPFILE%"
IF ERRORLEVEL 1 (
  ECHO [ERROR] The export failed.
  EXIT /B 1
)

ECHO.
ECHO [DONE] Backup written to %BACKUPFILE%
EXIT /B 0
