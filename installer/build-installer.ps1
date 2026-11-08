<#
=====================================================================
 Nickel Sales Center - build the Windows installer

   powershell -ExecutionPolicy Bypass -File installer\build-installer.ps1

 Produces:
   installer\dist\NickelSalesSetup-<version>.exe    the installer for stores (kept between builds)
   installer\build\app\NickelSales\                  the app on its own, with its
                                                     own Java runtime and JavaFX:
     NickelSales.exe           the app (no console window; logs go to the log file)
     NickelSales-Console.exe   the same app with a console, for support
   installer\build\mysql\                            the MySQL files the installer adds

 Needs:
   a JDK 21+ with jpackage and jmods (Temurin recommended: its licence allows
   giving the runtime to stores). Pass -JdkHome to choose one.
   Inno Setup 6 (winget install JRSoftware.InnoSetup), unless -AppOnly.
 Downloads once into installer\.cache, each checked before use:
   JavaFX jmods from Gluon (SHA-256), the same version as nickel-sales\lib\javafx
   MySQL Server ZIP edition from Oracle's CDN (SHA-256)
   Microsoft Visual C++ runtime, which MySQL needs (Microsoft's signature)

 Parameters:
   -JdkHome <folder>   the JDK to build with (default: the newest one found)
   -AppOnly            build the app folder only, not MySQL or the installer
=====================================================================
#>
param(
    [string]$JdkHome,
    [switch]$AppOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2

$Here      = $PSScriptRoot
$AppDir    = Join-Path (Split-Path $Here -Parent) 'nickel-sales'
$Cache     = Join-Path $Here '.cache'
$Build     = Join-Path $Here 'build'
$Dist      = Join-Path $Here 'dist'      # finished installers; kept between builds

$FxVersion = '21.0.5'       # keep in step with nickel-sales\lib\javafx and setup.bat
$FxSha256  = '693c04e2bf16ad26dc803d384500aab7593921e6e3dc8aaac3b10b0613feaa1f'
$FxUrl     = "https://download2.gluonhq.com/openjfx/$FxVersion/openjfx-$($FxVersion)_windows-x64_bin-jmods.zip"
$Libs      = @('jbcrypt-0.4.jar', 'mysql-connector-j-9.4.0.jar')
$MySqlVersion = '8.4.13'    # an LTS release; the schema must work on it
$MySqlSha256  = 'e9cd98b1d23f122987b1afca26ad942efec5891481103d48ae3cde237fa3ea54'
$MySqlUrl     = "https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-$MySqlVersion-winx64.zip"
$VcRedistUrl  = 'https://aka.ms/vs/17/release/vc_redist.x64.exe'
$MainClass = 'app.Main'
$Name      = 'NickelSales'

function Step($text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }

# A download kept in installer\.cache; checked against $sha256, or Microsoft's signature when $sha256 is ''
function Get-Cached($url, $file, $sha256) {
    $path = Join-Path $Cache $file
    if (-not (Test-Path $path)) {
        New-Item -ItemType Directory -Force $Cache | Out-Null
        Write-Host "Downloading $url"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'   # the progress bar makes big downloads very slow
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile "$path.part"
        Move-Item "$path.part" $path
    }
    if ($sha256) {
        $actual = (Get-FileHash $path -Algorithm SHA256).Hash
        if ($actual -ne $sha256) { Remove-Item $path; throw "$file has the wrong SHA-256 ($actual); deleted it, run again." }
    } else {
        $signature = Get-AuthenticodeSignature $path
        if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notlike 'CN=Microsoft Corporation,*') {
            Remove-Item $path; throw "$file is not signed by Microsoft; deleted it, run again."
        }
    }
    return $path
}

function Invoke-Tool($exe, [string[]]$arguments) {
    & $exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path $exe -Leaf) failed (exit code $LASTEXITCODE)" }
}

