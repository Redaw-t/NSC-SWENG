@echo off
rem =====================================================================
rem  Nickel Sales Center - Sprint 1 demo (Windows)
rem
rem    run-demo.bat              JavaFX login screen (LoginDemoApp) - reads
rem                              users from the local MySQL database
rem    run-demo.bat --console    console demo with built-in stub users:
rem                              scenarios, then an interactive login prompt
rem    run-demo.bat --scripted   console scenarios only; exit code 1 if any fail
rem
rem  Double-clicking opens the login screen. The first run downloads JavaFX
rem  (via setup.bat), so it needs internet once.
rem =====================================================================
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "MAIN=nickelsales.auth.demo.LoginDemoApp"
set "APP_ARGS="
set "PAUSE_AT_END=1"
if /i "%~1"=="--console" set "MAIN=nickelsales.auth.demo.AuthDemo"
if /i "%~1"=="--scripted" (
    set "MAIN=nickelsales.auth.demo.AuthDemo"
    set "APP_ARGS=--scripted"
    set "PAUSE_AT_END="
)

set "FX=lib\javafx"
set "CP=lib\jbcrypt-0.4.jar;lib\mysql-connector-j-9.4.0.jar"

rem --- 1. JDK + JavaFX (setup.bat skips whatever is already there) ------
call "%~dp0setup.bat" --from-run
if errorlevel 1 goto :fail

rem --- 2. Compile everything in src\ and test\ into a clean out\ -------
if exist out rmdir /s /q out
mkdir out

rem javac argfiles treat "\" as an escape character and the project path
rem may contain spaces, so write each path quoted, with forward slashes.
set "SOURCES=out\sources.txt"
type nul > "%SOURCES%"
for /r src %%F in (*.java) do (
    set "P=%%F"
    >>"%SOURCES%" echo "!P:\=/!"
)
for /r test %%F in (*.java) do (
    set "P=%%F"
    >>"%SOURCES%" echo "!P:\=/!"
)

echo Compiling...
"!JDK_BIN!\javac.exe" -encoding UTF-8 --module-path "%FX%" --add-modules javafx.controls -cp "%CP%" -d out @%SOURCES%
if errorlevel 1 (
    echo [ERROR] Compile failed - see the javac output above.
    goto :fail
)

rem Optional resources (e.g. resources\images\nickel-logo.png for the login screen)
if exist resources xcopy /e /i /q /y resources out >nul

rem --- 3. Run -------------------------------------------------------------
echo Running %MAIN% ...
echo.
"!JDK_BIN!\java.exe" --module-path "%FX%" --add-modules javafx.controls -cp "out;%CP%" %MAIN% %APP_ARGS%
set "RC=!errorlevel!"
if defined PAUSE_AT_END pause
endlocal & exit /b %RC%

:fail
if defined PAUSE_AT_END pause
endlocal & exit /b 1
