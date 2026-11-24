@REM Runs the backend build with the pinned JDK and writes the output to a log file.
@REM Usage: scripts\run-backend.bat [maven goals]
@ECHO OFF
SETLOCAL
SET "JAVA_HOME=G:\tools\jdk21\jdk-21.0.13.1+2"
SET "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0.."
CALL backend\mvnw.cmd -f backend\pom.xml -B %*
EXIT /B %ERRORLEVEL%
