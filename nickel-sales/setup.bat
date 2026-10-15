@echo off
rem =====================================================================
rem  Nickel Sales Center - developer setup (Windows)
rem
rem  Double-click this file (or run "setup.bat" in a terminal). It checks
rem  every dependency and tells you what to install and where to get it:
rem
rem    Java JDK 17+     required   - offers a one-click install via winget
rem    JavaFX 21.0.5    required   - downloaded automatically into lib\javafx
rem    jBCrypt, MySQL   bundled    - already in lib\
rem      Connector/J
rem    MySQL Server 8   login screen only - you install it; once it is
rem                                running this script creates the
rem                                database and saves your MySQL password
rem
rem  Safe to run as often as you like: anything already done is skipped.
rem  run-demo.bat calls this automatically (quietly), so most of the time
rem  you only run it by hand the first time, or when something is missing.
rem =====================================================================
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "MODE=%~1"
set "QUIET="
if /i "%MODE%"=="--from-run" set "QUIET=1"

set "FX_VERSION=21.0.5"
set "FX_DIR=lib\javafx"
set "FX_URL=https://repo1.maven.org/maven2/org/openjfx"
set "FX_MODULES=base graphics controls"
set "SQL_FILE=%~dp0..\database\NickelSales-Database-Config.sql"
set "TODO="

if not defined QUIET (
    echo ==================================================================
    echo  Nickel Sales Center - checking what this computer needs
    echo ==================================================================
    echo.
)

rem ---------------------------------------------------------------------
rem  1. Java JDK 17+ (required)
rem ---------------------------------------------------------------------
call :find_jdk
if not defined JDK_BIN call :install_jdk
if not defined JDK_BIN goto :fail
if not defined QUIET echo [setup] Java JDK 17+      : OK      javac !JDK_VER!  ^(!JDK_BIN!^)

rem ---------------------------------------------------------------------
rem  2. JavaFX (required, downloaded automatically)
rem ---------------------------------------------------------------------
call :get_javafx
if errorlevel 1 goto :fail
if not defined QUIET echo [setup] JavaFX %FX_VERSION%     : OK      nickel-sales\%FX_DIR%
if not defined QUIET echo [setup] jBCrypt, Connector/J: OK      bundled in nickel-sales\lib

rem ---------------------------------------------------------------------
rem  3. MySQL Server (needed for the login screen only)
rem ---------------------------------------------------------------------
call :find_mysql
if defined QUIET goto :mysql_quiet

if not defined MYSQL_RUNNING (
    if defined MYSQL_SVC (call :msg_mysql_stopped) else if defined MYSQL_EXE (call :msg_mysql_stopped) else (call :msg_mysql_missing)
    set "TODO=1"
    goto :summary
)
echo [setup] MySQL Server        : OK      running
if not defined MYSQL_EXE (
    call :msg_mysql_no_client
    set "TODO=1"
    goto :summary
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup-database.ps1" -MySql "!MYSQL_EXE!" -SqlFile "%SQL_FILE%"
if errorlevel 1 set "TODO=1"
goto :summary

:mysql_quiet
rem Called from run-demo.bat: never prompt, just warn if the login screen
rem will not be able to reach the database.
if not defined MYSQL_RUNNING (
    echo [setup] Note: MySQL is not running, so the login screen cannot log anyone in.
    echo [setup]       Double-click setup.bat for help, or use run-demo.bat --console.
) else if not defined NSC_DB_PASSWORD (
    echo [setup] Note: NSC_DB_PASSWORD is not set. Double-click setup.bat once to
    echo [setup]       create the database and save your MySQL password.
)
endlocal & set "JDK_BIN=%JDK_BIN%" & exit /b 0

:summary
echo.
if defined TODO (
    echo [setup] Java side is ready. To use the login screen, finish the MySQL step
    echo [setup] above, then run setup.bat again. Meanwhile, run-demo.bat --console
    echo [setup] works without a database.
) else (
    echo [setup] Everything is ready. Double-click run-demo.bat to open the login screen.
    echo [setup] Demo login: NSC-00001 / Password123
)
echo.
pause
endlocal & set "JDK_BIN=%JDK_BIN%" & exit /b 0

:fail
if not defined QUIET pause
endlocal & exit /b 1


rem =====================================================================
rem  Subroutines
rem =====================================================================

rem ---------------------------------------------------------------------
rem  No JDK 17+: say where to get one, offer winget as a shortcut.
rem ---------------------------------------------------------------------
:install_jdk
echo.
echo [setup] Java JDK 17+      : MISSING  (required)
if defined OLD_JDK echo [setup]   Found only !OLD_JDK!, which is too old.
echo [setup]   Download: https://adoptium.net/temurin/releases/?version=21
echo [setup]   Choose Windows / x64 / JDK / .msi, and in the installer turn on
echo [setup]   "Set JAVA_HOME variable". Then run this script again.
where winget >nul 2>nul
if errorlevel 1 exit /b 0
echo.
choice /c YN /m "[setup] Or install Temurin JDK 21 for you now using winget"
if errorlevel 2 exit /b 0
winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-package-agreements --accept-source-agreements
if errorlevel 1 (
    echo [setup] winget could not install the JDK. Use the download link above instead.
    exit /b 0
)
call :find_jdk
if not defined JDK_BIN (
    echo [setup] The JDK was installed but this window cannot see it yet.
    echo [setup] Close this window and run the script again.
)
exit /b 0

rem ---------------------------------------------------------------------
rem  Download the JavaFX jars for Windows from Maven Central.
rem ---------------------------------------------------------------------
:get_javafx
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
            echo [setup] JavaFX            : DOWNLOAD FAILED  ^(required^)
            echo [setup]   Check your internet connection and run this again, or
            echo [setup]   download !SRC!
            echo [setup]   yourself and save it in nickel-sales\%FX_DIR%\
            exit /b 1
        )
    )
)
exit /b 0

