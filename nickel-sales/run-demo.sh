#!/usr/bin/env bash
# =====================================================================
#  Nickel Sales Center - Sprint 1 demo (macOS / Linux)
#
#    ./run-demo.sh              JavaFX login screen (LoginDemoApp) - reads
#                               users from the local MySQL database
#    ./run-demo.sh --console    console demo with built-in stub users:
#                               scenarios, then an interactive login prompt
#    ./run-demo.sh --scripted   console scenarios only; exit code 1 if any fail
#    ./run-demo.sh --qa         Rhian role/session/access checks; exit code 1 if any fail
#    ./run-demo.sh --setup      only download JavaFX, don't build or run
#
#  Needs a JDK 17+ (java/javac on PATH, or JAVA_HOME set). The first run
#  downloads the JavaFX jars into lib/javafx, so it needs internet once.
# =====================================================================
set -euo pipefail
cd "$(dirname "$0")"

FX_VERSION="21.0.5"
FX_DIR="lib/javafx"
FX_URL="https://repo1.maven.org/maven2/org/openjfx"
CP="lib/jbcrypt-0.4.jar:lib/mysql-connector-j-9.4.0.jar"

# --- 1. JDK -----------------------------------------------------------
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
    JDK_BIN="$JAVA_HOME/bin"
elif command -v javac >/dev/null 2>&1; then
    JDK_BIN="$(dirname "$(command -v javac)")"
else
    echo "[ERROR] No JDK found. Install JDK 17+ (e.g. https://adoptium.net/ or 'brew install --cask temurin@21')." >&2
    exit 1
fi
JV="$("$JDK_BIN/javac" -version 2>&1 | awk '$1=="javac"{print $2}')"
if (( ${JV%%[.+-]*} < 17 )); then
    echo "[ERROR] javac $JV is too old - this project needs JDK 17 or newer." >&2
    exit 1
fi

# --- 2. JavaFX jars for this OS/CPU --------------------------------------
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64)              PLATFORM="mac-aarch64" ;;
    Darwin-*)                  PLATFORM="mac" ;;
    Linux-aarch64|Linux-arm64) PLATFORM="linux-aarch64" ;;
    Linux-*)                   PLATFORM="linux" ;;
    *) echo "[ERROR] Unsupported OS $(uname -s) - on Windows use run-demo.bat." >&2; exit 1 ;;
esac

mkdir -p "$FX_DIR"
# Drop jars for another version or platform (two copies of a module = Java refuses to start).
for f in "$FX_DIR"/javafx-*.jar; do
    [[ -e "$f" && "$f" != *"-$FX_VERSION-$PLATFORM.jar" ]] && rm -f "$f"
done
for m in base graphics controls; do
    jar="javafx-$m-$FX_VERSION-$PLATFORM.jar"
    if [[ ! -s "$FX_DIR/$jar" ]]; then
        echo "[setup] Downloading $jar ..."
        if ! curl -fsSL -o "$FX_DIR/$jar" "$FX_URL/javafx-$m/$FX_VERSION/$jar"; then
            rm -f "$FX_DIR/$jar"
            echo "[ERROR] Download failed: $FX_URL/javafx-$m/$FX_VERSION/$jar" >&2
            exit 1
        fi
    fi
done
[[ "${1:-}" == "--setup" ]] && { echo "[setup] JavaFX $FX_VERSION ready in nickel-sales/$FX_DIR"; exit 0; }

# --- 3. Compile ---------------------------------------------------------
MAIN="nickelsales.auth.demo.LoginDemoApp"
ARGS=()
case "${1:-}" in
    --console)  MAIN="nickelsales.auth.demo.AuthDemo" ;;
    --scripted) MAIN="nickelsales.auth.demo.AuthDemo"; ARGS=(--scripted) ;;
    --qa)       MAIN="nickelsales.auth.demo.RoleSessionAccessDemo" ;;
esac

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
