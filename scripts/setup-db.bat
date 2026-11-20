@REM ===========================================================================
@REM Merqo - create the local PostgreSQL databases.
@REM
@REM Creates the databases "merqo" (development) and "merqo_test" (tests) and
@REM makes sure the "merqo" user can log in with the password in .env.
@REM
@REM Requirements:
@REM   * PostgreSQL installed and running
@REM   * psql.exe on the PATH, or PGBIN pointing at the bin folder
@REM   * a .env file in the repository root (see .env.example)
@REM ===========================================================================
@ECHO OFF
SETLOCAL ENABLEDELAYEDEXPANSION
cd /d "%~dp0.."

IF NOT EXIST ".env" (
  ECHO [ERROR] .env not found. Copy .env.example to .env first and fill in the values.
  EXIT /B 1
)

REM ---- Read the database settings from .env ---------------------------------
FOR /F "usebackq tokens=1,* delims==" %%A IN (".env") DO (
  IF "%%A"=="DATABASE_URL" SET "DB_URL=%%B"
  IF "%%A"=="DB_USER" SET "DB_USER=%%B"
  IF "%%A"=="DB_PASSWORD" SET "DB_PASSWORD=%%B"
)

IF NOT DEFINED DB_USER SET "DB_USER=postgres"
IF NOT DEFINED DB_PASSWORD SET "DB_PASSWORD=postgres"

REM ---- Find psql ------------------------------------------------------------
IF DEFINED PGBIN (
  SET "PSQL=%PGBIN%\psql.exe"
) ELSE (
  SET "PSQL=psql"
)

WHERE %PSQL% >NUL 2>&1
IF ERRORLEVEL 1 (
  ECHO [ERROR] psql.exe not found.
  ECHO         Install PostgreSQL and add its bin folder to the PATH, or set PGBIN.
  EXIT /B 1
)

ECHO [INFO] Creating the databases "merqo" and "merqo_test" ...

FOR %%D IN (merqo merqo_test) DO (
  ECHO %%D | %PSQL% -h localhost -p 5432 -U %DB_USER% -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='%%D'" | findstr /C:"1" >NUL
  IF ERRORLEVEL 1 (
    %PSQL% -h localhost -p 5432 -U %DB_USER% -d postgres -c "CREATE DATABASE %%D OWNER %DB_USER%;" >NUL
    IF ERRORLEVEL 1 (
      ECHO [ERROR] Could not create the database %%D.
      EXIT /B 1
    )
    ECHO [OK]   Created database %%D
  ) ELSE (
    ECHO [OK]   Database %%D already exists
  )
)

REM ---- Make sure the application user can log in with a password ------------
%PSQL% -h localhost -p 5432 -U %DB_USER% -d postgres -c "ALTER ROLE %DB_USER% WITH LOGIN PASSWORD '%DB_PASSWORD%';" >NUL
IF ERRORLEVEL 1 (
  ECHO [WARN] Could not set the password for the role %DB_USER%. Check your PostgreSQL setup.
) ELSE (
  ECHO [OK]   Role %DB_USER% can log in with the password from .env
)

ECHO.
ECHO [DONE] The databases are ready. Run scripts\start-all.bat to start Merqo.
EXIT /B 0
