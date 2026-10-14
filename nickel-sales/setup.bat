@echo off
rem =====================================================================
rem  Nickel Sales Center - one-time developer setup (Windows)
rem
rem  Double-click this file (or run "setup.bat" in a terminal). It:
rem    1. finds a JDK 17 or newer (and offers to install Temurin JDK 21
rem       with winget if you have none),
rem    2. downloads the JavaFX jars into lib\javafx (not committed to git).
rem
rem  Safe to run as often as you like: anything already present is skipped.
rem  run-demo.bat calls this automatically, so most people never need to
rem  run it by hand.
rem =====================================================================
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "MODE=%~1"
set "FX_VERSION=21.0.5"
set "FX_DIR=lib\javafx"
set "FX_URL=https://repo1.maven.org/maven2/org/openjfx"
set "FX_MODULES=base graphics controls"

rem ---------------------------------------------------------------------
rem  1. JDK
rem ---------------------------------------------------------------------
call :find_jdk
if not defined JDK_BIN (
    echo.
    if defined OLD_JDK echo [setup] Found only an old JDK: !OLD_JDK! - this project needs JDK 17 or newer.
    if not defined OLD_JDK echo [setup] No JDK found on this computer.
    where winget >nul 2>nul
    if errorlevel 1 (
        echo [setup] Install a JDK 17+ manually, e.g. https://adoptium.net/ , then run this again.
        goto :fail
    )
    choice /c YN /m "[setup] Install Eclipse Temurin JDK 21 now using winget"
    if errorlevel 2 goto :fail
    winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-package-agreements --accept-source-agreements
    call :find_jdk
    if not defined JDK_BIN (
        echo [setup] The JDK was installed but this window cannot see it yet.
        echo [setup] Close this window and run the script again.
        goto :fail
    )
)
if /i not "%MODE%"=="--from-run" echo [setup] JDK    : !JDK_BIN!  ^(javac !JDK_VER!^)

rem ---------------------------------------------------------------------
rem  2. JavaFX jars (Windows builds from Maven Central)
rem ---------------------------------------------------------------------
if not exist "%FX_DIR%" mkdir "%FX_DIR%"

rem Remove jars from a different JavaFX version - two versions on the
rem module path at once make Java refuse to start.
for %%F in ("%FX_DIR%\javafx-*.jar") do (
    echo %%~nxF | findstr /c:"-%FX_VERSION%-" >nul || del /q "%%~F"
)

for %%M in (%FX_MODULES%) do (
    set "JAR=javafx-%%M-%FX_VERSION%-win.jar"
    if not exist "%FX_DIR%\!JAR!" (
        echo [setup] Downloading !JAR! ...
        set "SRC=%FX_URL%/javafx-%%M/%FX_VERSION%/!JAR!"
        where curl >nul 2>nul
        if not errorlevel 1 (
            curl -fsSL -o "%FX_DIR%\!JAR!" "!SRC!"
        ) else (
            powershell -NoProfile -Command "Invoke-WebRequest -UseBasicParsing -Uri '!SRC!' -OutFile '%FX_DIR%\!JAR!'"
        )
        if errorlevel 1 (
            if exist "%FX_DIR%\!JAR!" del /q "%FX_DIR%\!JAR!"
            echo [setup] Download failed. Check your internet connection and try again.
            echo [setup] Or download it yourself from !SRC!
            echo [setup] and save it in nickel-sales\%FX_DIR%\
            goto :fail
        )
    )
)
if /i not "%MODE%"=="--from-run" echo [setup] JavaFX : %FX_VERSION% in nickel-sales\%FX_DIR%

if /i not "%MODE%"=="--from-run" (
    echo.
    echo [setup] All set. Double-click run-demo.bat to try the demo.
    pause
)
endlocal & set "JDK_BIN=%JDK_BIN%" & exit /b 0

:fail
if /i not "%MODE%"=="--from-run" pause
endlocal & exit /b 1


rem ---------------------------------------------------------------------
rem  Look for a JDK 17+ in: JAVA_HOME, PATH, the usual install folders,
rem  and IntelliJ's downloaded JDKs (%USERPROFILE%\.jdks).
rem  Sets JDK_BIN to the JDK's bin folder (holds javac.exe and java.exe).
rem ---------------------------------------------------------------------
:find_jdk
set "JDK_BIN="
set "OLD_JDK="
if defined JAVA_HOME call :try_jdk "%JAVA_HOME%\bin"
for /f "delims=" %%P in ('where javac 2^>nul') do call :try_jdk "%%~dpP"
for %%R in ("%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Java" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Zulu" "%ProgramFiles%\Amazon Corretto" "%ProgramFiles%\BellSoft" "%USERPROFILE%\.jdks") do (
    if exist "%%~R\" for /d %%D in ("%%~R\*") do call :try_jdk "%%~D\bin"
)
exit /b 0

:try_jdk
if defined JDK_BIN exit /b 0
set "CAND=%~1"
if "!CAND:~-1!"=="\" set "CAND=!CAND:~0,-1!"
if not exist "!CAND!\javac.exe" exit /b 0
set "JV="
for /f "tokens=1,2" %%A in ('call "!CAND!\javac.exe" -version 2^>^&1') do if /i "%%A"=="javac" set "JV=%%B"
if not defined JV exit /b 0
set "JMAJOR=0"
for /f "delims=.-+" %%N in ("!JV!") do set "JMAJOR=%%N"
if !JMAJOR! GEQ 17 (
    set "JDK_BIN=!CAND!"
    set "JDK_VER=!JV!"
) else (
    set "OLD_JDK=!CAND! (javac !JV!)"
)
exit /b 0
