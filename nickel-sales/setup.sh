#!/usr/bin/env bash
# =====================================================================
#  Nickel Sales Center - developer setup (macOS / Linux)
#
#  Run "./setup.sh" in a terminal. It checks every dependency and tells
#  you what to install and where to get it:
#
#    Java JDK 17+     required   - offers a one-click install via Homebrew (macOS)
#    JavaFX 21.0.5    required   - downloaded automatically into lib/javafx
#    jBCrypt, MySQL   bundled    - already in lib/
#      Connector/J
#    MySQL Server 8   login screen only - you install it; once it is
#                     running this script creates the database and saves
#                     your MySQL password
#
#  Safe to run as often as you like: anything already done is skipped.
#  run-demo.sh SOURCES this file (quietly), so most of the time you only
#  run it by hand the first time, or when something is missing.
#
#  Database work is done by setup-database.sh (same folder).
#  Sets JDK_BIN for the caller when sourced. No "set -e" here on purpose:
#  this file is sourced and must not change the caller's shell options.
# =====================================================================

NSC_SETUP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$NSC_SETUP_DIR" || return 1 2>/dev/null || exit 1

QUIET=""
[[ "${1:-}" == "--from-run" ]] && QUIET=1

FX_VERSION="21.0.5"
FX_DIR="lib/javafx"
FX_URL="https://repo1.maven.org/maven2/org/openjfx"
FX_MODULES="base graphics controls"
SQL_FILE="$NSC_SETUP_DIR/../database/NickelSales-Database-Config.sql"
TODO=""
JDK_BIN=""
JDK_VER=""
OLD_JDK=""
MYSQL_RUNNING=""
MYSQL_EXE=""

say()  { [[ -z "$QUIET" ]] && echo "$@"; return 0; }
info() { echo "[setup] $*"; }
qinfo(){ [[ -z "$QUIET" ]] && echo "[setup] $*"; return 0; }

# Interactive only when a human is at the keyboard and we weren't called from run-demo.sh
can_prompt() { [[ -z "$QUIET" && -t 0 ]]; }

# ---------------------------------------------------------------------
#  JDK 17+
# ---------------------------------------------------------------------
try_jdk() {
    [[ -n "$JDK_BIN" ]] && return 0
    local cand="${1%/}" jv major
    [[ -x "$cand/javac" ]] || return 0
    jv="$("$cand/javac" -version 2>&1 | awk '$1=="javac"{print $2}')"
    [[ -n "$jv" ]] || return 0
    major="${jv%%[.+-]*}"
    [[ "$major" =~ ^[0-9]+$ ]] || return 0
    if (( major >= 17 )); then
        JDK_BIN="$cand"; JDK_VER="$jv"
    else
        OLD_JDK="$cand (javac $jv)"
    fi
    return 0
}

