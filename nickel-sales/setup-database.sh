#!/usr/bin/env bash
# =====================================================================
#  Nickel Sales Center - database check / first-time setup (macOS / Linux)
#
#  Called by setup.sh once a running MySQL server has been found:
#    1. connects with NSC_DB_USER (default root) / NSC_DB_PASSWORD,
#       asking for the password (hidden) if it is not set or is wrong,
#    2. creates NickelSales_Database from the SQL script if it is missing,
#    3. offers to save the password as NSC_DB_PASSWORD so the app can use it.
#
#  Usage:  setup-database.sh <path-to-mysql> <path-to-sql-file>
#  Exit code 0 = database ready, 1 = not ready (the reason is printed).
# =====================================================================

MYSQL_BIN="${1:-}"
SQL_FILE="${2:-}"
if [[ -z "$MYSQL_BIN" || -z "$SQL_FILE" ]]; then
    echo "Usage: $0 <path-to-mysql> <path-to-sql-file>" >&2
    exit 1
fi

DB_USER="${NSC_DB_USER:-root}"
PW="${NSC_DB_PASSWORD:-}"
ASKED=""
CNF="$(mktemp "${TMPDIR:-/tmp}/nsc-mysql.XXXXXX")"
trap 'rm -f "$CNF"' EXIT

step() { echo "[setup] $*"; }

read_password() {
    # Prompt on the terminal even if stdin is redirected; fail if there is none.
    if [[ ! -t 0 && ! -r /dev/tty ]]; then return 1; fi
    read -r -s -p "[setup] MySQL password for user '$DB_USER': " PW </dev/tty
    echo
    ASKED=1
}

ask_yes_no() {
    local ans
    read -r -p "[setup] $1 (Y/N) " ans </dev/tty || return 1
    [[ "$ans" =~ ^[[:space:]]*[Yy] ]]
}

# The password goes in a temporary option file (mode 600, deleted on exit),
# so it never appears on a command line or in the process list.
write_option_file() {
    local esc="${PW//\\/\\\\}"
    esc="${esc//\"/\\\"}"
    : > "$CNF"
    chmod 600 "$CNF"
    {
        echo "[client]"
        echo "user=$DB_USER"
        echo "password=\"$esc\""
    } >> "$CNF"
}

# Sets R_CODE and R_TEXT. Optional 2nd argument is text piped to mysql's stdin.
run_mysql() {
    local args=("$1") input="${2:-}"
    write_option_file
    if [[ -n "$input" ]]; then
        R_TEXT="$(printf '%s' "$input" | "$MYSQL_BIN" --defaults-extra-file="$CNF" 2>&1)"
    else
        R_TEXT="$("$MYSQL_BIN" --defaults-extra-file="$CNF" -N -s -e "$1" 2>&1)"
    fi
    R_CODE=$?
}

# Persist NSC_DB_PASSWORD in the user's shell profile.
profile_file() {
    case "$(basename "${SHELL:-}")" in
        zsh)  echo "$HOME/.zshrc" ;;
        bash) [[ "$(uname -s)" == "Darwin" ]] && echo "$HOME/.bash_profile" || echo "$HOME/.bashrc" ;;
        *)    echo "$HOME/.profile" ;;
    esac
}

save_password() {
    local prof; prof="$(profile_file)"
    if [[ -f "$prof" ]]; then
        grep -v '^export NSC_DB_PASSWORD=' "$prof" > "$prof.nsc.tmp" && mv "$prof.nsc.tmp" "$prof"
    fi
    printf 'export NSC_DB_PASSWORD=%q\n' "$PW" >> "$prof"
    step "Saved to $prof. Open a new terminal (or run: source $prof);"
    step "run-demo.sh will use it from now on."
}

main() {
    if [[ ! -f "$SQL_FILE" ]]; then
        step "Database        : SQL file not found: $SQL_FILE"
        return 1
    fi

    if [[ -z "$PW" ]]; then
        read_password || {
            step "No MySQL password available. Set NSC_DB_PASSWORD, or run ./setup.sh in a terminal."
            return 1
        }
    fi

    # --- 1. connect, retrying the password up to 3 times -------------------
    local attempt=0
    while true; do
        attempt=$((attempt + 1))
        run_mysql 'SELECT COUNT(*) FROM NickelSales_Database.Users'
        # Success, or the server answered but the database/table is missing
        # (1049 unknown database, 1046 no database selected, 1146 no such table).
        if (( R_CODE == 0 )) || [[ "$R_TEXT" =~ ERROR\ 10(49|46)|ERROR\ 1146 ]]; then
            break
        fi
        if [[ "$R_TEXT" == *"ERROR 1045"* ]] && (( attempt < 3 )); then
            step "MySQL rejected the password for '$DB_USER'. Try again."
            read_password || return 1
            continue
        fi
        if [[ "$R_TEXT" == *"ERROR 1045"* ]]; then
            step "Could not log in to MySQL as '$DB_USER'."
            step "If your MySQL user is not root, set it first:  export NSC_DB_USER=yourname"
        elif [[ "$R_TEXT" =~ ERROR\ 200[0-9] ]]; then
            step "Could not reach the MySQL server on this computer. Is it running?"
        else
            step "MySQL error: $R_TEXT"
        fi
        return 1
    done

    # --- 2. create the database if it is missing or empty ----------------
    local count=0
    if (( R_CODE == 0 )); then
        count="$(printf '%s\n' "$R_TEXT" | tail -n 1 | tr -d '[:space:]')"
        [[ "$count" =~ ^[0-9]+$ ]] || count=0
    fi
    if (( count > 0 )); then
        step "Database        : NickelSales_Database is ready ($count users)."
    else
        step "Database        : NickelSales_Database has not been created yet."
        if ! ask_yes_no "Create it now from database/NickelSales-Database-Config.sql?"; then
            step "Skipped. Run setup.sh again when you want to create it."
            return 1
        fi
        run_mysql "" "$(cat "$SQL_FILE")"
        if (( R_CODE != 0 )); then
            step "Creating the database failed:"
            echo "$R_TEXT"
            return 1
        fi
        step "Database        : created NickelSales_Database with the demo users."
    fi

    # --- 3. remember a password the user typed in -------------------------
    if [[ -n "$ASKED" && "$PW" != "${NSC_DB_PASSWORD:-}" ]]; then
        if ask_yes_no "Save this MySQL password for the app (adds NSC_DB_PASSWORD to your shell profile)?"; then
            save_password
        else
            step "Not saved. The app will only log in if NSC_DB_PASSWORD is set."
        fi
    fi
    return 0
}

main
exit $?