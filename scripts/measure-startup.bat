@REM ===========================================================================
@REM Merqo - measure the backend startup time and the memory it uses.
@REM
@REM Starts the packaged jar, waits until /api/v1/health/ping answers, prints
@REM how long that took and the memory footprint of the running process.
@REM
@REM The jar is tuned for the 512 MB free Render instance.
@REM ===========================================================================
@ECHO OFF
SETLOCAL ENABLEDELAYEDEXPANSION
cd /d "%~dp0.."

IF NOT EXIST "backend\target\merqo-backend-0.0.1-SNAPSHOT.jar" (
  ECHO [INFO] Building the backend jar ...
  CALL backend\mvnw.cmd -f backend\pom.xml -B -DskipTests package
  IF ERRORLEVEL 1 EXIT /B 1
)

SET "JAR=backend\target\merqo-backend-0.0.1-SNAPSHOT.jar"
SET "LOGFILE=logs\startup-measurement.log"
IF NOT EXIST "logs" MKDIR "logs"

ECHO [INFO] Starting the backend with the production memory settings ...
ECHO [INFO] Waiting for the health endpoint ...

SET "START=%TIME%"
START "Merqo measurement" /B cmd /c "java -XX:MaxRAMPercentage=70 -XX:+UseSerialGC -Xss512k -XX:+ExitOnOutOfMemoryError -jar %JAR% --spring.profiles.active=prod > %LOGFILE% 2>&1"

SET "ELAPSED=not ready"
FOR /L %%I IN (1,1,90) DO (
  IF "%%I"=="91" GOTO :timeout
  curl.exe -s -o NUL http://localhost:8080/api/v1/health/ping 2>NUL
  IF NOT ERRORLEVEL 1 (
    SET "ELAPSED=%%I"
    GOTO :ready
  )
  ping -n 2 127.0.0.1 >NUL
)

:timeout
ECHO [WARN] The backend did not answer within 90 seconds. See %LOGFILE%.
GOTO :report

:ready
ECHO.
ECHO [OK] The backend answered.
ECHO.
ECHO ---- Startup time -----------------------------------------
findstr /C:"Started MerqoApplication" %LOGFILE%
findstr /C:"Tomcat started" %LOGFILE%
findstr /C:"Successfully applied" %LOGFILE%
ECHO.
ECHO ---- Memory ------------------------------------------------
FOR /F "tokens=2" %%P IN ('wmic process where "name='java.exe'" get ProcessId /value ^| find "="') DO (
  IF NOT "%%P"=="" (
    FOR /F "WorkingSetSize=,PrivatePageCount=" %%A IN ('wmic process where ProcessId=%%P get WorkingSetSize,PrivatePageCount /value ^| find "="') DO (
      ECHO   java.exe %%P
    )
  )
)

:report
ECHO.
ECHO The full startup log is in %LOGFILE%.
EXIT /B 0
