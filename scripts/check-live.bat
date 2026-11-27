@REM ===========================================================================
@REM Merqo - check the live deployment.
@REM
@REM Runs scripts\check-live.ps1, which checks the frontend, the backend, the
@REM proxy and the demo login of the deployed site. It never writes anything.
@REM
@REM Usage:
@REM   scripts\check-live.bat
@REM   scripts\check-live.bat https://my-app.pages.dev
@REM   scripts\check-live.bat https://my-app.pages.dev https://my-api.onrender.com
@REM
@REM The exit code is 0 when every check passed.
@REM ===========================================================================
@ECHO OFF
SETLOCAL
cd /d "%~dp0.."

IF "%~1"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-live.ps1"
) ELSE IF "%~2"=="" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-live.ps1" -FrontendUrl "%~1"
) ELSE (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0check-live.ps1" -FrontendUrl "%~1" -BackendUrl "%~2"
)

EXIT /B %ERRORLEVEL%
