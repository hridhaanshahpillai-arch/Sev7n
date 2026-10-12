@echo off
rem Builds (if needed) and launches the Sev7n editor. Extra arguments are passed through,
rem e.g.  run.bat mygame.7n   or   run.bat --play mygame.7n
setlocal
cd /d "%~dp0"

if not exist build\sev7n.jar call build.bat || exit /b 1

set "JAVAW=javaw"
if defined JAVA_HOME set "JAVAW=%JAVA_HOME%\bin\javaw"
start "" "%JAVAW%" -jar build\sev7n.jar %*
