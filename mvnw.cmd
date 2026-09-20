@echo off
setlocal
set BASEDIR=%~dp0
"%BASEDIR%backend\mvnw.cmd" -f "%BASEDIR%pom.xml" %*