# --- 1. JDK with jpackage, jlink and jmods ----------------------------
function Find-Jdk {
    if ($JdkHome) { return $JdkHome }
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($parent in "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft") {
        if (Test-Path $parent) { $candidates += (Get-ChildItem $parent -Directory -Filter 'jdk*' | ForEach-Object FullName) }
    }
    $usable = $candidates | Where-Object {
        (Test-Path (Join-Path $_ 'bin\jpackage.exe')) -and (Test-Path (Join-Path $_ 'jmods\java.base.jmod'))
    }
    $best = $usable | Sort-Object { [int](Get-JdkMajor $_) } -Descending | Select-Object -First 1
    if (-not $best) {
        throw 'No JDK 21+ with jpackage and jmods found. Install Temurin JDK 21 (winget install EclipseAdoptium.Temurin.21.JDK) or pass -JdkHome.'
    }
    return $best
}

function Get-JdkMajor($jdk) {
    $release = Join-Path $jdk 'release'
    if (-not (Test-Path $release)) { return 0 }
    $line = Select-String -Path $release -Pattern '^JAVA_VERSION="(\d+)' | Select-Object -First 1
    if ($line) { return [int]$line.Matches[0].Groups[1].Value } else { return 0 }
}

Step 'JDK'
$Jdk = Find-Jdk
$JdkMajor = Get-JdkMajor $Jdk
if ($JdkMajor -lt 21) { throw "$Jdk is Java $JdkMajor; the app needs Java 21 or newer." }
$Bin = Join-Path $Jdk 'bin'
Write-Host "Using $Jdk (Java $JdkMajor)"

# --- 2. Version, from the one place it is defined ---------------------
$VersionFile = Join-Path $AppDir 'src\shared\AppVersion.java'
$match = Select-String -Path $VersionFile -Pattern 'VERSION\s*=\s*"(\d+\.\d+\.\d+)"' | Select-Object -First 1
if (-not $match) { throw "No VERSION = ""x.y.z"" in $VersionFile" }
$Version = $match.Matches[0].Groups[1].Value
Write-Host "Version $Version"

# --- 3. JavaFX jmods (cached) -----------------------------------------
Step "JavaFX $FxVersion jmods"
$FxMods = Join-Path $Cache "javafx-jmods-$FxVersion"
if (-not (Test-Path $FxMods)) {
    $FxZip = Get-Cached $FxUrl "openjfx-$($FxVersion)_windows-x64_bin-jmods.zip" $FxSha256
    $unzip = Join-Path $Cache 'javafx-unzip'
    if (Test-Path $unzip) { Remove-Item -Recurse -Force $unzip }
    Expand-Archive $FxZip $unzip
    $jmodDir = Get-ChildItem $unzip -Recurse -Filter 'javafx.base.jmod' | Select-Object -First 1
    if (-not $jmodDir) { throw "No javafx.base.jmod in $FxZip" }
    Move-Item $jmodDir.DirectoryName $FxMods
    Remove-Item -Recurse -Force $unzip
}
Write-Host "Using $FxMods"

# --- 4. Compile src\ (not test\) and make nickelsales.jar --------------
Step 'Compile'
if (Test-Path $Build) { Remove-Item -Recurse -Force $Build }
$Classes = Join-Path $Build 'classes'
$Staging   = Join-Path $Build 'input'
New-Item -ItemType Directory -Force $Classes, $Staging | Out-Null

# javac argfiles treat "\" as an escape: write each path quoted, with forward slashes
$Sources = Join-Path $Build 'sources.txt'
Get-ChildItem (Join-Path $AppDir 'src') -Recurse -Filter *.java |
    ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } |
    Set-Content -Encoding ascii $Sources
$LibPaths = $Libs | ForEach-Object { Join-Path $AppDir "lib\$_" }
Invoke-Tool (Join-Path $Bin 'javac.exe') @('-encoding', 'UTF-8', '--release', '21',
    '--module-path', $FxMods, '--add-modules', 'javafx.controls',
    '-cp', ($LibPaths -join ';'), '-d', $Classes, "@$Sources")
Copy-Item -Recurse (Join-Path $AppDir 'resources\*') $Classes