find_jdk() {
    JDK_BIN=""; OLD_JDK=""
    [[ -n "${JAVA_HOME:-}" ]] && try_jdk "$JAVA_HOME/bin"
    command -v javac >/dev/null 2>&1 && try_jdk "$(dirname "$(command -v javac)")"

    # macOS: ask the system which JDKs are registered
    if [[ -z "$JDK_BIN" && -x /usr/libexec/java_home ]]; then
        local jh
        jh="$(/usr/libexec/java_home -v 17+ 2>/dev/null || true)"
        [[ -n "$jh" ]] && try_jdk "$jh/bin"
    fi

    # Usual install folders (macOS, Linux, Homebrew, SDKMAN, IntelliJ downloads)
    local root d
    for root in \
        /Library/Java/JavaVirtualMachines \
        /usr/lib/jvm \
        "$HOME/.sdkman/candidates/java" \
        "$HOME/.jdks"; do
        [[ -d "$root" ]] || continue
        for d in "$root"/*; do
            [[ -x "$d/Contents/Home/bin/javac" ]] && try_jdk "$d/Contents/Home/bin"
            try_jdk "$d/bin"
        done
    done
    for d in /opt/homebrew/opt/openjdk* /usr/local/opt/openjdk*; do
        [[ -d "$d" ]] && try_jdk "$d/bin"
    done
    return 0
}

install_jdk() {
    echo
    info "Java JDK 17+      : MISSING  (required)"
    [[ -n "$OLD_JDK" ]] && info "  Found only $OLD_JDK, which is too old."
    info "  Download: https://adoptium.net/temurin/releases/?version=21"
    info "  Choose your OS / architecture / JDK, install it, then run this script again."

    if [[ "$(uname -s)" == "Darwin" ]] && command -v brew >/dev/null 2>&1 && can_prompt; then
        echo
        read -r -p "[setup] Or install Temurin JDK 21 for you now using Homebrew? [y/N] " ans
        if [[ "$ans" =~ ^[Yy]$ ]]; then
            if brew install --cask temurin@21; then
                find_jdk
                [[ -z "$JDK_BIN" ]] && {
                    info "The JDK was installed but this terminal cannot see it yet."
                    info "Open a new terminal and run the script again."
                }
            else
                info "Homebrew could not install the JDK. Use the download link above instead."
            fi
        fi
    elif command -v apt-get >/dev/null 2>&1; then
        info "  Debian/Ubuntu: sudo apt install openjdk-21-jdk"
    elif command -v dnf >/dev/null 2>&1; then
        info "  Fedora/RHEL:   sudo dnf install java-21-openjdk-devel"
    fi
    return 0
}

# ---------------------------------------------------------------------
#  JavaFX jars for this OS/CPU, from Maven Central
# ---------------------------------------------------------------------
get_javafx() {
    local platform f m jar src
    case "$(uname -s)-$(uname -m)" in
        Darwin-arm64)              platform="mac-aarch64" ;;
        Darwin-*)                  platform="mac" ;;
        Linux-aarch64|Linux-arm64) platform="linux-aarch64" ;;
        Linux-*)                   platform="linux" ;;
        *) info "Unsupported OS $(uname -s) - on Windows use setup.bat."; return 1 ;;
    esac

    mkdir -p "$FX_DIR"
    # Drop jars from another version or platform - two copies of a module
    # on the module path make Java refuse to start.
    for f in "$FX_DIR"/javafx-*.jar; do
        [[ -e "$f" && "$f" != *"-$FX_VERSION-$platform.jar" ]] && rm -f "$f"
    done
    for m in $FX_MODULES; do
        jar="javafx-$m-$FX_VERSION-$platform.jar"
        if [[ ! -s "$FX_DIR/$jar" ]]; then
            info "Downloading $jar ..."
            src="$FX_URL/javafx-$m/$FX_VERSION/$jar"
            if ! curl -fsSL -o "$FX_DIR/$jar" "$src"; then
                rm -f "$FX_DIR/$jar"
                info "JavaFX            : DOWNLOAD FAILED  (required)"
                info "  Check your internet connection and run this again, or"
                info "  download $src"
                info "  yourself and save it in nickel-sales/$FX_DIR/"
                return 1
            fi
        fi
    done
    return 0
}

# ---------------------------------------------------------------------
#  MySQL: running server (mysqld / mariadbd) and the mysql client
# ---------------------------------------------------------------------
find_mysql() {
    MYSQL_RUNNING=""; MYSQL_EXE=""
    if pgrep -x mysqld >/dev/null 2>&1 || pgrep -x mariadbd >/dev/null 2>&1; then
        MYSQL_RUNNING=1
    fi
    if command -v mysql >/dev/null 2>&1; then
        MYSQL_EXE="$(command -v mysql)"
    else
        local p
        for p in /opt/homebrew/bin/mysql /opt/homebrew/opt/mysql/bin/mysql \
                 /usr/local/bin/mysql /usr/local/mysql/bin/mysql \
                 /Applications/XAMPP/xamppfiles/bin/mysql /opt/lampp/bin/mysql; do
            [[ -x "$p" ]] && { MYSQL_EXE="$p"; break; }
        done
    fi
    return 0
}

msg_mysql_missing() {
    info "MySQL Server 8      : NOT INSTALLED  (needed for the login screen)"
    if [[ "$(uname -s)" == "Darwin" ]]; then
        info "  Easiest:  brew install mysql && brew services start mysql"
        info "  Or download the installer: https://dev.mysql.com/downloads/mysql/"
    else
        info "  Debian/Ubuntu: sudo apt install mysql-server"
        info "  Fedora/RHEL:   sudo dnf install mysql-server"
    fi
    info "  Keep port 3306 and write down the root password - this script asks for it."
}

msg_mysql_stopped() {
    info "MySQL Server        : INSTALLED BUT NOT RUNNING  (needed for the login screen)"
    if [[ "$(uname -s)" == "Darwin" ]]; then
        info "  Start it: brew services start mysql   (or use System Settings > MySQL)"
    else
        info "  Start it: sudo systemctl start mysql   (or mysqld / mariadb)"
    fi
    info "  XAMPP users: start MySQL from the XAMPP control panel."
}

msg_mysql_no_client() {
    info "Database            : cannot check automatically - the mysql client was not found."
    info "  Open database/NickelSales-Database-Config.sql in MySQL Workbench and"
    info "  run it, then save your MySQL password for the app by adding this to"
    info "  your shell profile (~/.zshrc or ~/.bashrc):"
    info "    export NSC_DB_PASSWORD=\"your-mysql-password\""
}

# ---------------------------------------------------------------------
#  Main
# ---------------------------------------------------------------------
nsc_setup_main() {
    if [[ -z "$QUIET" ]]; then
        echo "=================================================================="
        echo " Nickel Sales Center - checking what this computer needs"
        echo "=================================================================="
        echo
    fi

    # 1. JDK
    find_jdk
    [[ -z "$JDK_BIN" ]] && install_jdk
    [[ -z "$JDK_BIN" ]] && return 1
    qinfo "Java JDK 17+      : OK      javac $JDK_VER  ($JDK_BIN)"

    # 2. JavaFX
    get_javafx || return 1
    qinfo "JavaFX $FX_VERSION     : OK      nickel-sales/$FX_DIR"
    qinfo "jBCrypt, Connector/J: OK      bundled in nickel-sales/lib"

    # 3. MySQL
    find_mysql
    if [[ -n "$QUIET" ]]; then
        # Called from run-demo.sh: never prompt, just warn.
        if [[ -z "$MYSQL_RUNNING" ]]; then
            info "Note: MySQL is not running, so the login screen cannot log anyone in."
            info "      Run ./setup.sh for help, or use ./run-demo.sh --console."
        elif [[ -z "${NSC_DB_PASSWORD:-}" ]]; then
            info "Note: NSC_DB_PASSWORD is not set. Run ./setup.sh once to"
            info "      create the database and save your MySQL password."
        fi
        return 0
    fi

    if [[ -z "$MYSQL_RUNNING" ]]; then
        if [[ -n "$MYSQL_EXE" ]]; then msg_mysql_stopped; else msg_mysql_missing; fi
        TODO=1
    else
        info "MySQL Server        : OK      running"
        if [[ -z "$MYSQL_EXE" ]]; then
            msg_mysql_no_client; TODO=1
        else
            bash "$NSC_SETUP_DIR/setup-database.sh" "$MYSQL_EXE" "$SQL_FILE" || TODO=1
        fi
    fi

    echo
    if [[ -n "$TODO" ]]; then
        info "Java side is ready. To use the login screen, finish the MySQL step"
        info "above, then run ./setup.sh again. Meanwhile, ./run-demo.sh --console"
        info "works without a database."
    else
        info "Everything is ready. Run ./run-demo.sh to open the login screen."
        info "Demo login: NSC-00001 / Password123"
    fi
    echo
    return 0
}

nsc_setup_main "$@"
NSC_SETUP_RC=$?
export JDK_BIN

# Works both sourced (return) and executed (exit)
return "$NSC_SETUP_RC" 2>/dev/null || exit "$NSC_SETUP_RC"