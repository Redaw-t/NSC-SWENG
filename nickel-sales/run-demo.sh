#!/usr/bin/env bash
# =====================================================================
#  Nickel Sales Center Application - run and scripted checks (macOS / Linux)
#
#    ./run-demo.sh              the app (DevApp): login, home screen, modules.
#                               Users and sales from the local MySQL database,
#                               TEST products until Product & Inventory is connected
#    ./run-demo.sh --console    console demo with built-in stub users:
#                               scenarios, then an interactive login prompt
#    ./run-demo.sh --scripted   run ALL self-tests (before you push); exit code 1 if any fail
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
MAIN="app.DevApp"
ARGS=()
case "${1:-}" in
    --console)  MAIN="auth.AuthDemo" ;;
esac

# --- 3. Compile everything in src/ and test/ into a clean out/ -----------
rm -rf out && mkdir out
find src test -name '*.java' | sed 's/.*/"&"/' > out/sources.txt
echo "Compiling..."
"$JDK_BIN/javac" -encoding UTF-8 --module-path "$FX_DIR" --add-modules javafx.controls \
    -cp "$CP" -d out @out/sources.txt
[[ -d resources ]] && cp -R resources/. out/

# --- --scripted: every self-test, with the same JDK that compiled them ----
if [[ "${1:-}" == "--scripted" ]]; then
    FAILED=""
    for check in "auth.AuthDemo --scripted" auth.AuthzDemo \
                 auth.UserAdminDemo pos.PosContractCheck pos.CartCheck \
                 pos.CheckoutCheck pos.ReceiptCheck app.AppCheck; do
        echo
        echo "----- ${check%% *}"
        # shellcheck disable=SC2086
        "$JDK_BIN/java" -cp "out:$CP:$FX_DIR/*" $check || FAILED="$FAILED ${check%% *}"
    done
    echo
    echo "================================================================="
    if [[ -n "$FAILED" ]]; then echo " CHECKS FAILED:$FAILED"; else echo " ALL CHECKS PASSED"; fi
    echo "================================================================="
    [[ -z "$FAILED" ]]
    exit $?
fi

# --- 4. Run ---------------------------------------------------------------
echo "Running $MAIN ..."
echo
exec "$JDK_BIN/java" --module-path "$FX_DIR" --add-modules javafx.controls \
    -cp "out:$CP" "$MAIN" ${ARGS[@]+"${ARGS[@]}"}