$MainJar = Join-Path $Staging 'nickelsales.jar'
Invoke-Tool (Join-Path $Bin 'jar.exe') @('--create', '--file', $MainJar, '--main-class', $MainClass, '-C', $Classes, '.')
$LibPaths | ForEach-Object { Copy-Item $_ $Staging }

# --- 5. A Java runtime with only the modules the app uses -------------
Step 'Java runtime (jlink)'
$deps = & (Join-Path $Bin 'jdeps.exe') --module-path $FxMods --print-module-deps --ignore-missing-deps `
    --multi-release 21 --class-path (($LibPaths) -join ';') $MainJar @LibPaths
if ($LASTEXITCODE -ne 0) { throw 'jdeps failed' }
$modules = @(($deps | Select-Object -Last 1).Trim().Split(','))
# Loaded by name at run time, so jdeps can't see them: printer and receipt charsets,
# date/number formats, TLS ciphers for MySQL connections over SSL.
$modules += 'javafx.controls', 'jdk.charsets', 'jdk.localedata', 'jdk.crypto.ec', 'jdk.crypto.mscapi', 'jdk.zipfs'
$available = & (Join-Path $Bin 'java.exe') --list-modules | ForEach-Object { ($_ -split '@')[0] }
$modules = $modules | Where-Object { $_ -like 'javafx.*' -or $available -contains $_ } | Sort-Object -Unique
Write-Host "Modules: $($modules -join ', ')"

$Runtime = Join-Path $Build 'runtime'
Invoke-Tool (Join-Path $Bin 'jlink.exe') @('--module-path', ((Join-Path $Jdk 'jmods') + ';' + $FxMods),
    '--add-modules', ($modules -join ','), '--include-locales', 'en,fil',
    '--strip-debug', '--no-header-files', '--no-man-pages', '--compress=zip-6', '--output', $Runtime)

# --- 6. Icon: the logo as a Windows .ico (PNG images inside) ----------
Step 'Icon'
Add-Type -AssemblyName System.Drawing
$Icon = Join-Path $Build "$Name.ico"
$logo = [System.Drawing.Image]::FromFile((Join-Path $AppDir 'resources\images\nickel-logo-icon.png'))
try {
    $images = foreach ($size in 256, 48, 32, 16) {
        $bitmap = New-Object System.Drawing.Bitmap $size, $size
        $g = [System.Drawing.Graphics]::FromImage($bitmap)
        $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $g.DrawImage($logo, 0, 0, $size, $size)
        $g.Dispose()
        $stream = New-Object System.IO.MemoryStream
        $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        $bitmap.Dispose()
        , @($size, $stream.ToArray())
    }
} finally { $logo.Dispose() }
$out = New-Object System.IO.BinaryWriter ([System.IO.File]::Create($Icon))
try {
    $out.Write([uint16]0); $out.Write([uint16]1); $out.Write([uint16]$images.Count)   # ICONDIR
    $offset = 6 + 16 * $images.Count
    foreach ($image in $images) {                                                     # ICONDIRENTRY each
        $side = if ($image[0] -ge 256) { 0 } else { $image[0] }
        $out.Write([byte]$side); $out.Write([byte]$side); $out.Write([byte]0); $out.Write([byte]0)
        $out.Write([uint16]1); $out.Write([uint16]32)
        $out.Write([uint32]$image[1].Length); $out.Write([uint32]$offset)
        $offset += $image[1].Length
    }
    foreach ($image in $images) { $out.Write([byte[]]$image[1]) }
} finally { $out.Close() }

# --- 7. The app folder (jpackage app-image) ----------------------------
Step 'App (jpackage)'
$ConsoleProps = Join-Path $Build 'console-launcher.properties'
Set-Content -Encoding ascii $ConsoleProps "win-console=true`r`ndescription=Nickel Sales Center (with console, for support)"
$AppOut = Join-Path $Build 'app'
# JavaFX loads its own DLLs and (on newer Java) uses sun.misc.Unsafe: allow both, or Java warns at start
$javaOptions = @('--java-options', '--enable-native-access=javafx.graphics')
if ($JdkMajor -ge 23) { $javaOptions += '--java-options', '--sun-misc-unsafe-memory-access=allow' }
Invoke-Tool (Join-Path $Bin 'jpackage.exe') (@('--type', 'app-image', '--name', $Name,
    '--app-version', $Version, '--vendor', 'Nickel Sales Center',
    '--description', 'Nickel Sales Center point of sale', '--icon', $Icon,
    '--input', $Staging, '--main-jar', 'nickelsales.jar', '--main-class', $MainClass,
    '--runtime-image', $Runtime, '--add-launcher', "$Name-Console=$ConsoleProps",
    '--dest', $AppOut) + $javaOptions)

$Exe = Join-Path $AppOut "$Name\$Name.exe"
$sizeMb = [math]::Round(((Get-ChildItem (Join-Path $AppOut $Name) -Recurse | Measure-Object Length -Sum).Sum) / 1MB)
Write-Host ''
Write-Host "Built $Exe (version $Version, $sizeMb MB)" -ForegroundColor Green

if ($AppOnly) { return }

# --- 8. MySQL: only what the installed server needs ---------------------
Step "MySQL $MySqlVersion"
$MySqlZip = Get-Cached $MySqlUrl "mysql-$MySqlVersion-winx64.zip" $MySqlSha256
$MySqlOut = Join-Path $Build 'mysql'
# The ZIP is ~1 GB unpacked, mostly debug files and developer libraries. Keep the server,
# the command-line client (the installer runs the schema with it), mysqladmin and mysqldump
# (for support), their DLLs, the plugins, the error messages, character sets and ICU data, and the licence.
$keepTools = 'mysqld.exe', 'mysql.exe', 'mysqladmin.exe', 'mysqldump.exe'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($MySqlZip)
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.Name -eq '') { continue }                                  # a folder
        $relative = $entry.FullName.Substring($entry.FullName.IndexOf('/') + 1)
        $keep = switch -Regex ($relative) {
            '\.(pdb|lib)$'              { $false; break }
            '-debug\.dll$'              { $false; break }
            '^bin/[^/]+\.dll$'          { $true; break }
            '^bin/[^/]+$'               { $keepTools -contains $entry.Name; break }
            '^lib/plugin/debug/'        { $false; break }
            '^lib/plugin/[^/]+\.dll$'   { $true; break }
            '^lib/private/'             { $true; break }   # ICU data for REGEXP; mysqld warns without it
            '^share/'                   { $true; break }
            '^(LICENSE|README)'         { $true; break }
            default                     { $false }
        }
        if ($keep) {
            $target = Join-Path $MySqlOut ($relative -replace '/', '\')
            New-Item -ItemType Directory -Force (Split-Path $target -Parent) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    }
} finally { $zip.Dispose() }
$mysqlMb = [math]::Round(((Get-ChildItem $MySqlOut -Recurse | Measure-Object Length -Sum).Sum) / 1MB)
Write-Host "Kept $mysqlMb MB"

# --- 9. Visual C++ runtime (MySQL needs it; installed only where missing) ---
Step 'Visual C++ runtime'
$VcRedist = Get-Cached $VcRedistUrl 'vc_redist.x64.exe' ''
Copy-Item $VcRedist $Build
Write-Host "Version $((Get-Item $VcRedist).VersionInfo.ProductVersion)"

# --- 10. The installer (Inno Setup) -------------------------------------
Step 'Installer (Inno Setup)'
$Iscc = @("$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
          "$env:ProgramFiles\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Iscc) { throw 'Inno Setup 6 not found. Install it (winget install JRSoftware.InnoSetup) or use -AppOnly.' }
Invoke-Tool $Iscc @('/Qp', "/DAppVersion=$Version", "/DBuildDir=$Build", "/DOutputDir=$Dist",
    "/DSchemaFile=$(Join-Path (Split-Path $Here -Parent) 'database\NickelSales-Database-Config.sql')",
    (Join-Path $Here 'NickelSales.iss'))
$Setup = Join-Path $Dist "NickelSalesSetup-$Version.exe"
Write-Host ''
Write-Host "Built $Setup ($([math]::Round((Get-Item $Setup).Length / 1MB)) MB)" -ForegroundColor Green