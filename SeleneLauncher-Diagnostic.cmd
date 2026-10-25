@echo off
setlocal
cd /d "%~dp0"
set "LOG=SeleneLauncher-startup-check.log"

if not exist "runtime\bin\java.exe" (
  echo ERROR: runtime\bin\java.exe is missing. > "%LOG%"
  echo Please extract the complete ZIP before starting Selene Launcher. >> "%LOG%"
  type "%LOG%"
  pause
  exit /b 2
)

if not exist "SeleneLauncher.jar" (
  echo ERROR: SeleneLauncher.jar is missing. > "%LOG%"
  echo Please extract the complete ZIP before starting Selene Launcher. >> "%LOG%"
  type "%LOG%"
  pause
  exit /b 3
)

"runtime\bin\java.exe" -Dfile.encoding=UTF-8 -Dprism.lcdtext=false -cp "SeleneLauncher.jar;lib\*" io.selene.launcher.LauncherMain --startup-check > "%LOG%" 2>&1
set "RESULT=%ERRORLEVEL%"
type "%LOG%"
if not "%RESULT%"=="0" (
  echo.
  echo Startup check failed. Send this log when reporting the problem:
  echo %CD%\%LOG%
) else (
  echo.
  echo Startup files and dependencies passed the logical check.
)
pause
exit /b %RESULT%
