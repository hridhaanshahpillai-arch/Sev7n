@echo off
rem Builds Sev7n into build\sev7n.jar (the real work is in build.ps1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1"
