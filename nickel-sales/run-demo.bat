@echo off
rem =====================================================================
rem  Nickel Sales Center Application - run and scripted checks (Windows)
rem
rem    run-demo.bat              the app (DevApp): login, home screen, modules.
rem                              Users and sales from the local MySQL database,
rem                              TEST products until Product & Inventory is connected
rem    run-demo.bat --console    console demo with built-in stub users:
rem                              scenarios, then an interactive login prompt
rem    run-demo.bat --scripted   run ALL self-tests (before you push); exit code 1 if any fail
rem
rem  Double-clicking opens the login screen. The first run downloads JavaFX
rem  (via setup.bat), so it needs internet once.
rem =====================================================================
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "MAIN=app.DevApp"
set "APP_ARGS="
set "PAUSE_AT_END=1"
if /i "%~1"=="--console" set "MAIN=auth.AuthDemo"
set "CHECKS="
if /i "%~1"=="--scripted" (
    set "CHECKS=1"
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

if defined CHECKS goto :checks

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

rem --- --scripted: every self-test, with the same JDK that compiled them --
:checks
set "FAILED="
call :check auth.AuthDemo --scripted
call :check auth.AuthzDemo
call :check auth.UserAdminDemo
call :check pos.PosContractCheck
call :check pos.CartCheck
call :check pos.CheckoutCheck
call :check pos.ReceiptCheck
call :check app.AppCheck
echo.
echo =================================================================
if defined FAILED (
    echo  CHECKS FAILED:!FAILED!
) else (
    echo  ALL CHECKS PASSED
)
echo =================================================================
if defined FAILED (
    endlocal & exit /b 1
)
endlocal & exit /b 0

:check
echo.
echo ----- %~1
"!JDK_BIN!\java.exe" -cp "out;%CP%;%FX%\*" %*
if errorlevel 1 set "FAILED=!FAILED! %~1"
exit /b 0