rem ---------------------------------------------------------------------
rem  MySQL messages (kept out of if-blocks so parentheses print safely).
rem ---------------------------------------------------------------------
:msg_mysql_missing
echo [setup] MySQL Server 8      : NOT INSTALLED  (needed for the login screen)
echo [setup]   Download: https://dev.mysql.com/downloads/installer/
echo [setup]   Get "MySQL Installer for Windows" (the larger, full download).
echo [setup]   In the installer choose "Server only", keep port 3306, and
echo [setup]   write down the root password you set - this script asks for it.
echo [setup]   Optional: also tick "MySQL Workbench" to browse the database.
exit /b 0

:msg_mysql_stopped
echo [setup] MySQL Server        : INSTALLED BUT NOT RUNNING  (needed for the login screen)
if defined MYSQL_SVC (
    echo [setup]   Start it: press Win+R, type services.msc, right-click
    echo [setup]   "!MYSQL_SVC!" and choose Start.
) else (
    echo [setup]   Start the MySQL server. XAMPP users: click Start next to
    echo [setup]   MySQL in the XAMPP Control Panel.
)
exit /b 0

:msg_mysql_no_client
echo [setup] Database            : cannot check automatically - mysql.exe was not found.
echo [setup]   Open database\NickelSales-Database-Config.sql in MySQL Workbench and
echo [setup]   run it, then save your MySQL password for the app with:
echo [setup]     setx NSC_DB_PASSWORD "your-mysql-password"
exit /b 0

rem ---------------------------------------------------------------------
rem  Look for MySQL: a Windows service (MySQL80, MySQL84, MariaDB, ...),
rem  a running mysqld.exe (e.g. XAMPP), and the mysql.exe client.
rem  Sets MYSQL_SVC, MYSQL_RUNNING and MYSQL_EXE.
rem ---------------------------------------------------------------------
:find_mysql
set "MYSQL_SVC="
set "MYSQL_RUNNING="
set "MYSQL_EXE="
for /f "tokens=2 delims=:" %%S in ('sc query state^= all 2^>nul ^| findstr /i /b /c:"SERVICE_NAME: MySQL" /c:"SERVICE_NAME: MariaDB"') do (
    if not defined MYSQL_SVC set "MYSQL_SVC=%%S"
)
if not defined MYSQL_SVC goto :find_mysql_process
rem sc prints " MySQL80" after the colon - drop the leading space.
set "MYSQL_SVC=!MYSQL_SVC:~1!"
rem (No "if" on this line: "if ... | findstr" would run in a child cmd
rem  where !variables! are not expanded.)
sc query "%MYSQL_SVC%" | findstr /i "RUNNING" >nul && set "MYSQL_RUNNING=1"
:find_mysql_process
tasklist /fi "imagename eq mysqld.exe" 2>nul | findstr /i "mysqld.exe" >nul && set "MYSQL_RUNNING=1"
tasklist /fi "imagename eq mariadbd.exe" 2>nul | findstr /i "mariadbd.exe" >nul && set "MYSQL_RUNNING=1"
for /f "delims=" %%P in ('where mysql 2^>nul') do if not defined MYSQL_EXE set "MYSQL_EXE=%%P"
if not defined MYSQL_EXE for /d %%D in ("%ProgramFiles%\MySQL\MySQL Server*" "%ProgramFiles%\MariaDB*") do (
    if exist "%%~D\bin\mysql.exe" set "MYSQL_EXE=%%~D\bin\mysql.exe"
)
if not defined MYSQL_EXE if exist "C:\xampp\mysql\bin\mysql.exe" set "MYSQL_EXE=C:\xampp\mysql\bin\mysql.exe"
exit /b 0

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
