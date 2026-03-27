@echo off
setlocal

set BASEDIR=%~dp0
set MAVEN_DIR=%BASEDIR%.mvn\apache-maven-3.9.9
set MAVEN_CMD=%MAVEN_DIR%\bin\mvn.cmd

if not exist "%MAVEN_CMD%" (
  if not exist "%BASEDIR%.mvn" mkdir "%BASEDIR%.mvn"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip' -OutFile '%BASEDIR%.mvn\apache-maven-3.9.9-bin.zip'"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%BASEDIR%.mvn\apache-maven-3.9.9-bin.zip' -DestinationPath '%BASEDIR%.mvn' -Force"
)

"%MAVEN_CMD%" %*
