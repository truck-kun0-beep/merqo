@REM ---------------------------------------------------------------------------
@REM Maven Wrapper for Windows.
@REM Uses distribution type "only-script": no wrapper jar is needed, the pinned
@REM Maven version is downloaded on first use.
@REM Run: mvnw.cmd clean verify
@REM ---------------------------------------------------------------------------
@ECHO OFF
SETLOCAL EnableDelayedExpansion

SET "BASE_DIR=%~dp0"
REM The trailing backslash would escape the closing quote of the -D argument on
REM the Windows command line, so it is removed here.
IF "%BASE_DIR:~-1%"=="\" SET "BASE_DIR=%BASE_DIR:~0,-1%"
SET "WRAPPER_PROPERTIES=%BASE_DIR%\.mvn\wrapper\maven-wrapper.properties"

IF NOT EXIST "%WRAPPER_PROPERTIES%" (
  ECHO Cannot find %WRAPPER_PROPERTIES%
  EXIT /B 1
)

FOR /F "usebackq tokens=1,* delims==" %%A IN ("%WRAPPER_PROPERTIES%") DO (
  IF "%%A"=="distributionUrl" SET "DISTRIBUTION_URL=%%B"
  IF "%%A"=="distributionSha256Sum" SET "DISTRIBUTION_SHA256=%%B"
)

IF NOT DEFINED DISTRIBUTION_URL (
  ECHO Cannot read distributionUrl from %WRAPPER_PROPERTIES%
  EXIT /B 1
)

REM The archive is named after the file, not after the whole URL.
FOR %%F IN ("%DISTRIBUTION_URL%") DO SET "ARCHIVE_NAME=%%~nxF"

SET "MAVEN_USER_HOME=%USERPROFILE%\.m2"
SET "DISTS_DIR=%MAVEN_USER_HOME%\wrapper\dists"
SET "MAVEN_HOME=%DISTS_DIR%\%ARCHIVE_NAME:.zip=%"
SET "ARCHIVE_PATH=%DISTS_DIR%\%ARCHIVE_NAME%"

IF NOT EXIST "%MAVEN_HOME%\bin\mvn.cmd" (
  ECHO Downloading Maven from %DISTRIBUTION_URL%
  IF NOT EXIST "%DISTS_DIR%" MKDIR "%DISTS_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri $env:DISTRIBUTION_URL -OutFile $env:ARCHIVE_PATH; if ($env:DISTRIBUTION_SHA256) { $h=(Get-FileHash -Algorithm SHA256 $env:ARCHIVE_PATH).Hash.ToLower(); if ($h -ne $env:DISTRIBUTION_SHA256) { throw 'Checksum mismatch' } }; $tmp=Join-Path $env:DISTS_DIR 'tmp'; if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }; Expand-Archive -Path $env:ARCHIVE_PATH -DestinationPath $tmp -Force; $inner=Get-ChildItem $tmp -Directory | Select-Object -First 1; Move-Item $inner.FullName $env:MAVEN_HOME; Remove-Item $tmp -Recurse -Force; Remove-Item $env:ARCHIVE_PATH -Force"
  IF ERRORLEVEL 1 (
    ECHO Failed to install Maven.
    EXIT /B 1
  )
)

CALL "%MAVEN_HOME%\bin\mvn.cmd" -Dmaven.multiModuleProjectDirectory="%BASE_DIR%" %*
EXIT /B %ERRORLEVEL%
