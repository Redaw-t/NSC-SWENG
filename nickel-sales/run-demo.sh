#!/usr/bin/env bash
# =====================================================================
#  Nickel Sales Center - Sprint 1 demo (macOS / Linux)
#
#    ./run-demo.sh              JavaFX login screen (LoginDemoApp) - reads
#                               users from the local MySQL database
#    ./run-demo.sh --console    console demo with built-in stub users:
#                               scenarios, then an interactive login prompt
#    ./run-demo.sh --scripted   console scenarios only; exit code 1 if any fail
#    ./run-demo.sh --setup      only run setup.sh (check dependencies, download
#                               JavaFX, set up the database), don't build or run
#
#  The first run downloads JavaFX (via setup.sh), so it needs internet once.
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"

CP="lib/jbcrypt-0.4.jar:lib/mysql-connector-j-9.4.0.jar"

# --setup: just the full interactive setup, nothing else.
if [[ "${1:-}" == "--setup" ]]; then
    exec bash ./setup.sh
fi

# --- 1. JDK + JavaFX (setup.sh skips whatever is already there) ----------
# setup.sh is SOURCED so that it can hand JDK_BIN back to this script.
# It manages its own error handling, so suspend "set -e" while it runs.
set +e
# shellcheck source=setup.sh
source ./setup.sh --from-run
SETUP_RC=$?
set -e
[[ $SETUP_RC -eq 0 ]] || exit 1

# --- 2. Pick what to run --------------------------------------------------
MAIN="nickelsales.auth.demo.LoginDemoApp"
ARGS=()
case "${1:-}" in
    --console)  MAIN="nickelsales.auth.demo.AuthDemo" ;;
    --scripted) MAIN="nickelsales.auth.demo.AuthDemo"; ARGS=(--scripted) ;;
esac

# --- 3. Compile everything in src/ and test/ into a clean out/ -----------
rm -rf out && mkdir out
find src test -name '*.java' | sed 's/.*/"&"/' > out/sources.txt
echo "Compiling..."
"$JDK_BIN/javac" -encoding UTF-8 --module-path "$FX_DIR" --add-modules javafx.controls \
    -cp "$CP" -d out @out/sources.txt
[[ -d resources ]] && cp -R resources/. out/

# --- 4. Run ---------------------------------------------------------------
echo "Running $MAIN ..."
echo
exec "$JDK_BIN/java" --module-path "$FX_DIR" --add-modules javafx.controls \
    -cp "out:$CP" "$MAIN" ${ARGS[@]+"${ARGS[@]}"}