@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "APP_ROOT=%SCRIPT_DIR%.."
set "MVN_EXE=%APP_ROOT%\mvn_home\bin\mvn.cmd"

if exist "%APP_ROOT%\jdk21\bin\java.exe" (
  set "JAVA_HOME=%APP_ROOT%\jdk21"
  set "PATH=%JAVA_HOME%\bin;%PATH%"
)

if not exist "%MVN_EXE%" (
  echo [ERROR] Bundled Maven was not found at:
  echo %MVN_EXE%
  echo Run INSTALL.bat from the TradeRakshak-Java folder first.
  exit /b 1
)

"%MVN_EXE%" %*
exit /b %ERRORLEVEL%
