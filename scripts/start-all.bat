@REM ===========================================================================
@REM Merqo - start the backend and the frontend for local development.
@REM
@REM   * Backend  : http://localhost:8080
@REM   * Frontend : http://localhost:5173
@REM
@REM The frontend proxies /api to the backend, so the browser only ever uses
@REM its own origin, exactly like the production setup on Cloudflare Pages.
@REM
@REM Press Ctrl+C in this window to stop both.
@REM ===========================================================================
@ECHO OFF
SETLOCAL
cd /d "%~dp0.."

IF NOT EXIST ".env" (
  ECHO [ERROR] .env not found. Copy .env.example to .env first and fill in the values.
  EXIT /B 1
)

IF NOT EXIST "backend\target\merqo-backend-0.0.1-SNAPSHOT.jar" (
  ECHO [INFO] Building the backend jar ...
  CALL backend\mvnw.cmd -f backend\pom.xml -B -DskipTests package
  IF ERRORLEVEL 1 (
    ECHO [ERROR] The backend build failed.
    EXIT /B 1
  )
)

IF NOT EXIST "frontend\node_modules" (
  ECHO [INFO] Installing the frontend dependencies ...
  PUSHD frontend
  CALL npm install
  POPD
)

ECHO.
ECHO [INFO] Starting the backend on port 8080 ...
START "Merqo backend" cmd /k "cd /d "%~dp0.." && call backend\mvnw.cmd -f backend\pom.xml -B spring-boot:run -Dspring-boot.run.profiles=dev"

ECHO [INFO] Starting the frontend on port 5173 ...
PUSHD frontend
CALL npm run dev
POPD

EXIT /B 0
