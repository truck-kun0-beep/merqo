@REM ===========================================================================
@REM Merqo - stop the local backend and frontend.
@REM
@REM Closes the windows that scripts\start-all.bat opened and stops any
@REM leftover java or node process that listens on the Merqo ports.
@REM ===========================================================================
@ECHO OFF
SETLOCAL
cd /d "%~dp0.."

ECHO [INFO] Stopping the Merqo backend and frontend windows ...

FOR /F "tokens=2 delims=," %%P IN ('tasklist /FI "IMAGENAME eq java.exe" /FO CSV /NH 2^>NUL') DO (
  ECHO   java.exe %%~P
)

REM Stop the processes that hold the Merqo ports.
FOR %%P IN (8080 5173 4173) DO (
  FOR /F "tokens=5" %%I IN ('netstat -ano ^| findstr /R /C:":%%P .*LISTENING"') DO (
    ECHO [INFO] Stopping the process %%I that holds port %%P ...
    TASKKILL /F /PID %%I >NUL 2>&1
  )
)

ECHO [INFO] Any window titled "Merqo backend" can also be closed with Ctrl+C.
ECHO [DONE] Merqo has been stopped.
EXIT /B 0
