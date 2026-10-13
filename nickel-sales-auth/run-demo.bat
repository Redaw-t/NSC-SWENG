@echo off
rem Run the Sprint 1 authentication demo (the AUTH module + throwaway stubs).
rem Double-click this file, or run from a terminal:  run-demo.bat --scripted
setlocal enabledelayedexpansion
cd /d "%~dp0"

rem --- find a JDK: PATH, then JAVA_HOME, then C:\Program Files\Java\jdk* ---
set "JAVAC="
where javac >nul 2>nul && set "JAVAC=javac"
if not defined JAVAC if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JAVAC=%JAVA_HOME%\bin\javac.exe"
if not defined JAVAC for /d %%D in ("C:\Program Files\Java\jdk*") do if not defined JAVAC set "JAVAC=%%D\bin\javac.exe"
if not defined JAVAC (
    echo [ERROR] No JDK found. Install JDK 17+ or set JAVA_HOME.
    pause
    exit /b 1
)

set "JAVA=java"
if not "!JAVAC!"=="javac" (
    for %%F in ("!JAVAC!") do set "JAVA=%%~dpFjava.exe"
)

if not exist out mkdir out

rem Build the source list with QUOTED FORWARD-SLASH paths: the folder name
rem contains a space ("New folder") so paths must be quoted, and javac
rem argfiles treat backslashes as escape characters, so use "/".
set "SOURCES=out\_sources.txt"
type nul > "!SOURCES!"
for /r src %%F in (*.java) do (
    set "P=%%F"
    echo "!P:\=/!">>"!SOURCES!"
)
for /r test %%F in (*.java) do (
    set "P=%%F"
    echo "!P:\=/!">>"!SOURCES!"
)

"!JAVAC!" -encoding UTF-8 -cp lib\jbcrypt-0.4.jar -d out "@!SOURCES!"
if errorlevel 1 (
    echo [ERROR] Compile failed - see the javac output above.
    pause
    exit /b 1
)

"!JAVA!" -cp "out;lib\jbcrypt-0.4.jar" nickelsales.auth.demo.AuthDemo %*
pause
