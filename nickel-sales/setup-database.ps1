# =====================================================================
#  Nickel Sales Center - database check / first-time setup
#
#  Called by setup.bat once a running MySQL server has been found:
#    1. connects with NSC_DB_USER (default root) / NSC_DB_PASSWORD,
#       asking for the password (hidden) if it is not set or is wrong,
#    2. creates NickelSales_Database from the SQL script if it is missing,
#    3. offers to save the password as NSC_DB_PASSWORD so the app can use it.
#
#  Exit code 0 = database ready, 1 = not ready (the reason is printed).
# =====================================================================
param(
    [Parameter(Mandatory = $true)][string]$MySql,
    [Parameter(Mandatory = $true)][string]$SqlFile
)
$ErrorActionPreference = 'Continue'

$user = if ($env:NSC_DB_USER) { $env:NSC_DB_USER } else { 'root' }
$script:pw = $env:NSC_DB_PASSWORD
$script:askedForPassword = $false
$cnf = Join-Path $env:TEMP ("nsc-mysql-" + [guid]::NewGuid().ToString('N') + ".cnf")

function Write-Step([string]$msg) { Write-Host "[setup] $msg" }

function Read-Password {
    $secure = Read-Host "[setup] MySQL password for user '$user'" -AsSecureString
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}

# The password goes in a temporary option file, deleted straight after each
# mysql call, so it never appears on a command line or lingers on disk.
function Write-OptionFile {
    $escaped = ($script:pw -replace '\\', '\\') -replace '"', '\"'
    $lines = @('[client]', "user=$user", "password=`"$escaped`"")
    [IO.File]::WriteAllLines($cnf, $lines, (New-Object Text.UTF8Encoding $false))
}

function Invoke-MySql([string[]]$MySqlArgs, [string]$InputText) {
    Write-OptionFile
    try {
        if ($InputText) {
            $out = $InputText | & $MySql "--defaults-extra-file=$cnf" @MySqlArgs 2>&1
        } else {
            $out = & $MySql "--defaults-extra-file=$cnf" @MySqlArgs 2>&1
        }
        $code = $LASTEXITCODE
    } finally {
        Remove-Item $cnf -Force -ErrorAction SilentlyContinue
    }
    return @{ Code = $code; Text = ($out | Out-String).Trim() }
}

function Ask-YesNo([string]$question) {
    $answer = Read-Host "[setup] $question (Y/N)"
    return $answer -match '^\s*[Yy]'
}

$OutputEncoding = New-Object Text.UTF8Encoding $false

function Main {
    if (-not $script:pw) { $script:pw = Read-Password; $script:askedForPassword = $true }

    # --- 1. connect, retrying the password up to 3 times -------------------
    $attempt = 0
    while ($true) {
        $attempt++
        $r = Invoke-MySql @('-N', '-s', '-e', 'SELECT COUNT(*) FROM NickelSales_Database.Users')
        if ($r.Code -eq 0 -or $r.Text -match 'ERROR 10(49|46)|ERROR 1146') { break }
        if ($r.Text -match 'ERROR 1045' -and $attempt -lt 3) {
            Write-Step "MySQL rejected the password for '$user'. Try again."
            $script:pw = Read-Password; $script:askedForPassword = $true
            continue
        }
        if ($r.Text -match 'ERROR 1045') {
            Write-Step "Could not log in to MySQL as '$user'."
            Write-Step "If your MySQL user is not root, set it first:  setx NSC_DB_USER yourname"
        } elseif ($r.Text -match 'ERROR 200[0-9]') {
            Write-Step "Could not reach the MySQL server on this computer. Is it running?"
        } else {
            Write-Step "MySQL error: $($r.Text)"
        }
        return 1
    }

    # --- 2. create the database if it is missing or empty ----------------
    $count = 0
    if ($r.Code -eq 0) { [int]::TryParse(($r.Text -split "`n")[-1].Trim(), [ref]$count) | Out-Null }
    if ($count -gt 0) {
        Write-Step "Database        : NickelSales_Database is ready ($count users)."
    } else {
        Write-Step "Database        : NickelSales_Database has not been created yet."
        if (-not (Ask-YesNo "Create it now from database\NickelSales-Database-Config.sql?")) {
            Write-Step "Skipped. Run setup.bat again when you want to create it."
            return 1
        }
        $sql = [IO.File]::ReadAllText($SqlFile)
        $r = Invoke-MySql @() $sql
        if ($r.Code -ne 0) {
            Write-Step "Creating the database failed:"
            Write-Host $r.Text
            return 1
        }
        Write-Step "Database        : created NickelSales_Database with the demo users."
    }

    # --- 3. remember a password the user typed in -------------------------
    if ($script:askedForPassword -and $script:pw -ne $env:NSC_DB_PASSWORD) {
        if (Ask-YesNo "Save this MySQL password for the app (sets NSC_DB_PASSWORD for your Windows account)?") {
            [Environment]::SetEnvironmentVariable('NSC_DB_PASSWORD', $script:pw, 'User')
            Write-Step "Saved. Close this window; run-demo.bat will use it from now on."
        } else {
            Write-Step "Not saved. The app will only log in if NSC_DB_PASSWORD is set."
        }
    }
    return 0
}

try { $code = Main } finally { if (Test-Path $cnf) { Remove-Item $cnf -Force } }
exit $code
