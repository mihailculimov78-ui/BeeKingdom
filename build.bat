@echo off
setlocal
set MAVEN_VERSION=3.9.11
set MAVEN_DIR=%~dp0.tools\apache-maven-%MAVEN_VERSION%
set MAVEN_ZIP=%~dp0.tools\maven.zip
if not exist "%MAVEN_DIR%\bin\mvn.cmd" (
  if not exist "%~dp0.tools" mkdir "%~dp0.tools"
  echo Downloading Apache Maven %MAVEN_VERSION%...
  curl -L "https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip" -o "%MAVEN_ZIP%"
  if errorlevel 1 goto fail
  powershell -NoProfile -Command "Expand-Archive -Force '%MAVEN_ZIP%' '%~dp0.tools'"
  if errorlevel 1 goto fail
)
java -version 2>&1 | findstr /C:"25." >nul
if errorlevel 1 (
  echo ERROR: Install JDK 25 and make sure java -version shows 25.
  pause
  exit /b 1
)
call "%MAVEN_DIR%\bin\mvn.cmd" -U clean package
if errorlevel 1 goto fail
echo.
echo BUILD SUCCESS. JAR: target\BeeKingdom-0.1.0.jar
pause
exit /b 0
:fail
echo BUILD FAILED. Copy the complete error log and send it here.
pause
exit /b 1
