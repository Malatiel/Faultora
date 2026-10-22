@echo off
setlocal DisableDelayedExpansion
set "JAVA=java.exe"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" -jar "%~dp0..\lib\faultora-${project.version}.jar" %*
exit /b %ERRORLEVEL%
